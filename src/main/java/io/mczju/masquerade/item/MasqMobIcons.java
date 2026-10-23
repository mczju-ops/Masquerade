package io.mczju.masquerade.item;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 生物易容术的图标（纯函数，可离线单测）。
 *
 * <p>用户 2026-10-22 裁定：刷怪蛋图标；末影龙 → 龙头、凋灵 → 下界之星；
 * 其余没有刷怪蛋的生物 → {@code null}（药水外观兜底，与方块易容术一致）。</p>
 */
public final class MasqMobIcons {

    private MasqMobIcons() {
    }

    public static @Nullable Material iconOf(@NotNull EntityType type) {
        return switch (type) {
            case ENDER_DRAGON -> Material.DRAGON_HEAD;
            case WITHER -> Material.NETHER_STAR;
            default -> {
                Material egg = Material.matchMaterial(type.getKey().getKey() + "_spawn_egg");
                yield (egg != null && egg.isItem()) ? egg : null;
            }
        };
    }
}
