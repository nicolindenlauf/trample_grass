package com.niconator.tramplegrass;

import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue WATCH_DURATION_TICKS;
    public static final ModConfigSpec.DoubleValue PATH_CHANCE_PERCENT;
    public static final ModConfigSpec.IntValue PATH_REGROWTH_SLOWDOWN;
    public static final ModConfigSpec.BooleanValue DEBUG;
    public static final ModConfigSpec SPEC;

    public static int watchDurationTicks = 200;
    public static double pathChancePercent = 25.0;
    public static int pathRegrowthSlowdown = 4;
    public static boolean debug = false;

    static {
        BUILDER.comment("Grass trampling behavior.").push("trampling");
        WATCH_DURATION_TICKS = BUILDER
                .comment(
                        "How long a grass block remains watched after an entity steps on it, in game ticks.",
                        "20 ticks is roughly one second. Later steps refresh this duration."
                )
                .defineInRange("watchDurationTicks", 200, 1, 20 * 60 * 60);
        PATH_CHANCE_PERCENT = BUILDER
                .comment(
                        "Chance that another entity stepping on a watched grass block turns it into a dirt path.",
                        "Use 0 to disable conversion, 100 to always convert on a qualifying step."
                )
                .defineInRange("pathChancePercent", 25.0, 0.0, 100.0);
        BUILDER.pop();

        BUILDER.comment("Natural regrowth for dirt paths.").push("regrowth");
        PATH_REGROWTH_SLOWDOWN = BUILDER
                .comment(
                        "How much slower dirt paths regrow into grass compared to normal random-tick grass spreading.",
                        "The default of 4 means a dirt path is four times less likely to regrow than normal grass is to spread.",
                        "Set this higher for slower regrowth."
                )
                .defineInRange("pathRegrowthSlowdown", 4, 1, 1024);
        BUILDER.pop();

        BUILDER.comment("Diagnostics for troubleshooting grass trampling.").push("debug");
        DEBUG = BUILDER
                .comment("Enable detailed debug logging. Leave this disabled during normal gameplay.")
                .define("debugLogging", false);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    private Config() {
    }

    static void onConfigLoad(ModConfigEvent.Loading event) {
        bake(event.getConfig());
    }

    static void onConfigReload(ModConfigEvent.Reloading event) {
        bake(event.getConfig());
    }

    private static void bake(ModConfig config) {
        if (!TrampleGrass.MODID.equals(config.getModId()) || config.getType() != ModConfig.Type.COMMON) {
            return;
        }

        watchDurationTicks = WATCH_DURATION_TICKS.get();
        pathChancePercent = PATH_CHANCE_PERCENT.get();
        pathRegrowthSlowdown = PATH_REGROWTH_SLOWDOWN.get();
        debug = DEBUG.get();
    }
}
