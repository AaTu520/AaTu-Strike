package cn.aatu.strike.mixin;

import cn.aatu.strike.StrikeEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class SneakReleaseMixin {
    @Inject(method = "setShiftKeyDown", at = @At("HEAD"))
    private void aatuStrike$release(boolean pressed, CallbackInfo callback) {
        // A release followed by a new press in the same tick must still discard the old energy.
        if (!pressed && (Object) this instanceof ServerPlayer player) {
            StrikeEvents.releasedShift(player);
        }
    }
}
