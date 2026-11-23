package cn.aatu.strike;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

final class HeadshotFeedback {
    private static final int TICKS_PER_SECOND = 20;
    private static final int EFFECT_DURATION_TICKS = 3 * TICKS_PER_SECOND;
    private static final int EFFECT_AMPLIFIER = 0;
    private static final int TITLE_STAY_TICKS = 15;
    private static final int TITLE_FADE_OUT_TICKS = 5;
    private static final Component TITLE = Component.literal("精准打击");
    private static final List<Holder<MobEffect>> EFFECTS = List.of(
            MobEffects.CONFUSION, MobEffects.BLINDNESS, MobEffects.MOVEMENT_SLOWDOWN);
    private static final Map<Projectile, PendingHit> PENDING = new IdentityHashMap<>();
    private static final Set<UUID> TITLED_THIS_TICK = new HashSet<>();

    private HeadshotFeedback() {}

    static void discardPending(Projectile projectile) {
        PENDING.remove(projectile);
    }

    static void remember(Projectile projectile, DamageSource source, LivingEntity target, ServerPlayer shooter) {
        PENDING.put(projectile, new PendingHit(source, target, shooter, target.level().getGameTime()));
    }

    static void damageApplied(LivingDamageEvent.Post event, StrikeConfig.Settings settings) {
        DamageSource source = event.getSource();
        if (!(source.getDirectEntity() instanceof Projectile projectile)) return;
        PendingHit hit = PENDING.get(projectile);
        if (hit == null || hit.source() != source || hit.target() != event.getEntity()) return;
        PENDING.remove(projectile);
        if (settings == null || !settings.enabled() || !settings.headshotsEnabled()
                || hit.gameTime() != event.getEntity().level().getGameTime() || !causedDamage(event)) return;

        if (settings.headshotTitleEnabled() && TITLED_THIS_TICK.add(hit.shooter().getUUID())) {
            showTitle(hit.shooter());
        }
        applyRandomEffect(hit.target(), hit.shooter(), settings.headshotEffectChance());
    }

    private static boolean causedDamage(LivingDamageEvent.Post event) {
        float damage = event.getNewDamage();
        float absorbed = event.getReduction(DamageContainer.Reduction.ABSORPTION);
        // Absorption hearts still count as a hit; canceled and fully blocked attacks do not.
        return Float.isFinite(damage) && Float.isFinite(absorbed) && (damage > 0.0F || absorbed > 0.0F);
    }

    private static void showTitle(ServerPlayer shooter) {
        shooter.connection.send(new ClientboundSetTitlesAnimationPacket(0, TITLE_STAY_TICKS, TITLE_FADE_OUT_TICKS));
        // Another title may have left a subtitle active; this notification contains only one line.
        shooter.connection.send(new ClientboundSetSubtitleTextPacket(Component.empty()));
        shooter.connection.send(new ClientboundSetTitleTextPacket(TITLE));
    }

    private static void applyRandomEffect(LivingEntity target, ServerPlayer shooter, double chance) {
        if (!target.isAlive() || chance <= 0.0 || target.getRandom().nextDouble() >= chance) return;
        Holder<MobEffect> effect = EFFECTS.get(target.getRandom().nextInt(EFFECTS.size()));
        target.addEffect(new MobEffectInstance(effect, EFFECT_DURATION_TICKS, EFFECT_AMPLIFIER), shooter);
    }

    static void clearTickState() {
        PENDING.clear();
        TITLED_THIS_TICK.clear();
    }

    private record PendingHit(DamageSource source, LivingEntity target, ServerPlayer shooter, long gameTime) {}
}
