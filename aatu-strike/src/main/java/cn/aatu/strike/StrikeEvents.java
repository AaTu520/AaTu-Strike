package cn.aatu.strike;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingSwapItemsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class StrikeEvents {
    private static final long ERROR_LOG_INTERVAL_NANOS = 60_000_000_000L;
    private static final Map<UUID, Double> MELEE_ATTACKS = new HashMap<>();
    private static final Map<UUID, Volley> VOLLEYS = new HashMap<>();
    private static final Map<Projectile, Impact> IMPACTS = new IdentityHashMap<>();
    private static long lastErrorNanos;

    private StrikeEvents() {}

    static void register(IEventBus bus) {
        bus.addListener(StrikeEvents::playerTick);
        bus.addListener(EventPriority.LOWEST, StrikeEvents::projectileSpawned);
        bus.addListener(EventPriority.LOWEST, StrikeEvents::projectileImpact);
        bus.addListener(EventPriority.HIGH, StrikeEvents::incomingDamage);
        bus.addListener(EventPriority.LOWEST, StrikeEvents::damageApplied);
        bus.addListener(EventPriority.LOWEST, StrikeEvents::swapHands);
        bus.addListener(EventPriority.LOWEST, StrikeEvents::died);
        bus.addListener(StrikeEvents::loggedOut);
        bus.addListener(StrikeEvents::changedDimension);
        bus.addListener(StrikeEvents::cloned);
        bus.addListener(StrikeEvents::afterTick);
        bus.addListener(StrikeEvents::stopped);
    }

    public static AttackScope beginMeleeAttack(ServerPlayer player) {
        double multiplier = consumeCharge(player);
        Double previous = MELEE_ATTACKS.put(player.getUUID(), multiplier);
        return () -> restore(MELEE_ATTACKS, player.getUUID(), previous);
    }

    public static AttackScope beginVolley(ServerPlayer player) {
        Volley previous = VOLLEYS.put(player.getUUID(), new Volley());
        return () -> restore(VOLLEYS, player.getUUID(), previous);
    }

    public static void releasedShift(ServerPlayer player) {
        try {
            ChargeController.clear(player);
        } catch (RuntimeException exception) {
            reportFailure(exception);
        }
    }

    public static boolean landedOnBed(ServerPlayer player, float distance) {
        try {
            return BedTrampoline.landed(player, distance, StrikeConfig.current());
        } catch (RuntimeException exception) {
            BedTrampoline.forget(player);
            reportFailure(exception);
            return false;
        }
    }

    private static <T> void restore(Map<UUID, T> scopes, UUID player, T previous) {
        if (previous == null) scopes.remove(player);
        else scopes.put(player, previous);
    }

    private static double consumeCharge(ServerPlayer player) {
        try {
            return ChargeController.consume(player, StrikeConfig.current());
        } catch (RuntimeException exception) {
            ChargeController.forget(player);
            reportFailure(exception);
            return 1.0;
        }
    }

    private static void playerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        try {
            ChargeController.tick(player, StrikeConfig.current());
            DashController.tick(player, StrikeConfig.current());
            BedTrampoline.tick(player, StrikeConfig.current());
        } catch (RuntimeException exception) {
            ChargeController.forget(player);
            DashController.forget(player);
            BedTrampoline.forget(player);
            reportFailure(exception);
        }
    }

    private static void projectileSpawned(EntityJoinLevelEvent event) {
        StrikeConfig.Settings settings = StrikeConfig.current();
        if (event.getLevel().isClientSide() || event.loadedFromDisk() || !enabled(settings)
                || !settings.chargeEnabled() || !(event.getEntity() instanceof Projectile projectile)
                || projectile.tickCount != 0 || !ProjectileCharge.isAttackProjectile(projectile)) return;
        try {
            if (ProjectileCharge.alreadyCaptured(projectile)
                    || !(projectile.getOwner() instanceof ServerPlayer shooter)) return;
            Volley volley = VOLLEYS.get(shooter.getUUID());
            double multiplier;
            if (volley != null) {
                // All arrows from one multishot volley share the energy consumed by its first spawn.
                if (!volley.captured) {
                    volley.multiplier = consumeCharge(shooter);
                    volley.captured = true;
                }
                multiplier = volley.multiplier;
            } else {
                multiplier = consumeCharge(shooter);
            }
            ProjectileCharge.capture(projectile, shooter, multiplier);
        } catch (RuntimeException exception) {
            reportFailure(exception);
        }
    }

    private static void projectileImpact(ProjectileImpactEvent event) {
        Projectile projectile = event.getProjectile();
        if (projectile.level().isClientSide()) return;
        IMPACTS.remove(projectile);
        StrikeConfig.Settings settings = StrikeConfig.current();
        if (!enabled(settings)) return;
        try {
            if (MelonArrowImpact.handle(event, settings) || !settings.headshotsEnabled()) return;
            if (!ProjectileCharge.isAttackProjectile(projectile)
                    || !(projectile.getOwner() instanceof ServerPlayer)
                    || !(event.getRayTraceResult() instanceof EntityHitResult hit)
                    || !(hit.getEntity() instanceof LivingEntity target)) return;
            boolean headshot = HeadshotGeometry.hitsHead(projectile, target, hit, settings.headHeightFraction());
            IMPACTS.put(projectile, new Impact(target.getUUID(), projectile.level().getGameTime(), headshot));
        } catch (RuntimeException exception) {
            reportFailure(exception);
        }
    }

    private static void incomingDamage(LivingIncomingDamageEvent event) {
        StrikeConfig.Settings settings = StrikeConfig.current();
        if (event.getEntity().level().isClientSide() || !enabled(settings)) return;
        DamageSource source = event.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) return;
        try {
            double multiplier = 1.0;
            Projectile headshotProjectile = null;
            if (source.getDirectEntity() instanceof Projectile projectile) {
                // Discard canceled or nested damage records before this projectile starts a new hit.
                HeadshotFeedback.discardPending(projectile);
                multiplier = ProjectileCharge.multiplier(projectile, player, settings);
                Impact impact = IMPACTS.remove(projectile);
                if (settings.headshotsEnabled() && source.is(DamageTypeTags.IS_PROJECTILE)
                        && !source.is(DamageTypeTags.IS_EXPLOSION)
                        && impact != null && impact.headshot()
                        && impact.target().equals(event.getEntity().getUUID())
                        && impact.gameTime() == projectile.level().getGameTime()) {
                    multiplier *= settings.headshotMultiplier();
                    headshotProjectile = projectile;
                }
            } else if (settings.chargeEnabled() && source.getDirectEntity() == player
                    && source.is(DamageTypes.PLAYER_ATTACK)) {
                Double attack = MELEE_ATTACKS.get(player.getUUID());
                // Mods that emit a standard direct player attack without Player.attack still consume once.
                multiplier = attack != null ? attack : consumeCharge(player);
            }
            float original = event.getAmount();
            if (original <= 0.0F || multiplier <= 1.0 && headshotProjectile == null) return;
            double scaled = original * multiplier;
            if (!Double.isFinite(scaled) || scaled > Float.MAX_VALUE) {
                throw new IllegalArgumentException("Attack multiplier would produce invalid damage");
            }
            if (multiplier > 1.0) event.setAmount((float) scaled);
            if (headshotProjectile != null && (settings.headshotTitleEnabled() || settings.headshotEffectChance() > 0.0)) {
                HeadshotFeedback.remember(headshotProjectile, source, event.getEntity(), player);
            }
        } catch (RuntimeException exception) {
            // Leave this event's existing damage untouched when our calculation cannot be used.
            reportFailure(exception);
        }
    }

    private static void damageApplied(LivingDamageEvent.Post event) {
        if (event.getEntity().level().isClientSide()) return;
        try {
            HeadshotFeedback.damageApplied(event, StrikeConfig.current());
        } catch (RuntimeException exception) {
            // Feedback must not interrupt an attack whose damage has already been applied.
            reportFailure(exception);
        }
    }

    private static boolean enabled(StrikeConfig.Settings settings) {
        return settings != null && settings.enabled();
    }

    private static void swapHands(LivingSwapItemsEvent.Hands event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        try {
            if (DashController.tryStart(player, StrikeConfig.current())) event.setCanceled(true);
        } catch (RuntimeException exception) {
            DashController.forget(player);
            reportFailure(exception);
        }
    }

    private static void died(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) resetPlayer(player);
    }

    private static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) resetPlayer(player);
    }

    private static void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) resetPlayer(player);
    }

    private static void cloned(PlayerEvent.Clone event) {
        if (event.getEntity() instanceof ServerPlayer player) resetPlayer(player);
    }

    private static void resetPlayer(ServerPlayer player) {
        MELEE_ATTACKS.remove(player.getUUID());
        VOLLEYS.remove(player.getUUID());
        DashController.forget(player);
        BedTrampoline.forget(player);
        try {
            ChargeController.clear(player);
        } catch (RuntimeException exception) {
            reportFailure(exception);
        }
    }

    private static void afterTick(ServerTickEvent.Post event) {
        IMPACTS.clear();
        HeadshotFeedback.clearTickState();
    }

    private static void stopped(ServerStoppedEvent event) {
        ChargeController.clearAll();
        DashController.clearAll();
        BedTrampoline.clearAll();
        MELEE_ATTACKS.clear();
        VOLLEYS.clear();
        IMPACTS.clear();
        HeadshotFeedback.clearTickState();
        lastErrorNanos = 0;
    }

    private static void reportFailure(RuntimeException exception) {
        long now = System.nanoTime();
        if (lastErrorNanos == 0 || now - lastErrorNanos >= ERROR_LOG_INTERVAL_NANOS) {
            lastErrorNanos = now;
            StrikeMod.LOGGER.error("AaTu Strike skipped a failed operation; repeated errors are limited to one per minute", exception);
        }
    }

    public interface AttackScope extends AutoCloseable {
        @Override void close();
    }

    private record Impact(UUID target, long gameTime, boolean headshot) {}

    private static final class Volley {
        private boolean captured;
        private double multiplier = 1.0;
    }
}
