package io.mczju.masquerade.item;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * {@code /masq item <方块id>} 的参数解析（纯函数，可离线单测）。
 *
 * <p>只做**字符串归一化**：保留方块 id 与状态（如 {@code furnace[lit=true]}），
 * 是否合法由 {@code Bukkit.createBlockData(...)} + {@link MasqBlocks#isConvertible} 判定
 * —— 判据只有一处（`AGENTS.md` 铁律 11 的「第二处白名单」教训）。</p>
 */
public final class CommandIds {

    public enum Kind {
        /** {@code morphpotion}（保留 id，对应易容药水）。 */
        MORPH_POTION,
        /** 方块 id（可能带状态），交给 {@code createBlockData} 校验。 */
        BLOCK,
        /** 空 / legacy。 */
        UNKNOWN
    }

    /**
     * @param kind 分类
     * @param block {@link Kind#BLOCK} 时是**小写归一化**后的方块 id/状态
     */
    public record Parsed(@NotNull Kind kind, @NotNull String block) {
    }

    private CommandIds() {
    }

    public static @NotNull Parsed resolve(@Nullable String raw) {
        if (raw == null) {
            return new Parsed(Kind.UNKNOWN, "");
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return new Parsed(Kind.UNKNOWN, "");
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.equals("morphpotion")) {
            return new Parsed(Kind.MORPH_POTION, "");
        }
        if (lower.startsWith("legacy_")) {
            return new Parsed(Kind.UNKNOWN, lower);
        }
        // 方块 id 与状态值都是小写；统一小写后交给 Bukkit.createBlockData
        return new Parsed(Kind.BLOCK, lower);
    }
}
