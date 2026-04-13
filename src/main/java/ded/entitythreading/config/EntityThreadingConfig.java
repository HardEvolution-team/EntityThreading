package ded.entitythreading.config;

import ded.entitythreading.Reference;
import net.minecraftforge.common.config.Config;

@Config(modid = Reference.MOD_ID, name = Reference.MOD_NAME)
public final class EntityThreadingConfig {

    @Config.Comment("Master switch to enable/disable parallel entity ticking.")
    public static boolean enabled = true;

    @Config.Comment({
            "Thread count mode:",
            "  auto   = CPU cores / 2 (minimum 2, maximum 4)",
            "  manual = Use the exact value from 'manualThreadCount'",
            "  max    = Use all available CPU cores minus 1"
    })
    public static String threadMode = "auto";

    @Config.Comment("Number of worker threads when threadMode = manual.")
    @Config.RangeInt(min = 1, max = 16)
    public static int manualThreadCount = 3;

    @Config.Comment("Entity classes to exclude from parallel ticking. Use full class names.")
    public static String[] blacklistedEntities = {
            "net.minecraft.entity.item.EntityItem",
            "net.minecraft.entity.item.EntityXPOrb"
    };

    @Config.Comment("Enable debug logging.")
    public static boolean debugLogging = false;

    @Config.Comment("Enable asynchronous pathfinding. EXPERIMENTAL.")
    public static boolean asyncPathfinding = false;

    @Config.Comment("Minimum number of entities to enable parallel ticking.")
    @Config.RangeInt(min = 10, max = 1000)
    public static int minEntitiesForThreading = 100;

    @Config.Comment("Minimum batch size per worker thread.")
    @Config.RangeInt(min = 10, max = 500)
    public static int minBatchSize = 50;

    // ─── Entity Activation Range ────────────────────────────────────────

    @Config.Comment("Enable distance-based tick throttling (huge performance boost).")
    public static boolean entityActivationRange = true;

    // Monsters
    @Config.Comment("Monsters: full tick rate within this range (blocks).")
    @Config.RangeInt(min = 8, max = 256)
    public static int activationRangeMonstersTier1 = 32;

    @Config.Comment("Monsters: tick every 2nd tick within this range (blocks).")
    @Config.RangeInt(min = 16, max = 256)
    public static int activationRangeMonstersTier2 = 64;

    @Config.Comment("Monsters: tick every 4th tick within this range (blocks). Beyond = every 8th tick.")
    @Config.RangeInt(min = 32, max = 512)
    public static int activationRangeMonstersTier3 = 128;

    // Animals
    @Config.Comment("Animals: full tick rate within this range (blocks).")
    @Config.RangeInt(min = 8, max = 256)
    public static int activationRangeAnimalsTier1 = 32;

    @Config.Comment("Animals: tick every 2nd tick within this range (blocks).")
    @Config.RangeInt(min = 16, max = 256)
    public static int activationRangeAnimalsTier2 = 64;

    @Config.Comment("Animals: tick every 4th tick within this range (blocks). Beyond = every 8th tick.")
    @Config.RangeInt(min = 32, max = 512)
    public static int activationRangeAnimalsTier3 = 128;

    // Misc
    @Config.Comment("Misc entities: full tick rate within this range (blocks).")
    @Config.RangeInt(min = 8, max = 256)
    public static int activationRangeMiscTier1 = 32;

    @Config.Comment("Misc entities: tick every 2nd tick within this range (blocks).")
    @Config.RangeInt(min = 16, max = 256)
    public static int activationRangeMiscTier2 = 64;

    @Config.Comment("Misc entities: tick every 4th tick within this range (blocks). Beyond = every 8th tick.")
    @Config.RangeInt(min = 32, max = 512)
    public static int activationRangeMiscTier3 = 128;

    /**
     * Returns the effective thread count based on the configured mode.
     */
    public static int getEffectiveThreadCount() {
        int cores = Runtime.getRuntime().availableProcessors();
        return switch (threadMode.toLowerCase()) {
            case "max" -> Math.max(2, cores - 1);
            case "manual" -> Math.clamp(manualThreadCount, 1, Math.max(1, cores));
            default -> Math.clamp(cores / 2, 2, 4);
        };
    }

    private EntityThreadingConfig() {}
}
