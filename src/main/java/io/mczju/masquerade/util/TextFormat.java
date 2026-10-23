package io.mczju.masquerade.util;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 把 {@code config.yml} 里的纯文本模板（{@code %s} / {@code %d}）转成 Adventure 组件。
 *
 * <p>之所以不直接用 {@code Component.text(formatted)}：方块名要用**客户端翻译**
 * （{@code Component.translatable}），所以参数允许是 {@link Component}，
 * 直接拼进消息里，而不是先 toString 成英文 id。</p>
 *
 * <p>纯函数，可离线单测（{@code TextFormatTest}）。</p>
 */
public final class TextFormat {

    private TextFormat() {
    }

    /**
     * 依次替换模板里的 {@code %s} / {@code %d}。
     *
     * <ul>
     *   <li>参数是 {@link Component} ⇒ 原样拼接（保持可翻译/带颜色）；</li>
     *   <li>参数是其它对象 ⇒ {@code String.valueOf(...)} 后拼接；</li>
     *   <li>占位符比参数多 ⇒ **保留原样**（不吞字符，方便发现配置写错）；</li>
     *   <li>{@code %} 后面不是 {@code s}/{@code d} ⇒ 当作普通字符。</li>
     * </ul>
     */
    public static @NotNull Component join(@Nullable String template, Object... args) {
        if (template == null || template.isEmpty()) {
            return Component.empty();
        }
        Component out = Component.empty();
        int index = 0;
        int i = 0;
        final int n = template.length();
        while (i < n) {
            int p = template.indexOf('%', i);
            if (p < 0 || p + 1 >= n) {
                out = out.append(Component.text(template.substring(i)));
                break;
            }
            char flag = template.charAt(p + 1);
            if (!(flag == 's' || flag == 'd') || index >= args.length) {
                out = out.append(Component.text(template.substring(i, p + 1)));
                i = p + 1;
                continue;
            }
            if (p > i) {
                out = out.append(Component.text(template.substring(i, p)));
            }
            Object arg = args[index++];
            out = out.append(arg instanceof Component component
                    ? component
                    : Component.text(String.valueOf(arg)));
            i = p + 2;
        }
        return out;
    }
}
