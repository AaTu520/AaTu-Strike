package cn.aatu.strike;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(StrikeMod.MOD_ID)
public final class StrikeMod {
    public static final String MOD_ID = "aatu_strike";
    public static final Logger LOGGER = LogUtils.getLogger();

    public StrikeMod(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, StrikeConfig.SPEC, "aatu-strike.toml");
        modBus.addListener(this::loadConfig);
        StrikeEvents.register(NeoForge.EVENT_BUS);
    }

    private void loadConfig(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == StrikeConfig.SPEC) StrikeConfig.load();
    }
}
