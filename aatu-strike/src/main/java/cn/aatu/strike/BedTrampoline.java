package cn.aatu.strike;

import cn.aatu.strike.mixin.ServerConnectionAccessor;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

final class BedTrampoline {
    private static final double FALL_HEIGHT_MULTIPLIER = 1.25;
    private static final double MINIMUM_HEIGHT = 2.0;
    private static final double MAXIMUM_UPWARD_SPEED = 48.0 / 20.0;
    private static final double MOTION_PACKET_LIMIT = 3.9;
    private static final double VERTICAL_DRAG = (double) 0.98F;
    private static final double LOG_VERTICAL_DRAG = Math.log(VERTICAL_DRAG);
    private static final double MINIMUM_PROGRESS = 0.001;
    private static final double TELEPORT_DISTANCE_SQUARED = 16.0 * 16.0;
    private static final int MAXIMUM_STALLED_TICKS = 20;
    private static final int SPEED_SEARCH_STEPS = 18;
    private static final Map<UUID, Bounce> ACTIVE = new HashMap<>();

    private BedTrampoline() {}

    static boolean landed(ServerPlayer player, float fallDistance, StrikeConfig.Settings settings) {
        if (settings == null || !settings.enabled() || !settings.bedTrampolineEnabled()
                || !player.isAlive() || player.isSpectator() || player.isPassenger()) return false;
        long now = player.level().getGameTime();
        Bounce previous = ACTIVE.get(player.getUUID());
        if (previous != null && previous.landingTick == now) return true;
        forget(player);

        // Sneaking retains the bed's normal way to stop bouncing, without restoring fall damage.
        if (player.isSuppressingBounce() || !canBounce(player) || !Float.isFinite(fallDistance)) return true;
        double height = Math.max(MINIMUM_HEIGHT, fallDistance * FALL_HEIGHT_MULTIPLIER);
        double apex = player.getY() + height;
        if (!Double.isFinite(apex)) return true;
        ACTIVE.put(player.getUUID(), new Bounce(apex, player.position(), now));
        return true;
    }

    static void tick(ServerPlayer player, StrikeConfig.Settings settings) {
        Bounce bounce = ACTIVE.get(player.getUUID());
        if (bounce == null) return;
        if (settings == null || !settings.enabled() || !settings.bedTrampolineEnabled() || !canBounce(player)) {
            forget(player);
            return;
        }
        long now = player.level().getGameTime();
        if (bounce.lastTick == now) return;
        bounce.lastTick = now;
        Vec3 position = player.position();
        Vec3 displacement = position.subtract(bounce.lastPosition);
        bounce.lastPosition = position;
        if (!finite(displacement) || displacement.lengthSqr() > TELEPORT_DISTANCE_SQUARED) {
            forget(player);
            return;
        }

        if (bounce.launched) {
            bounce.leftGround |= !player.onGround();
            bounce.hasAscended |= displacement.y > MINIMUM_PROGRESS;
            bounce.stalledTicks = displacement.y > MINIMUM_PROGRESS ? 0 : bounce.stalledTicks + 1;
            if ((bounce.leftGround && player.onGround()) || bounce.stalledTicks >= MAXIMUM_STALLED_TICKS
                    || (bounce.hasAscended && displacement.y < -MINIMUM_PROGRESS)) {
                forget(player);
                return;
            }
            // Wait for accepted upward movement rather than repeating an unacknowledged launch.
            if (displacement.y <= MINIMUM_PROGRESS) return;
        }

        double remainingHeight = bounce.apex - position.y;
        if (remainingHeight <= 0.0) {
            forget(player);
            return;
        }
        if (bounce.coasting) {
            allowLegitimateAscent(player);
            return;
        }
        double gravity = player.getGravity();
        double coastingHeight = heightFromSpeed(MAXIMUM_UPWARD_SPEED, gravity);
        if (!Double.isFinite(coastingHeight) || coastingHeight <= 0.0) {
            forget(player);
            return;
        }
        double speed = MAXIMUM_UPWARD_SPEED;
        if (remainingHeight <= coastingHeight) {
            speed = speedForHeight(remainingHeight, gravity);
            bounce.coasting = true;
        }
        Vec3 movement = player.getKnownMovement();
        if (!finite(movement) || Math.abs(movement.x) > MOTION_PACKET_LIMIT
                || Math.abs(movement.z) > MOTION_PACKET_LIMIT) {
            forget(player);
            return;
        }
        Vec3 velocity = new Vec3(movement.x, speed, movement.z);
        player.setDeltaMovement(velocity);
        player.hasImpulse = true;
        player.connection.send(new ClientboundSetEntityMotionPacket(player.getId(), velocity));
        bounce.launched = true;
        allowLegitimateAscent(player);
    }

    private static boolean canBounce(ServerPlayer player) {
        return player.isAlive() && !player.isSpectator() && !player.isPassenger() && !player.isSleeping()
                && !player.getAbilities().flying && !player.isFallFlying()
                && !player.isInWater() && !player.isInLava() && !player.onClimbable()
                && Double.isFinite(player.getGravity()) && player.getGravity() > 0.0;
    }

    private static double speedForHeight(double height, double gravity) {
        double lower = 0.0;
        double upper = MAXIMUM_UPWARD_SPEED;
        for (int step = 0; step < SPEED_SEARCH_STEPS; step++) {
            double middle = (lower + upper) * 0.5;
            if (heightFromSpeed(middle, gravity) < height) lower = middle;
            else upper = middle;
        }
        return upper;
    }

    private static double heightFromSpeed(double speed, double gravity) {
        // Sum vanilla's ascending motion, v(next) = (v - gravity) * drag, in constant time.
        double terminalSpeed = gravity * VERTICAL_DRAG / (1.0 - VERTICAL_DRAG);
        double steps = Math.ceil(Math.log(terminalSpeed / (speed + terminalSpeed)) / LOG_VERTICAL_DRAG);
        double dragSum = -Math.expm1(steps * LOG_VERTICAL_DRAG) / (1.0 - VERTICAL_DRAG);
        return Math.max(0.0, (speed + terminalSpeed) * dragSum - terminalSpeed * steps);
    }

    private static void allowLegitimateAscent(ServerPlayer player) {
        // Long, speed-limited launches can exceed vanilla's floating timeout even while moving.
        ((ServerConnectionAccessor) player.connection).aatuStrike$setAboveGroundTickCount(0);
    }

    static void forget(ServerPlayer player) { ACTIVE.remove(player.getUUID()); }

    static void clearAll() { ACTIVE.clear(); }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    private static final class Bounce {
        private final double apex;
        private final long landingTick;
        private Vec3 lastPosition;
        private long lastTick = Long.MIN_VALUE;
        private int stalledTicks;
        private boolean launched;
        private boolean leftGround;
        private boolean hasAscended;
        private boolean coasting;

        private Bounce(double apex, Vec3 position, long now) {
            this.apex = apex;
            this.lastPosition = position;
            this.landingTick = now;
        }
    }
}
