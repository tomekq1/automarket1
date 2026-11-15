package pl.automarket;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AutoMarketClient implements ClientModInitializer {

    // ====== USTAWIENIA (zmienisz je komendami w grze) ======
    static boolean enabled = false;
    static double maxPrice = 1000;      // /automarket max <cena>
    static String nameFilter = "";      // /automarket filtr <tekst>  (pusty = każdy przedmiot)
    static int cooldownTicks = 10;      // odstęp między kliknięciami (20 ticków = 1 s)

    // DOPASUJ do wyglądu lore na anarchia.gg! Grupa 1 = liczba, grupa 2 = opcjonalny sufiks k/m.
    static final Pattern PRICE = Pattern.compile(
            "(?i)(?:cena|price)\\s*:?\\s*\\$?\\s*([0-9][0-9 .,]*)\\s*([km])?");

    // Jeśli po kliknięciu pojawia się okno potwierdzenia: numer slotu przycisku "Potwierdź".
    // -1 = brak okna potwierdzenia.
    static int confirmSlot = -1;

    private int cooldown = 0;
    private KeyBinding toggleKey;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.automarket.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "category.automarket"));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("automarket")
                        .then(ClientCommandManager.literal("max")
                                .then(ClientCommandManager.argument("cena", DoubleArgumentType.doubleArg(0))
                                        .executes(ctx -> {
                                            maxPrice = DoubleArgumentType.getDouble(ctx, "cena");
                                            msg("Maksymalna cena: " + maxPrice);
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("filtr")
                                .then(ClientCommandManager.argument("tekst", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            nameFilter = StringArgumentType.getString(ctx, "tekst").toLowerCase();
                                            msg("Filtr nazwy: '" + nameFilter + "'");
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("potwierdz")
                                .then(ClientCommandManager.argument("slot", DoubleArgumentType.doubleArg(-1, 53))
                                        .executes(ctx -> {
                                            confirmSlot = (int) DoubleArgumentType.getDouble(ctx, "slot");
                                            msg("Slot potwierdzenia: " + confirmSlot);
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("toggle")
                                .executes(ctx -> { toggle(); return 1; }))));

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void toggle() {
        enabled = !enabled;
        msg(enabled ? "WŁĄCZONY" : "WYŁĄCZONY");
    }

    private static void msg(String s) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(Text.literal("[AutoMarket] " + s), false);
    }

    private void tick(MinecraftClient mc) {
        while (toggleKey.wasPressed()) toggle();

        if (!enabled || mc.player == null || mc.interactionManager == null) return;
        if (cooldown > 0) { cooldown--; return; }
        if (!(mc.currentScreen instanceof GenericContainerScreen screen)) return;

        GenericContainerScreenHandler handler = screen.getScreenHandler();
        int containerSlots = handler.getRows() * 9; // tylko okno rynku, bez ekwipunku gracza

        // Okno potwierdzenia (jeśli skonfigurowane) ma pierwszeństwo.
        if (confirmSlot >= 0 && confirmSlot < containerSlots && looksLikeConfirm(handler.getSlot(confirmSlot).getStack())) {
            click(mc, handler, confirmSlot);
            return;
        }

        for (int i = 0; i < containerSlots; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) continue;
            if (!nameFilter.isEmpty() && !stack.getName().getString().toLowerCase().contains(nameFilter)) continue;

            double price = readPrice(stack);
            if (price < 0 || price > maxPrice) continue;

            click(mc, handler, i);
            msg("Klik: " + stack.getName().getString() + " za " + price);
            return; // jedno kliknięcie na cykl
        }
    }

    private boolean looksLikeConfirm(ItemStack s) {
        if (s.isEmpty()) return false;
        String n = s.getName().getString().toLowerCase();
        return n.contains("potwierd") || n.contains("confirm") || n.contains("kup");
    }

    private void click(MinecraftClient mc, GenericContainerScreenHandler h, int slot) {
        mc.interactionManager.clickSlot(h.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
        cooldown = cooldownTicks;
    }

    /** Zwraca cenę z lore lub -1, jeśli nie znaleziono. */
    private double readPrice(ItemStack stack) {
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore == null) return -1;
        for (Text line : lore.lines()) {
            Matcher m = PRICE.matcher(line.getString());
            if (!m.find()) continue;
            try {
                String num = m.group(1).replace(" ", "").replace(",", ".");
                double v = Double.parseDouble(num);
                String suf = m.group(2);
                if (suf != null) v *= suf.equalsIgnoreCase("k") ? 1_000 : 1_000_000;
                return v;
            } catch (NumberFormatException ignored) { }
        }
        return -1;
    }
}
