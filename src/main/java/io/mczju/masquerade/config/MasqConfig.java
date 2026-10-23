package io.mczju.masquerade.config;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * {@code config.yml} 的解析结果（纯数据）。
 *
 * <p>所有数值都在**加载期**校验并夹紧：越界只 WARN + 夹紧，**不抛异常**
 * （一个写错的键不应该让活动插件起不来）。</p>
 */
public record MasqConfig(
        Items items,
        Effect effect,
        Display display,
        Commands commands,
        Messages messages,
        boolean debug
) {

    /** 物品相关。 */
    public record Items(
            String potionId,
            Color potionColor,
            String potionName,
            List<String> potionLore,
            boolean potionInvisibility,
            int sampleCooldownSeconds,
            String masqueradeName,
            String masqueradeMobName,
            List<String> masqueradeLore,
            List<String> mobLore,
            int cooldownSeconds,
            String cooldownGroup,
            Set<Material> blockBlacklist,
            Set<Material> invisibleIconBlocks
    ) {
    }

    /** 玩家效果。 */
    public record Effect(
            int durationSeconds,
            double scale,
            boolean invisibility,
            boolean endWhenInvisibilityLost,
            boolean endOnDeath,
            boolean snapOnSneak
    ) {
    }

    /** 展示实体。 */
    public record Display(
            double scale,
            double translationX,
            double translationY,
            double translationZ,
            double viewRange,
            boolean hideFromSelf,
            boolean syncRotation,
            int teleportDuration
    ) {
    }

    /** 指令参数。 */
    public record Commands(int itemCountMax) {
    }

    private static final List<String> DEFAULT_LORE = List.of(
            "<gray>饮用后，你将变为 %s 的模样</gray>",
            "<gray>按 <gold>下蹲 + 右键</gold> 退出易容</gray>",
            "<dark_gray>药效持续三分钟</dark_gray>"
    );

    private static final List<String> DEFAULT_MOB_LORE = List.of(
            "<gray>饮用后，你将变为 %s 的模样</gray>",
            "<gray>按 下蹲 + 右键 退出易容</gray>",
            "<dark_gray>药效持续三分钟</dark_gray>"
    );

    public static @NotNull MasqConfig load(@NotNull FileConfiguration cfg, @NotNull Logger log) {
        String potionId = str(cfg, "items.morphpotion.id", "masquerade_morphpotion");
        Color potionColor = color(cfg.getString("items.morphpotion.color"), Color.fromRGB(0x99, 0xBB, 0x88), log);
        // 空 = 沿用 items/morphpotion.yml 的 customName / lore；非空 = config.yml 覆盖
        String potionName = str(cfg, "items.morphpotion.name", "");
        List<String> potionLore = cfg.isSet("items.morphpotion.lore")
                ? List.copyOf(cfg.getStringList("items.morphpotion.lore"))
                : List.of();
        boolean potionInvisibility = cfg.getBoolean("items.morphpotion.show-invisibility-effect", true);
        int sampleCooldownSeconds = clampInt(
                cfg.getInt("items.morphpotion.sample-cooldown-seconds", 1), 0, 60,
                "items.morphpotion.sample-cooldown-seconds", log);
        String name = str(cfg, "items.masquerade.name", "<light_purple>易容术</light_purple>");
        String mobName = str(cfg, "items.masquerade.mob-name", "<light_purple>易容术</light_purple>");
        List<String> lore = cfg.isSet("items.masquerade.lore")
                ? List.copyOf(cfg.getStringList("items.masquerade.lore"))
                : DEFAULT_LORE;
        List<String> mobLore = cfg.isSet("items.masquerade.mob-lore")
                ? List.copyOf(cfg.getStringList("items.masquerade.mob-lore"))
                : DEFAULT_MOB_LORE;
        int cooldownSeconds = clampInt(
                cfg.getInt("items.masquerade.cooldown-seconds", 1), 0, 3600,
                "items.masquerade.cooldown-seconds", log);
        String cooldownGroup = str(cfg, "items.masquerade.cooldown-group", "masquerade:masquerade");
        Set<Material> blacklist = materialSet(cfg.getStringList("items.block-blacklist"),
                List.of(), "items.block-blacklist", log);
        // 图标不可见的方块（物品栏里看起来像空气）⇒ 改用药水外观（用户 2026-10-22 要求）
        Set<Material> invisibleIcons = materialSet(cfg.getStringList("items.invisible-icon-blocks"),
                List.of("BARRIER", "LIGHT", "STRUCTURE_VOID"), "items.invisible-icon-blocks", log);

        Items items = new Items(potionId, potionColor, potionName, potionLore, potionInvisibility,
                sampleCooldownSeconds, name, mobName, lore, mobLore, cooldownSeconds, cooldownGroup,
                blacklist, invisibleIcons);

        Effect effect = new Effect(
                clampInt(cfg.getInt("effect.duration-seconds", 180), 1, 3600, "effect.duration-seconds", log),
                clampDouble(cfg.getDouble("effect.scale", 0.5), 0.1, 1.0, "effect.scale", log),
                cfg.getBoolean("effect.invisibility", true),
                cfg.getBoolean("effect.end-when-invisibility-lost", true),
                cfg.getBoolean("effect.end-on-death", true),
                cfg.getBoolean("effect.snap-on-sneak", true)
        );

        List<Double> translation = cfg.getDoubleList("display.translation");
        double tx = translation.size() > 0 ? translation.get(0) : 0.0;
        double ty = translation.size() > 1 ? translation.get(1) : 0.0;
        double tz = translation.size() > 2 ? translation.get(2) : 0.0;
        Display display = new Display(
                clampDouble(cfg.getDouble("display.scale", 1.0), 0.25, 2.0, "display.scale", log),
                tx, ty, tz,
                clampDouble(cfg.getDouble("display.view-range", 1.0), 0.1, 2.0, "display.view-range", log),
                cfg.getBoolean("display.hide-from-self", false),
                cfg.getBoolean("display.sync-rotation", true),
                clampInt(cfg.getInt("display.teleport-duration", 2), 0, 20, "display.teleport-duration", log)
        );

        Commands commands = new Commands(
                clampInt(cfg.getInt("commands.item-count-max", 2304), 1, 2304, "commands.item-count-max", log));

        Messages messages = Messages.load(cfg.getConfigurationSection("messages"));
        boolean debug = cfg.getBoolean("debug", false);

        return new MasqConfig(items, effect, display, commands, messages, debug);
    }

    private static String str(FileConfiguration cfg, String path, String def) {
        String value = cfg.getString(path);
        return (value == null || value.isBlank()) ? def : value;
    }

    private static int clampInt(int value, int min, int max, String path, Logger log) {
        if (value < min || value > max) {
            log.warning(path + " = " + value + " 超出范围 [" + min + ", " + max + "]，已夹紧。");
            return (int) Math.clamp(value, min, max);
        }
        return value;
    }

    private static double clampDouble(double value, double min, double max, String path, Logger log) {
        if (Double.isNaN(value) || value < min || value > max) {
            log.warning(path + " = " + value + " 超出范围 [" + min + ", " + max + "]，已夹紧。");
            return Math.clamp(value, min, max);
        }
        return value;
    }

    private static Color color(String raw, Color def, Logger log) {
        if (raw == null || raw.isBlank()) {
            return def;
        }
        String hex = raw.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        if (!hex.matches("[0-9a-fA-F]{6}")) {
            log.warning("items.morphpotion.color = " + raw + " 不是 #RRGGBB 格式，使用默认颜色。");
            return def;
        }
        try {
            return Color.fromRGB(Integer.parseInt(hex, 16));
        } catch (NumberFormatException ex) {
            log.warning("items.morphpotion.color = " + raw + " 解析失败，使用默认颜色。");
            return def;
        }
    }

    private static Set<Material> materialSet(List<String> rawList, List<String> defaults,
                                             String path, Logger log) {
        List<String> source = (rawList == null || rawList.isEmpty()) ? defaults : rawList;
        Set<Material> set = new LinkedHashSet<>();
        for (String raw : source) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            Material material = Material.matchMaterial(raw.trim());
            if (material == null) {
                log.warning(path + " 里的 " + raw + " 不是有效 Material，已忽略。");
                continue;
            }
            set.add(material);
        }
        return Set.copyOf(set);
    }
}
