package cn.aatu.strike;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

final class DashController {
    private static final double MINIMUM_MOTION = 1.0E-6;
    private static final double MINIMUM_PROGRESS = 0.001;
    private static final double DISTANCE_TOLERANCE = 0.02;
    private static final int MAXIMUM_STALLED_TICKS = 10;
    private static final int MAXIMUM_DASH_TICKS = 200;
    private static final int NETWORK_GRACE_TICKS = 10;
    private static final Map<UUID, Dash> ACTIVE = new HashMap<>();

    private DashController() {}

    static boolean tryStart(ServerPlayer player, StrikeConfig.Settings settings) {
        if (!eligible(player, settings) || !player.isShiftKeyDown()) return false;
        if (ACTIVE.containsKey(player.getUUID())) return true;
        double energy = ChargeController.energy(player, settings);
        if (energy <= 0.0) return false;

        Vec3 movement = observedMovement(player, null);
        if (!finite(movement) || movement.length() >= settings.dashSpeedLimitPerTick()) return false;
        boolean horizontal = player.onGround();
        Vec3 direction = horizontal
                ? Vec3.directionFromRotation(0.0F, player.getYRot()) : player.getLookAngle();
        if (!finite(direction) || direction.lengthSqr() <= MINIMUM_MOTION) return false;
        direction = direction.normalize();
        double forwardSpeed = movement.dot(direction) + settings.dashAddedSpeedPerTick();
        double distance = energy * settings.maximumDashDistance();
        if (distance <= DISTANCE_TOLERANCE) return false;

        double expectedSpeed = Math.max(settings.dashAddedSpeedPerTick(), forwardSpeed);
        int duration = Math.min(MAXIMUM_DASH_TICKS,
                (int) Math.ceil(distance / expectedSpeed * 2.0) + NETWORK_GRACE_TICKS);
        Dash dash = new Dash(direction, forwardSpeed, distance, duration,
                player.position(), player.level().getGameTime());
        Vec3 velocity = nextVelocity(dash, movement, settings.dashSpeedLimitPerTick());
        if (velocity == null || velocity.distanceToSqr(movement) <= MINIMUM_MOTION * MINIMUM_MOTION) return false;

        ChargeController.consumeEnergy(player, settings);
        ACTIVE.put(player.getUUID(), dash);
        sendVelocity(player, dash, velocity);
        return true;
    }

    static void tick(ServerPlayer player, StrikeConfig.Settings settings) {
        Dash dash = ACTIVE.get(player.getUUID());
        if (dash == null) return;
        if (!eligible(player, settings)) {
            forget(player);
            return;
        }
        long now = player.level().getGameTime();
        if (dash.lastTick == now) return;
        dash.lastTick = now;
        Vec3 position = player.position();
        Vec3 displacement = position.subtract(dash.lastPosition);
        dash.lastPosition = position;
        if (!finite(displacement) || displacement.lengthSqr() > settings.maximumDashDistance() * settings.maximumDashDistance()) {
            forget(player);
            return;
        }

        double progress = Math.max(0.0, displacement.dot(dash.direction));
        dash.remainingDistance -= progress;
        dash.remainingTicks--;
        dash.stalledTicks = progress < MINIMUM_PROGRESS ? dash.stalledTicks + 1 : 0;
        if (dash.remainingDistance <= DISTANCE_TOLERANCE || dash.remainingTicks <= 0
                || dash.stalledTicks >= MAXIMUM_STALLED_TICKS
                || player.horizontalCollision && progress < MINIMUM_PROGRESS) {
            forget(player);
            return;
        }

        // Reusing an unacknowledged motion packet would overwrite newer flight physics.
        if (displacement.lengthSqr() <= MINIMUM_MOTION * MINIMUM_MOTION) return;
        Vec3 movement = observedMovement(player, dash);
        Vec3 velocity = nextVelocity(dash, movement, settings.dashSpeedLimitPerTick());
        if (velocity == null) {
            forget(player);
            return;
        }
        if (velocity.distanceToSqr(movement) <= MINIMUM_MOTION * MINIMUM_MOTION) return;
        sendVelocity(player, dash, velocity);
    }

    private static Vec3 nextVelocity(Dash dash, Vec3 movement, double limit) {
        if (!finite(movement) || movement.length() > limit) return null;
        double forwardSpeed = movement.dot(dash.direction);
        double missingSpeed = Math.max(0.0, dash.forwardSpeed - forwardSpeed);
        if (missingSpeed <= MINIMUM_MOTION) return movement;

        // Limit only the added impulse. Scaling or replacing the whole vector brakes gliding,
        // especially when the remaining distance is smaller than one tick of normal movement.
        double availableSpeed = Math.max(0.0, Math.sqrt(Math.max(0.0,
                forwardSpeed * forwardSpeed + limit * limit - movement.lengthSqr())) - forwardSpeed);
        double addedSpeed = Math.min(missingSpeed, availableSpeed);
        Vec3 velocity = movement.add(dash.direction.scale(addedSpeed));
        return finite(velocity) ? velocity : null;
    }

    private static Vec3 observedMovement(ServerPlayer player, Dash dash) {
        Vec3 accepted = player.getKnownMovement();
        Vec3 pending = player.getDeltaMovement();
        if (!finite(accepted)) return accepted;
        if (!finite(pending)) return pending;
        // Our last packet is not a fresh external impulse: prefer the accepted flight movement.
        if (dash != null && dash.lastSentVelocity != null
                && pending.distanceToSqr(dash.lastSentVelocity) <= MINIMUM_MOTION * MINIMUM_MOTION) {
            return accepted;
        }
        // A pending external impulse may not yet have appeared in accepted position packets.
        return pending.lengthSqr() > accepted.lengthSqr() ? pending : accepted;
    }

    private static void sendVelocity(ServerPlayer player, Dash dash, Vec3 velocity) {
        player.setDeltaMovement(velocity);
        player.hasImpulse = true;
        player.connection.send(new ClientboundSetEntityMotionPacket(player.getId(), velocity));
        dash.lastSentVelocity = velocity;
    }

    private static boolean eligible(ServerPlayer player, StrikeConfig.Settings settings) {
        return settings != null && settings.enabled() && settings.chargeEnabled() && settings.dashEnabled()
                && settings.dashAddedSpeedPerTick() > 0.0 && settings.maximumDashDistance() > 0.0
                && player.isAlive() && !player.isSpectator() && !player.isSleeping()
                && !player.isPassenger();
    }

    static void forget(ServerPlayer player) { ACTIVE.remove(player.getUUID()); }

    static void clearAll() { ACTIVE.clear(); }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    private static final class Dash {
        private final Vec3 direction;
        private final double forwardSpeed;
        private double remainingDistance;
        private int remainingTicks;
        private int stalledTicks;
        private Vec3 lastPosition;
        private Vec3 lastSentVelocity;
        private long lastTick;

        private Dash(Vec3 direction, double forwardSpeed, double distance,
                     int ticks, Vec3 position, long now) {
            this.direction = direction;
            this.forwardSpeed = forwardSpeed;
            this.remainingDistance = distance;
            this.remainingTicks = ticks;
            this.lastPosition = position;
            this.lastTick = now;
        }
    }
}
