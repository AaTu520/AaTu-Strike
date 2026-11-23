package cn.aatu.strike;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

final class ChargeController {
    private static final int BAR_SEGMENTS = 10;
    private static final int HUD_UPDATE_TICKS = 4;
    private static final int HUD_REFRESH_TICKS = 20;
    private static final Map<UUID, ChargeState> STATES = new HashMap<>();

    private ChargeController() {}

    static void tick(ServerPlayer player, StrikeConfig.Settings settings) {
        if (!canCharge(player, settings)) {
            clear(player);
            return;
        }
        ChargeState state = STATES.computeIfAbsent(player.getUUID(), ignored -> new ChargeState());
        if (state.awaitingRelease) return;
        long now = player.level().getGameTime();
        if (state.lastChargedTick != now) {
            state.ticks = Math.min(state.ticks + 1, settings.fullChargeTicks());
            state.lastChargedTick = now;
        }
        publish(player, state, settings, now);
    }

    static double consume(ServerPlayer player, StrikeConfig.Settings settings) {
        double fraction = consumeEnergy(player, settings);
        return settings == null ? 1.0 : settings.chargeMultiplier(fraction);
    }

    static double energy(ServerPlayer player, StrikeConfig.Settings settings) {
        if (!canCharge(player, settings)) return 0.0;
        ChargeState state = STATES.get(player.getUUID());
        return state == null || state.awaitingRelease ? 0.0
                : Math.clamp((double) state.ticks / settings.fullChargeTicks(), 0.0, 1.0);
    }

    static double consumeEnergy(ServerPlayer player, StrikeConfig.Settings settings) {
        if (!canCharge(player, settings)) {
            clear(player);
            return 0.0;
        }
        // An attack can arrive before the first charging tick; that press is still consumed.
        ChargeState state = STATES.computeIfAbsent(player.getUUID(), ignored -> new ChargeState());
        if (state.awaitingRelease) return 0.0;
        double fraction = Math.clamp((double) state.ticks / settings.fullChargeTicks(), 0.0, 1.0);
        state.ticks = 0;
        state.awaitingRelease = true;
        state.lastChargedTick = player.level().getGameTime();
        // Keep the consumed state until release; hiding the bar must not rearm charging.
        hideBar(player, state);
        return fraction;
    }

    private static boolean canCharge(ServerPlayer player, StrikeConfig.Settings settings) {
        return settings != null && settings.enabled() && settings.chargeEnabled()
                && player.isAlive() && !player.isSpectator() && !player.isSleeping()
                && player.isShiftKeyDown();
    }

    private static void publish(ServerPlayer player, ChargeState state, StrikeConfig.Settings settings,
                                long now) {
        if (state.lastHudTick != Long.MIN_VALUE
                && now - state.lastHudTick < HUD_UPDATE_TICKS) return;
        double fraction = Math.clamp((double) state.ticks / settings.fullChargeTicks(), 0.0, 1.0);
        int filled = (int) Math.floor(fraction * BAR_SEGMENTS);
        int bonusPercent = (int) Math.floor(fraction * settings.maximumBonusPercent());
        String text = "蓄力 " + "■".repeat(filled) + "□".repeat(BAR_SEGMENTS - filled) + " +" + bonusPercent + "%";
        // Action-bar messages expire on the client. Refresh unchanged bars once per second.
        if (!text.equals(state.lastText) || now - state.lastHudTick >= HUD_REFRESH_TICKS) {
            player.displayClientMessage(Component.literal(text), true);
            state.lastText = text;
            state.lastHudTick = now;
        }
    }

    private static void hideBar(ServerPlayer player, ChargeState state) {
        if (state.lastText != null) {
            player.displayClientMessage(Component.empty(), true);
            state.lastText = null;
        }
    }

    static void clear(ServerPlayer player) {
        ChargeState state = STATES.remove(player.getUUID());
        if (state != null) hideBar(player, state);
    }

    static void forget(ServerPlayer player) { STATES.remove(player.getUUID()); }

    static void clearAll() { STATES.clear(); }

    private static final class ChargeState {
        private int ticks;
        private boolean awaitingRelease;
        private long lastChargedTick = Long.MIN_VALUE;
        private long lastHudTick = Long.MIN_VALUE;
        private String lastText;
    }
}
