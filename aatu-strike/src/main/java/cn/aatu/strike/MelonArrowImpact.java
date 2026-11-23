package cn.aatu.strike;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

final class MelonArrowImpact {
    private MelonArrowImpact() {}

    static boolean handle(ProjectileImpactEvent event, StrikeConfig.Settings settings) {
        if (!settings.melonArrowsEnabled() || !(event.getProjectile() instanceof AbstractArrow arrow)
                || arrow instanceof ThrownTrident || !(arrow.getOwner() instanceof ServerPlayer shooter)
                || !(arrow.level() instanceof ServerLevel level)
                || !(event.getRayTraceResult() instanceof BlockHitResult hit)) return false;
        BlockPos position = hit.getBlockPos();
        if (shooter.isSpectator() || !shooter.mayBuild() || shooter.level() != level
                || !level.hasChunkAt(position) || !level.mayInteract(shooter, position)) return false;
        BlockState state = level.getBlockState(position);
        if (!state.is(Blocks.MELON)) return false;

        // Projectile block destruction must offer the same cancellation point as a player break.
        BlockEvent.BreakEvent breakEvent = NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, position, state, shooter));
        if (breakEvent.isCanceled() || level.getBlockState(position) != state) return false;
        if (!level.destroyBlock(position, true, shooter)) return false;
        // The block is gone; let the arrow continue instead of lodging in its former collision box.
        event.setCanceled(true);
        return true;
    }
}
