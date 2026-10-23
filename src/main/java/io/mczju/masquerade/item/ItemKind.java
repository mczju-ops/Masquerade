package io.mczju.masquerade.item;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 物品 PDC 的语义分类（纯函数，可离线单测）。
 *
 * <p>输入是 {@code masquerade:type} / {@code masquerade:block} / {@code masquerade:mob-type}
 * 三个 PDC 值，输出「是不是我们的、是哪一种」。**不碰 ItemStack / Material**，
 * 所以既可以在测试里穷举，也可以在生产代码里被 {@link MasqItems} 直接复用。</p>
 */
public final class ItemKind {

    public enum Kind {
        /** 没有 {@code masquerade:type}（不是本插件的物品）。 */
        NOT_OURS,
        /** 原料药水。 */
        MORPH_POTION,
        /** 方块易容术（需要 {@code masquerade:block}）。 */
        MASQUERADE,
        /** 生物易容术（需要 {@code masquerade:mob} + {@code masquerade:mob-type}）。 */
        MASQUERADE_MOB,
        /** 带我们的 type，但内容不完整/不认识 —— **一律不当作自己的**。 */
        INVALID
    }

    /**
     * @param kind      分类
     * @param blockName 仅 {@link Kind#MASQUERADE} 非空（方块状态字符串）
     * @param mobType   仅 {@link Kind#MASQUERADE_MOB} 非空（{@code EntityType} 名）
     */
    public record Classification(@NotNull Kind kind, @Nullable String blockName, @Nullable String mobType) {
        public boolean isMasquerade() {
            return kind == Kind.MASQUERADE;
        }

        public boolean isMob() {
            return kind == Kind.MASQUERADE_MOB;
        }
    }

    private ItemKind() {
    }

    public static final Classification NOT_OURS = new Classification(Kind.NOT_OURS, null, null);
    public static final Classification INVALID = new Classification(Kind.INVALID, null, null);

    public static @NotNull Classification classify(@Nullable String type, @Nullable String blockName) {
        return classify(type, blockName, null);
    }

    public static @NotNull Classification classify(@Nullable String type, @Nullable String blockName,
                                                   @Nullable String mobType) {
        if (type == null) {
            return NOT_OURS;
        }
        return switch (type) {
            case "morphpotion" -> new Classification(Kind.MORPH_POTION, null, null);
            case "masquerade" -> {
                if (mobType != null && !mobType.isBlank()) {
                    yield new Classification(Kind.MASQUERADE_MOB, null, mobType.trim());
                }
                if (blockName != null && !blockName.isBlank()) {
                    yield new Classification(Kind.MASQUERADE, blockName.trim(), null);
                }
                yield INVALID;
            }
            default -> INVALID;
        };
    }
}
