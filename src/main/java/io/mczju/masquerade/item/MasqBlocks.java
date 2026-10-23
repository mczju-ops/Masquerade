package io.mczju.masquerade.item;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 取样范围判据与候选表。
 *
 * <p>★ 用户 2026-10-21 裁定：**除空气方块外，不拒绝任何方块**（`items.block-blacklist` 默认空，
 * 留作服主可选）。判据只此一处：取样与 `/masq item` 共用 {@link #isConvertible}。</p>
 */
public final class MasqBlocks {

    private MasqBlocks() {
    }

    public static boolean isConvertible(@Nullable BlockData data, @Nullable Set<Material> blacklist) {
        if (data == null) {
            return false;
        }
        Material material = data.getMaterial();
        if (material.isAir() || !material.isBlock()) {
            return false;
        }
        return blacklist == null || !blacklist.contains(material);
    }

    /** 全部「非空气方块」材质（只用于 Tab 补全的基础 id 列表）。 */
    public static @NotNull List<Material> convertibleMaterials(@Nullable Set<Material> blacklist) {
        List<Material> out = new ArrayList<>(1024);
        for (Material material : Material.values()) {
            if (material.isAir() || !material.isBlock() || material.isLegacy()) {
                continue;
            }
            if (blacklist != null && blacklist.contains(material)) {
                continue;
            }
            out.add(material);
        }
        return List.copyOf(out);
    }

    /** 方块 id（小写、无命名空间），用于 Tab 补全与 `/masq item`。 */
    public static @NotNull String idOf(@NotNull Material material) {
        return material.getKey().getKey();
    }
}
