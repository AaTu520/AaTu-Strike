package cn.aatu.strike;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.entity.projectile.ThrownExperienceBottle;
import net.minecraft.world.entity.projectile.ThrownPotion;

final class ProjectileCharge {
    private static final String DATA_KEY = StrikeMod.MOD_ID + ":charge";
    private static final String OWNER_KEY = "Owner";
    private static final String MULTIPLIER_KEY = "Multiplier";

    private ProjectileCharge() {}

    static boolean isAttackProjectile(Projectile projectile) {
        // These use projectiles for travel, utility or lingering effects instead of a direct attack.
        return !(projectile instanceof FishingHook || projectile instanceof ThrownEnderpearl
                || projectile instanceof ThrownExperienceBottle || projectile instanceof ThrownPotion);
    }

    static boolean alreadyCaptured(Projectile projectile) {
        return projectile.getPersistentData().contains(DATA_KEY);
    }

    static void capture(Projectile projectile, ServerPlayer shooter, double multiplier) {
        if (multiplier <= 1.0) return;
        CompoundTag data = new CompoundTag();
        data.putUUID(OWNER_KEY, shooter.getUUID());
        data.putDouble(MULTIPLIER_KEY, multiplier);
        projectile.getPersistentData().put(DATA_KEY, data);
    }

    static double multiplier(Projectile projectile, ServerPlayer shooter, StrikeConfig.Settings settings) {
        if (!settings.chargeEnabled()) return 1.0;
        CompoundTag data = projectile.getPersistentData().getCompound(DATA_KEY);
        if (!data.hasUUID(OWNER_KEY) || !data.getUUID(OWNER_KEY).equals(shooter.getUUID())) return 1.0;
        double stored = data.getDouble(MULTIPLIER_KEY);
        // A deflected projectile must not transfer another player's stored energy to its new owner.
        if (projectile.getOwner() != shooter || !Double.isFinite(stored)) return 1.0;
        return Math.clamp(stored, 1.0, settings.chargeMultiplier(1.0));
    }
}
