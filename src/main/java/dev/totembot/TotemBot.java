package dev.totembot;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keys:  F9 = start/stop (kill switch)   F8 = dump open GUI (slot, name, lore) to chat+log
 * Fill the CONFIG block using the F8 dump on the real server GUI.
 */
public class TotemBot implements ClientModInitializer {

    // ================= CONFIG =================
    static final String ORDERS_CMD = "orders totem";   // command that opens the orders menu
    static final String AH_CMD = "ah totem";           // command that opens AH search for totems
    static final int NEW_ORDER_SLOT = -1;              // slot of "new order" button  (from F8 dump)
    static final int CONFIRM_SLOT = -1;                // slot of confirm button      (from F8 dump)
    static final int QTY = 135;
    static final double STEP = 0.1;
    static final boolean DO_DROP = true;
    static final boolean DO_SELL = true;
    static final int STATE_TIMEOUT_TICKS = 20 * 30;    // auto-stop if stuck in a state
    // ==========================================

    enum S { IDLE, OPEN_ORDERS, READ_ORDERS, PLACE_ORDER, WAIT_ITEMS, DROP, OPEN_AH, READ_AH, SELL, COOLDOWN }

    static final Pattern PRICE = Pattern.compile("\\$\\s*([\\d,]+(?:\\.\\d+)?)\\s*([KkMmBb]?)");
    final Random rnd = new Random();
    S state = S.IDLE;
    int wait = 0, inState = 0;
    double target = 0;
    boolean f8Down, f9Down;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    // ---------- helpers ----------
    void say(Minecraft mc, String s) {
        System.out.println("[TotemBot] " + s);
    }

    /** human-like pause: gaussian around the middle of [minMs,maxMs] */
    void pause(int minMs, int maxMs) {
        double mid = (minMs + maxMs) / 2.0, sd = (maxMs - minMs) / 5.0;
        double ms = Math.max(minMs, Math.min(maxMs, mid + rnd.nextGaussian() * sd));
        wait = Math.max(1, (int) Math.round(ms / 50.0));
    }

    void go(S s) { state = s; inState = 0; }

    void stop(Minecraft mc, String why) { state = S.IDLE; say(mc, "stopped: " + why); }

    static double parsePrice(String line) {
        Matcher m = PRICE.matcher(line);
        if (!m.find()) return -1;
        double v = Double.parseDouble(m.group(1).replace(",", ""));
        return switch (m.group(2).toLowerCase()) { case "k" -> v * 1e3; case "m" -> v * 1e6; case "b" -> v * 1e9; default -> v; };
    }

    List<Component> lore(ItemStack st) {
        var l = st.get(DataComponents.LORE);
        return l == null ? List.of() : l.lines();
    }

    int totems(Minecraft mc) {
        int n = 0;
        for (ItemStack s : mc.player.getInventory().getNonEquipmentItems())
            if (s.is(Items.TOTEM_OF_UNDYING)) n += s.getCount();
        return n;
    }

    AbstractContainerMenu openMenu(Minecraft mc) {
        return mc.gui.screen() instanceof AbstractContainerScreen<?> s ? s.getMenu() : null;
    }

    void click(Minecraft mc, AbstractContainerMenu m, int slot) {
        mc.gameMode.handleContainerInput(m.containerId, slot, 0, ContainerInput.PICKUP, mc.player);
    }

    /** best price found in the open GUI: max (orders) or min (AH) over all lore lines */
    double scan(Minecraft mc, AbstractContainerMenu m, boolean wantMax) {
        double best = wantMax ? -1 : Double.MAX_VALUE;
        for (Slot sl : m.slots) {
            if (sl.container == mc.player.getInventory()) continue;
            ItemStack st = sl.getItem();
            if (!st.is(Items.TOTEM_OF_UNDYING)) continue;
            for (Component c : lore(st)) {
                double p = parsePrice(c.getString());
                if (p < 0) continue;
                best = wantMax ? Math.max(best, p) : Math.min(best, p);
            }
        }
        return (wantMax ? best < 0 : best == Double.MAX_VALUE) ? -1 : best;
    }

    void dump(Minecraft mc) {
        AbstractContainerMenu m = openMenu(mc);
        if (m == null) { say(mc, "no container open"); return; }
        for (Slot sl : m.slots) {
            if (sl.container == mc.player.getInventory()) continue;
            ItemStack st = sl.getItem();
            if (st.isEmpty()) continue;
            StringBuilder sb = new StringBuilder("slot " + sl.index + " | " + st.getHoverName().getString());
            for (Component c : lore(st)) sb.append(" | ").append(c.getString());
            System.out.println(sb);
            if (sl.index < 12) say(mc, sb.toString());
        }
        say(mc, "full dump in latest.log");
    }

    static String fmt(double v) { return String.format(java.util.Locale.ROOT, "%.1f", v); }

    // ---------- main loop ----------
    void tick(Minecraft mc) {
        if (mc.player == null || mc.gameMode == null) return;
        long win = mc.getWindow().handle();
        boolean f8 = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_F8) == GLFW.GLFW_PRESS;
        boolean f9 = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_F9) == GLFW.GLFW_PRESS;
        if (f8 && !f8Down) dump(mc);
        if (f9 && !f9Down) {
            if (state == S.IDLE) { go(S.OPEN_ORDERS); say(mc, "started"); } else stop(mc, "manual");
        }
        f8Down = f8; f9Down = f9;
        if (state == S.IDLE) return;

        if (++inState > STATE_TIMEOUT_TICKS) { stop(mc, "stuck in " + state); return; }
        if (wait > 0) { wait--; return; }

        AbstractContainerMenu m = openMenu(mc);
        switch (state) {
            case OPEN_ORDERS -> {
                mc.player.connection.sendCommand(ORDERS_CMD);
                go(S.READ_ORDERS); pause(600, 1200);
            }
            case READ_ORDERS -> {
                if (m == null) return;
                double top = scan(mc, m, true);
                if (top < 0) { stop(mc, "no price found - check lore format with F8"); return; }
                target = Math.round((top + STEP) * 10.0) / 10.0;
                say(mc, "top order " + fmt(top) + " -> mine " + fmt(target));
                go(S.PLACE_ORDER); pause(300, 800);
            }
            case PLACE_ORDER -> {
                if (m == null || NEW_ORDER_SLOT < 0) { stop(mc, "set NEW_ORDER_SLOT/CONFIRM_SLOT from F8 dump"); return; }
                click(mc, m, NEW_ORDER_SLOT);
                pause(400, 900);
                // Price / quantity entry (sign, anvil or chat) is server specific.
                // Send values via the chat-style prompt if the server uses one:
                mc.player.connection.sendChat(fmt(target));
                pause(400, 900);
                mc.player.connection.sendChat(String.valueOf(QTY));
                pause(500, 1000);
                if (CONFIRM_SLOT >= 0 && openMenu(mc) != null) click(mc, openMenu(mc), CONFIRM_SLOT);
                go(S.WAIT_ITEMS); pause(1500, 3000);
            }
            case WAIT_ITEMS -> {
                if (totems(mc) > 0) { go(DO_DROP ? S.DROP : S.OPEN_AH); pause(500, 1500); }
            }
            case DROP -> {
                mc.player.setXRot(78f + rnd.nextFloat() * 12f);
                var inv = mc.player.inventoryMenu;
                for (Slot sl : inv.slots) {
                    if (sl.getItem().is(Items.TOTEM_OF_UNDYING)) {
                        mc.gameMode.handleContainerInput(inv.containerId, sl.index, 1, ContainerInput.THROW, mc.player);
                        pause(250, 700);
                        return; // one stack per action, never several in one tick
                    }
                }
                go(DO_SELL ? S.OPEN_AH : S.COOLDOWN); pause(500, 1200);
            }
            case OPEN_AH -> {
                if (!DO_SELL) { go(S.COOLDOWN); return; }
                mc.player.connection.sendCommand(AH_CMD);
                go(S.READ_AH); pause(600, 1200);
            }
            case READ_AH -> {
                if (m == null) return;
                double low = scan(mc, m, false);
                if (low < 0) { stop(mc, "no AH price found"); return; }
                target = Math.round((low - STEP) * 10.0) / 10.0;
                say(mc, "lowest AH " + fmt(low) + " -> listing at " + fmt(target));
                mc.player.closeContainer();
                go(S.SELL); pause(400, 900);
            }
            case SELL -> {
                if (totems(mc) == 0) { go(S.COOLDOWN); return; }
                mc.player.connection.sendCommand("ah sell " + fmt(target));
                pause(900, 1800);
                go(S.COOLDOWN);
            }
            case COOLDOWN -> {
                pause(60_000, 180_000);
                go(S.OPEN_ORDERS);
            }
            default -> {}
        }
    }
}
