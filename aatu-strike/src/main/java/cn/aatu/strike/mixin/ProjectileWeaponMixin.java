package cn.aatu.strike.mixin;

import cn.aatu.strike.StrikeEvents;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ProjectileWeaponItem.class)
public abstract class ProjectileWeaponMixin {
    @WrapMethod(method = "shoot")
    private void aatuStrike$volley(ServerLevel level, LivingEntity shooter, InteractionHand hand,
                                  ItemStack weapon, List<ItemStack> ammunition, float velocity,
                                  float inaccuracy, boolean critical, LivingEntity target,
                                  Operation<Void> original) {
        if (shooter instanceof ServerPlayer player) {
            try (var scope = StrikeEvents.beginVolley(player)) {
                original.call(level, shooter, hand, weapon, ammunition, velocity, inaccuracy, critical, target);
            }
        } else {
            original.call(level, shooter, hand, weapon, ammunition, velocity, inaccuracy, critical, target);
        }
    }
}
