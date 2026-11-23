package cn.aatu.strike.mixin;

import cn.aatu.strike.StrikeEvents;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Player.class)
public abstract class PlayerAttackMixin {
    @WrapMethod(method = "attack(Lnet/minecraft/world/entity/Entity;)V")
    private void aatuStrike$attack(Entity target, Operation<Void> original) {
        if ((Object) this instanceof ServerPlayer player) {
            // Keep the same multiplier for the main hit and its sweep; always close the scope.
            try (var scope = StrikeEvents.beginMeleeAttack(player)) {
                original.call(target);
            }
        } else {
            original.call(target);
        }
    }
}
