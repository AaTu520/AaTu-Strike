package cn.aatu.strike;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class StrikeConfig {
    private static final int TICKS_PER_SECOND = 20;
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue ENABLED, CHARGE_ENABLED, HEADSHOTS_ENABLED,
            HEADSHOT_TITLE_ENABLED, DASH_ENABLED, BED_TRAMPOLINE_ENABLED, MELON_ARROWS_ENABLED;
    private static final ModConfigSpec.DoubleValue FULL_CHARGE_SECONDS, MAXIMUM_BONUS_PERCENT,
            HEADSHOT_MULTIPLIER, HEAD_HEIGHT_FRACTION, HEADSHOT_EFFECT_CHANCE,
            DASH_ADDED_SPEED, DASH_SPEED_LIMIT, DASH_MAXIMUM_DISTANCE;
    private static volatile Settings current;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment("逻辑服务端配置，适用于独立服务器与本地单人世界。修改后重启生效。")
                .push("general");
        ENABLED = builder.comment("总开关。关闭后停用本模组的玩法。")
                .define("enabled", true);
        builder.pop().push("charge");
        CHARGE_ENABLED = builder.comment("按住潜行键（默认 Shift）蓄力；消耗后须松开并重新按下才能再次蓄力。松开立即清空。")
                .define("enabled", true);
        FULL_CHARGE_SECONDS = builder.comment("蓄满所需秒数；每秒按 20 游戏刻计算。")
                .defineInRange("fullChargeSeconds", 6.0, 0.05, 3600.0);
        MAXIMUM_BONUS_PERCENT = builder.comment("满蓄力的额外伤害百分比。500 表示增加 500%，总伤害为 6 倍。")
                .defineInRange("maximumBonusPercent", 500.0, 0.0, 500.0);
        builder.pop().push("headshot");
        HEADSHOTS_ENABLED = builder.comment("启用玩家弹射物直击的爆头加成、精准打击提示与随机负面效果。")
                .define("enabled", true);
        HEADSHOT_MULTIPLIER = builder.comment("爆头伤害倍率，与蓄力倍率相乘。")
                .defineInRange("multiplier", 2.0, 1.0, 100.0);
        HEAD_HEIGHT_FRACTION = builder.comment("从目标碰撞箱顶部向下计算的头部高度比例。0.25 表示顶部四分之一。")
                .defineInRange("headHeightFraction", 0.25, 0.01, 1.0);
        HEADSHOT_TITLE_ENABLED = builder.comment("有效爆头后，在射手屏幕中央短暂显示“精准打击”。")
                .define("showTitle", true);
        HEADSHOT_EFFECT_CHANCE = builder.comment("有效爆头施加负面效果的概率，单位：百分比。等概率抽取反胃、失明、缓慢之一，一级，持续 3 秒；0 表示关闭。")
                .defineInRange("effectChancePercent", 30.0, 0.0, 100.0);
        // Preserve the existing section and speed keys so saved server settings keep their values.
        builder.pop().push("flight");
        DASH_ENABLED = builder.comment("潜行键＋交换副手键（默认 Shift＋F）消耗蓄力并向前冲刺，平地与空中均可使用。")
                .define("enabled", true);
        DASH_ADDED_SPEED = builder.comment("冲刺沿触发方向增加的速度，单位：格/秒；推进期间仅补足前向速度，保留已有运动。")
                .defineInRange("maximumAddedSpeed", 8.0, 0.0, 8.0);
        DASH_SPEED_LIMIT = builder.comment("本次冲刺设置的总速度上限，单位：格/秒。已有速度达到上限时不启动冲刺。")
                .defineInRange("maximumBoostedSpeed", 48.0, 1.0, 48.0);
        DASH_MAXIMUM_DISTANCE = builder.comment("满蓄力的推进目标距离，单位：格；按能量比例缩放。达到后停止推进，由原有移动处理惯性。")
                .defineInRange("maximumDistance", 40.0, 0.0, 40.0);
        builder.pop().push("fun");
        BED_TRAMPOLINE_ENABLED = builder.comment("玩家落到床上免疫摔落伤害并自动反弹。反弹目标为本次下落高度的 1.25 倍，最低约 2 格，不设高度上限；潜行沿用抑制反弹的操作。")
                .define("bedTrampoline", true);
        MELON_ARROWS_ENABLED = builder.comment("玩家射出的箭可打碎西瓜，并按原版规则掉落西瓜片；遵循玩家破坏权限。")
                .define("melonArrows", true);
        builder.pop();
        SPEC = builder.build();
    }

    private StrikeConfig() {}

    static void load() {
        current = new Settings(ENABLED.get(), CHARGE_ENABLED.get(),
                Math.max(1, (int) Math.round(FULL_CHARGE_SECONDS.get() * TICKS_PER_SECOND)),
                MAXIMUM_BONUS_PERCENT.get(), HEADSHOTS_ENABLED.get(),
                HEADSHOT_MULTIPLIER.get(), HEAD_HEIGHT_FRACTION.get(), DASH_ENABLED.get(),
                DASH_ADDED_SPEED.get() / TICKS_PER_SECOND, DASH_SPEED_LIMIT.get() / TICKS_PER_SECOND,
                DASH_MAXIMUM_DISTANCE.get(), HEADSHOT_TITLE_ENABLED.get(), HEADSHOT_EFFECT_CHANCE.get() / 100.0,
                BED_TRAMPOLINE_ENABLED.get(), MELON_ARROWS_ENABLED.get());
    }

    static Settings current() { return current; }

    record Settings(boolean enabled, boolean chargeEnabled, int fullChargeTicks,
                    double maximumBonusPercent, boolean headshotsEnabled,
                    double headshotMultiplier, double headHeightFraction, boolean dashEnabled,
                    double dashAddedSpeedPerTick, double dashSpeedLimitPerTick, double maximumDashDistance,
                    boolean headshotTitleEnabled, double headshotEffectChance,
                    boolean bedTrampolineEnabled, boolean melonArrowsEnabled) {
        double chargeMultiplier(double fraction) {
            return 1.0 + Math.clamp(fraction, 0.0, 1.0) * maximumBonusPercent / 100.0;
        }
    }
}
