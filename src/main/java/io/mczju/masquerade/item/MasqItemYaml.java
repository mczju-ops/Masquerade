package io.mczju.masquerade.item;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 生成「易容术」物品的 **ItemCreator YAML**（纯函数，可离线单测）。
 *
 * <p>按**方块状态**（{@code BlockData#getAsString()}，如 {@code minecraft:furnace[lit=true]}）
 * 生成，PDC 直接存这个字符串 —— 不再经过「物品 id」（用户 2026-10-21 裁定）。</p>
 *
 * <p>图标（{@code itemModel}）只能引用**物品模型**：点亮的熔炉没有物品模型 ⇒ 只能显示未点亮的图标；
 * 没有物品形态的方块（水/耕地…）连图标都没有 ⇒ {@code iconMaterialName} 传 {@code null}，
 * 物品退回药水外观（`docs/02` §7，用户已裁定接受）。</p>
 */
public final class MasqItemYaml {

    private MasqItemYaml() {
    }

    /**
     * @param itemId           ItemCreator 物品 ID（顶层键，内部用）
     * @param materialName     底层材质（固定 {@code POTION}）
     * @param iconMaterialName 图标用的物品材质名；{@code null} ⇒ 不写 {@code itemModel}
     * @param blockState       方块状态字符串（写进 PDC）
     * @param name             显示名（MiniMessage）
     * @param lore             lore 行（MiniMessage；null 视为空列表）
     * @param cooldownSeconds  冷却遮罩秒数（{@code <= 0} ⇒ 不写 {@code useCooldown}）
     * @param cooldownGroup    冷却组（{@code namespace:path}）
     */
    public static @NotNull String build(
            @NotNull String itemId,
            @NotNull String materialName,
            @Nullable String iconMaterialName,
            @NotNull String blockState,
            @Nullable String name,
            @Nullable List<String> lore,
            int cooldownSeconds,
            @Nullable String cooldownGroup
    ) {
        StringBuilder sb = new StringBuilder(512);
        sb.append(quote(itemId)).append(":\n");
        sb.append("  material: ").append(materialName).append('\n');
        if (iconMaterialName != null && !iconMaterialName.isBlank()) {
            sb.append("  itemModel: ").append(iconMaterialName).append('\n');
        }
        if (name != null && !name.isBlank()) {
            sb.append("  customName: ").append(quote(name)).append('\n');
        }
        if (lore != null && !lore.isEmpty()) {
            sb.append("  lore:\n");
            for (String line : lore) {
                sb.append("    - ").append(quote(line == null ? "" : line)).append('\n');
            }
        }
        // 不可堆叠（需求 R3.1）
        sb.append("  maxStackSize: 1\n");
        if (cooldownSeconds > 0 && cooldownGroup != null && !cooldownGroup.isBlank()) {
            sb.append("  useCooldown:\n");
            sb.append("    cooldownGroup: ").append(quote(cooldownGroup)).append('\n');
            sb.append("    seconds: ").append(cooldownSeconds).append('\n');
        }
        sb.append("  pdc:\n");
        sb.append("    - key: masquerade:type\n");
        sb.append("      type: STRING\n");
        sb.append("      value: masquerade\n");
        sb.append("    - key: masquerade:block\n");
        sb.append("      type: STRING\n");
        sb.append("      value: ").append(quote(blockState)).append('\n');
        return sb.toString();
    }

    /**
     * 生物易容术：底层仍是药水，PDC 换成 {@code masquerade:mob}（SNBT）+ {@code masquerade:mob-type}。
     *
     * @param iconMaterialName 刷怪蛋/龙头/下界之星；{@code null} ⇒ 不写 {@code itemModel}（药水外观）
     */
    public static @NotNull String buildMob(
            @NotNull String itemId,
            @NotNull String materialName,
            @Nullable String iconMaterialName,
            @NotNull String mobType,
            @NotNull String snapshot,
            @Nullable String name,
            @Nullable List<String> lore,
            int cooldownSeconds,
            @Nullable String cooldownGroup
    ) {
        StringBuilder sb = new StringBuilder(512 + snapshot.length());
        sb.append(quote(itemId)).append(":\n");
        sb.append("  material: ").append(materialName).append('\n');
        if (iconMaterialName != null && !iconMaterialName.isBlank()) {
            sb.append("  itemModel: ").append(iconMaterialName).append('\n');
        }
        if (name != null && !name.isBlank()) {
            sb.append("  customName: ").append(quote(name)).append('\n');
        }
        if (lore != null && !lore.isEmpty()) {
            sb.append("  lore:\n");
            for (String line : lore) {
                sb.append("    - ").append(quote(line == null ? "" : line)).append('\n');
            }
        }
        sb.append("  maxStackSize: 1\n");
        if (cooldownSeconds > 0 && cooldownGroup != null && !cooldownGroup.isBlank()) {
            sb.append("  useCooldown:\n");
            sb.append("    cooldownGroup: ").append(quote(cooldownGroup)).append('\n');
            sb.append("    seconds: ").append(cooldownSeconds).append('\n');
        }
        sb.append("  pdc:\n");
        sb.append("    - key: masquerade:type\n");
        sb.append("      type: STRING\n");
        sb.append("      value: masquerade\n");
        sb.append("    - key: masquerade:mob-type\n");
        sb.append("      type: STRING\n");
        sb.append("      value: ").append(quote(mobType)).append('\n');
        sb.append("    - key: masquerade:mob\n");
        sb.append("      type: STRING\n");
        sb.append("      value: ").append(quote(snapshot)).append('\n');
        return sb.toString();
    }

    /** YAML 双引号标量：转义 {@code \ " \n \r \t}。 */
    static @NotNull String quote(@NotNull String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        sb.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
