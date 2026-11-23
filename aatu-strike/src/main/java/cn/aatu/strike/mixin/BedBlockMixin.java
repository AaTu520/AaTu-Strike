package cn.aatu.strike.mixin;

import cn.aatu.strike.StrikeEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BedBlock.class)
public abstract class BedBlockMixin {
    @Inject(method = "fallOn", at = @At("HEAD"), cancellable = true)
    private void aatuStrike$land(Level level, BlockState state, BlockPos position, Entity entity,
                               float distance, CallbackInfo callback) {
        // Capture the full fall before the vanilla bed halves it; only this landing is protected.
        if (entity instanceof ServerPlayer player && StrikeEvents.landedOnBed(player, distance)) {
            callback.cancel();
        }
    }
}
