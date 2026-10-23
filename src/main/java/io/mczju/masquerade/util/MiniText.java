package io.mczju.masquerade.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 物品名称 / lore 的 MiniMessage 渲染（支持 {@code %s} 插入**可翻译**的方块名/生物名）。
 *
 * <p>为什么不能直接用 {@link TextFormat}：{@code config.yml} 里的物品文案是 MiniMessage
 * （{@code <light_purple>…</light_purple>}），而消息是纯文本。这里把 {@code %s} 换成一个
 * MiniMessage 占位符，再交给客户端的翻译组件插入，这样「易容术（熔炉）」里的方块名
 * 会跟随客户端语言，而不是写死英文 id。</p>
 *
 * <p>★ 配置写坏（MiniMessage 语法错）时**回落到纯文本**，绝不让一个错字导致物品生成失败。</p>
 */
public final class MiniText {

    private MiniText() {
    }

    public static @NotNull Component render(@Nullable String template) {
        return render(template, new Component[0]);
    }

    /**
     * @param template MiniMessage 模板；{@code %s} 依次替换成 {@code args}
     * @param args     要插入的组件（通常是 {@code Component.translatable(...)}）
     */
    public static @NotNull Component render(@Nullable String template, @NotNull Component... args) {
        if (template == null || template.isEmpty()) {
            return Component.empty();
        }
        try {
            String replaced = template;
            TagResolver[] resolvers = new TagResolver[args.length];
            if (args.length == 1) {
                // 单个参数：允许同一行出现多次 %s（lore 常见）
                replaced = replaced.replace("%s", "<masq_arg_0>");
                resolvers[0] = Placeholder.component("masq_arg_0", args[0]);
            } else {
                for (int i = 0; i < args.length; i++) {
                    String tag = "masq_arg_" + i;
                    int at = replaced.indexOf("%s");
                    if (at >= 0) {
                        replaced = replaced.substring(0, at) + '<' + tag + '>' + replaced.substring(at + 2);
                    }
                    resolvers[i] = Placeholder.component(tag, args[i]);
                }
            }
            return MiniMessage.miniMessage().deserialize(replaced, resolvers);
        } catch (RuntimeException ex) {
            // MiniMessage 语法错 ⇒ 退回纯文本（物品仍然生成，只是没有颜色）
            return TextFormat.join(template, (Object[]) args);
        }
    }
}
