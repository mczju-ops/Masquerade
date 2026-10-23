package io.mczju.masquerade.command;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * {@code /masq clear [selector]} 的选择器预检（纯函数，可离线单测）。
 *
 * <p>规则（需求 R7）：留空 = {@code @s}；**不接受任何 {@code @e} 变体**；
 * 控制台没有 {@code @s}，必须显式给选择器。</p>
 */
public final class SelectorGuard {

    public enum Reason {
        /** 通过。 */
        OK,
        /** 含 {@code @e}（含 {@code @e[type=player]} 这类"看起来只选玩家"的写法）。 */
        REJECT_AT_E,
        /** 控制台留空。 */
        EMPTY_FROM_CONSOLE
    }

    /**
     * @param reason 判定
     * @param selector 通过时是实际要用的选择器（留空已被替换成 {@code @s}）
     */
    public record Decision(@NotNull Reason reason, @NotNull String selector) {
        public boolean ok() {
            return reason == Reason.OK;
        }
    }

    private SelectorGuard() {
    }

    public static @NotNull Decision precheck(@Nullable String raw, boolean senderIsPlayer) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            return senderIsPlayer
                    ? new Decision(Reason.OK, "@s")
                    : new Decision(Reason.EMPTY_FROM_CONSOLE, "");
        }
        if (trimmed.toLowerCase(Locale.ROOT).contains("@e")) {
            return new Decision(Reason.REJECT_AT_E, trimmed);
        }
        return new Decision(Reason.OK, trimmed);
    }
}
