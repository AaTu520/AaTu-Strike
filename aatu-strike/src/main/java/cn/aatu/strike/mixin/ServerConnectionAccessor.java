package cn.aatu.strike.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerConnectionAccessor {
    @Accessor("aboveGroundTickCount")
    void aatuStrike$setAboveGroundTickCount(int ticks);
}
