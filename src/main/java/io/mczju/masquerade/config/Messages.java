package io.mczju.masquerade.config;

import io.mczju.masquerade.util.TextFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 玩家可见消息。
 *
 * <p>全部来自 {@code config.yml} 的 {@code messages} 段；**缺键回落到内置默认值**
 * （现场文件「只增不改」，缺键不能让插件失效 —— {@code AGENTS.md} 铁律 13）。</p>
 *
 * <p>★ 消息是**纯文本**（不走 MiniMessage）：服主改坏格式也不会把整个消息变成空白；
 * 占位符只有 {@code %s} / {@code %d}。</p>
 */
public final class Messages {

    /** 内置默认值：键名与 {@code config.yml} 完全一致。 */
    private static final Map<String, String> DEFAULTS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("prefix", "[易容术] ");
        m.put("no-permission", "你没有权限使用这个指令。");
        m.put("players-only", "这个指令只能由玩家执行。");
        m.put("usage", "用法：/masq item <方块名|morphpotion> [数量] / /masq clear [选择器] / /masq end");
        m.put("unknown-item", "未知的物品 id：%s");
        m.put("not-a-block", "%s 不是可放置方块。");
        m.put("count-invalid", "数量必须是 1~%d 的整数。");
        m.put("item-given", "已给予 %s ×%d");
        m.put("item-given-dropped", "已给予 %s ×%d（背包放不下，%d 件掉在脚下）");
        m.put("clear-done", "已清除 %d 名玩家的易容效果。");
        m.put("clear-none", "没有玩家正在易容。");
        m.put("selector-rejected", "不接受 @e 选择器，请用 @a / @p / 玩家名。");
        m.put("selector-invalid", "选择器无效：%s");
        m.put("morphpotion-not-drinkable", "这是易容术的原料，请潜行 + 右键方块取样后再饮用。");
        m.put("sample-success", "已取样：%s（消耗 1 瓶易容药水）");
        m.put("sample-dropped", "已取样：%s（背包放不下，掉在脚下）");
        m.put("sample-cooldown", "取样冷却中，还需 %d 秒。");
        m.put("sample-invalid", "这个方块无法取样。");
        m.put("effect-applied", "你变成了 %s 的模样，效果持续 %d 秒。");
        m.put("effect-ended", "易容效果已结束。");
        m.put("effect-block-invalid", "这个易容术的方块数据已失效，无法使用。");
        m.put("effect-failed", "易容失败，请稍后再试。");
        m.put("effect-none", "你现在没有正在进行的变形。");
        m.put("mob-invalid", "这个易容术的生物数据已失效，无法使用。");
        m.put("mob-sample-invalid", "这个目标无法取样（只能对生物）。");
        // 生物附身期间的提示（此前硬编码在 MobMasqueradeManager 里）
        m.put("mob-world-changed", "切世界，附身已结束。");
        m.put("mob-puppet-lost", "生物不见了，附身已结束。");
        m.put("mob-camera-lost", "相机被脱离，附身已结束。");
        m.put("mob-drive-failed", "操控失败，附身已结束。");
        m.put("mob-rejoin-ended", "易容效果已结束。");
        // 2026-10-29 生物黑名单：取样 / 指令 / 喝下已有的对应易容术都提示这条（%s = 生物名）
        m.put("mob-blacklisted", "这个生物不允许变身：%s");
        // 指令元数据（/help 与用法提示）；plugin.yml 里只是出厂默认，运行时以这里为准
        m.put("command-description", "易容术管理指令（item / clear / end）");
        m.put("command-usage", "/masq <item|clear|end> ...");
        DEFAULTS = Map.copyOf(m);
    }

    private final Map<String, String> values;
    private final String prefix;

    private Messages(Map<String, String> values, String prefix) {
        this.values = values;
        this.prefix = prefix;
    }

    /**
     * 从 {@code config.yml} 的 {@code messages} 段读取；缺键用默认值。
     *
     * <p>支持**嵌套段**（如 {@code messages.example.usage}）：键在内存里用 {@code .} 连接，
     * 这样一组文本可以集中在一个子段里，不会把顶层键堆成一长串。</p>
     */
    public static @NotNull Messages load(@Nullable ConfigurationSection section) {
        Map<String, String> merged = new HashMap<>(DEFAULTS);
        if (section != null) {
            collect(section, "", merged);
        }
        return new Messages(Map.copyOf(merged), merged.getOrDefault("prefix", ""));
    }

    private static void collect(@NotNull ConfigurationSection section, @NotNull String prefix,
                                @NotNull Map<String, String> out) {
        for (String key : section.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            ConfigurationSection child = section.getConfigurationSection(key);
            if (child != null) {
                collect(child, path, out);
                continue;
            }
            String value = section.getString(key);
            if (value != null) {
                out.put(path, value);
            }
        }
    }

    /** 原始模板（未格式化）。 */
    public @NotNull String raw(@NotNull String key) {
        return values.getOrDefault(key, key);
    }

    /** 格式化后的组件（不含前缀）。 */
    public @NotNull Component format(@NotNull String key, Object... args) {
        return TextFormat.join(raw(key), args);
    }

    /** 带前缀发给发送者。 */
    public void send(@NotNull CommandSender sender, @NotNull String key, Object... args) {
        Component body = format(key, args);
        sender.sendMessage(prefix.isEmpty() ? body : Component.text(prefix).append(body));
    }
}
