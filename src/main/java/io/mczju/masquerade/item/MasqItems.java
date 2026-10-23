package io.mczju.masquerade.item;

import io.mczju.masquerade.MasqKeys;
import io.mczju.masquerade.MasqueradePlugin;
import io.mczju.masquerade.config.MasqConfig;
import io.mczju.masquerade.util.MiniText;
import io.mczju.mczjuitemcreator.api.ItemCreatorApi;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 唯一与 MCZJUItemCreator 交互的地方（第二处调用 = 缺陷）。
 *
 * <ul>
 *   <li><b>易容药水</b>：静态 {@code items/morphpotion.yml} → {@code parseYamlToItems} → 补液体色后缓存；</li>
 *   <li><b>易容术</b>：按**方块状态**生成 YAML（{@link MasqItemYaml}）→ 同一个 API 构建 → 按状态缓存；</li>
 *   <li><b>识别</b>：只读 PDC（{@link ItemKind}），不靠材质/名称；</li>
 *   <li><b>发放</b>：按 {@code maxStackSize} 拆堆，放不下就掉在脚下（不静默吞物品）。</li>
 * </ul>
 */
public final class MasqItems {

    private final MasqueradePlugin plugin;
    private final MasqConfig config;
    private final ItemCreatorApi api;
    private final Set<Material> blacklist;
    private final Set<Material> invisibleIconBlocks;
    private final List<Material> convertibleMaterials;

    /** 缓存键 = {@code BlockData#getAsString()}（同一状态只构建一次）。 */
    private final Map<String, ItemStack> masqueradeCache = new HashMap<>();
    private @Nullable ItemStack potionTemplate;

    public MasqItems(@NotNull MasqueradePlugin plugin, @NotNull MasqConfig config, @NotNull ItemCreatorApi api) {
        this.plugin = plugin;
        this.config = config;
        this.api = api;
        this.blacklist = config.items().blockBlacklist();
        this.invisibleIconBlocks = config.items().invisibleIconBlocks();
        this.convertibleMaterials = MasqBlocks.convertibleMaterials(blacklist);
    }

    /** 重载：清缓存并重新构建药水模板（配置改完重启时走这里）。 */
    public void reload() {
        masqueradeCache.clear();
        potionTemplate = null;
        loadPotion();
    }

    public boolean potionAvailable() {
        return potionTemplate != null;
    }

    public @NotNull List<Material> convertibleMaterials() {
        return convertibleMaterials;
    }

    public boolean isConvertible(@Nullable BlockData data) {
        return MasqBlocks.isConvertible(data, blacklist);
    }

    /** 方块名（客户端翻译，用于消息里的 %s）。 */
    public @NotNull Component blockName(@NotNull Material material) {
        return Component.translatable(material.translationKey());
    }

    // === 构建 ===

    private void loadPotion() {
        Path path = plugin.getDataFolder().toPath().resolve("items").resolve("morphpotion.yml");
        if (!Files.isRegularFile(path)) {
            plugin.getLogger().warning("缺少 " + path + "，易容药水不可用（其余功能不受影响）。");
            return;
        }
        YamlConfiguration yaml;
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
                text = text.substring(1);
            }
            yaml = parseYaml(text);
        } catch (IOException | InvalidConfigurationException | RuntimeException ex) {
            plugin.getLogger().warning("读取 items/morphpotion.yml 失败：" + ex.getMessage());
            return;
        }
        Map<String, ItemStack> parsed;
        try {
            parsed = api.parseYamlToItems(yaml);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("ItemCreator 解析易容药水失败：" + ex.getMessage());
            return;
        }
        String id = config.items().potionId();
        ItemStack base = parsed.get(id);
        if (base == null) {
            plugin.getLogger().warning("items/morphpotion.yml 里没有物品 " + id + "（易容药水不可用）。");
            return;
        }
        potionTemplate = decoratePotion(colorize(base));
        plugin.getLogger().info("易容药水模板已就绪（" + id + "）。");
    }

    /**
     * 药水名称 / lore 的 config.yml 覆盖（可选）：
     * 空值 = 沿用 {@code items/morphpotion.yml} 的 {@code customName} / {@code lore}。
     */
    private @NotNull ItemStack decoratePotion(@NotNull ItemStack base) {
        String name = config.items().potionName();
        List<String> lore = config.items().potionLore();
        ItemStack item = base.clone();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        // tooltip 的「无效果」来自空 potion_contents；设成隐身基础药水即可显示「隐身」。
        // ★ 药水本身仍被 PlayerItemConsumeEvent 拦截，喝不下去；这里只影响 tooltip。
        if (config.items().potionInvisibility() && meta instanceof PotionMeta potionMeta) {
            potionMeta.setBasePotionType(PotionType.INVISIBILITY);
            potionMeta.setColor(config.items().potionColor());
            meta = potionMeta;
        }
        if (name != null && !name.isBlank()) {
            meta.displayName(MiniText.render(name));
        }
        if (lore != null && !lore.isEmpty()) {
            meta.lore(lore.stream().map(line -> MiniText.render(line)).toList());
        }
        item.setItemMeta(meta);
        return item;
    }

    /** 补液体色：ItemCreator 没有 potionColor 字段，只能在构建后改 PotionMeta（docs/04 §5.3）。 */
    private @NotNull ItemStack colorize(@NotNull ItemStack base) {
        ItemStack item = base.clone();
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof PotionMeta potionMeta) {
            potionMeta.setColor(config.items().potionColor());
            item.setItemMeta(potionMeta);
        } else {
            plugin.getLogger().warning("易容药水的 material 不是 POTION，液体颜色无法设置。");
        }
        return item;
    }

    /**
     * 用 {@code config.yml} 的模板覆盖显示名/lore。
     *
     * <p>{@code %s} = 方块名 / 生物名（客户端翻译）；不写 {@code %s} 就只是固定文案。
     * 这样「易容术（熔炉）」之类的命名完全由配置决定，代码里不再有写死的物品名。</p>
     */
    private @NotNull ItemStack decorate(@NotNull ItemStack base, @Nullable String nameTemplate,
                                        @Nullable List<String> loreTemplate, @NotNull Component arg,
                                        boolean showInvisibility) {
        ItemStack item = base.clone();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        // 取样后的易容术本身也是 3 分钟隐身药水；只改展示，真正的效果由插件应用
        if (showInvisibility && meta instanceof PotionMeta potionMeta) {
            potionMeta.setBasePotionType(PotionType.INVISIBILITY);
            potionMeta.setColor(config.items().potionColor());
            meta = potionMeta;
        }
        if (nameTemplate != null && !nameTemplate.isBlank()) {
            meta.displayName(MiniText.render(nameTemplate, arg));
        }
        if (loreTemplate != null && !loreTemplate.isEmpty()) {
            meta.lore(loreTemplate.stream().map(line -> MiniText.render(line, arg)).toList());
        }
        item.setItemMeta(meta);
        return item;
    }

    /** {@code YamlConfiguration#loadFromString} 是实例方法（Paper 26.2），这里包一层。 */
    private static @NotNull YamlConfiguration parseYaml(@NotNull String text) throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }

    public @NotNull Optional<ItemStack> morphpotion() {
        ItemStack template = potionTemplate;
        return template == null ? Optional.empty() : Optional.of(template.clone());
    }

    /** 按方块状态生成「易容术」（缓存命中直接 clone）。 */
    public @NotNull Optional<ItemStack> masqueradeOf(@NotNull BlockData state) {
        String key = state.getAsString();
        ItemStack cached = masqueradeCache.get(key);
        if (cached != null) {
            return Optional.of(cached.clone());
        }
        Material icon = iconOf(state);
        String itemId = itemIdOf(key);
        String yaml = MasqItemYaml.build(
                itemId,
                Material.POTION.name(),
                icon == null ? null : icon.name(),
                key,
                config.items().masqueradeName(),
                config.items().masqueradeLore(),
                config.items().cooldownSeconds(),
                config.items().cooldownGroup()
        );
        Map<String, ItemStack> parsed;
        try {
            parsed = api.parseYamlToItems(parseYaml(yaml));
        } catch (InvalidConfigurationException | RuntimeException ex) {
            plugin.getLogger().warning("生成易容术（" + key + "）失败：" + ex.getMessage());
            return Optional.empty();
        }
        ItemStack built = parsed.get(itemId);
        if (built == null) {
            plugin.getLogger().warning("生成易容术（" + key + "）失败：ItemCreator 未返回物品。");
            return Optional.empty();
        }
        // ★ 补药水颜色：没有 item_model 时（无图标方块）它直接决定图标外观——
        //   必须和「能正常显示的易容药水」一致；有 item_model 时颜色不可见，无副作用。
        //   （2026-10-22 实测：potion 不带 potion_contents 却硬套 item_model=potion ⇒ 图标空白）
        ItemStack finished = decorate(colorize(built),
                config.items().masqueradeName(), config.items().masqueradeLore(),
                blockName(state.getMaterial()), config.effect().invisibility());
        masqueradeCache.put(key, finished);
        if (config.debug()) {
            plugin.getLogger().info("[debug] 已生成易容术模板 " + key + " → " + itemId
                    + "（图标 " + (icon == null ? "无 ⇒ 易容药水外观" : icon.name()) + "）");
        }
        return Optional.of(finished.clone());
    }

    /**
     * 图标材质：
     * <ol>
     *   <li>优先方块自身的物品形态；</li>
     *   <li>否则用 {@code getPlacementMaterial()}（如小麦作物 → 小麦）；</li>
     *   <li>图标不可见的方块（{@code items.invisible-icon-blocks}，如 BARRIER/LIGHT）与没有物品形态的方块
     *       ⇒ 返回 {@code null} = **不写 item_model**，物品保持易容药水外观（带颜色）。
     *       ★ 不能写 {@code item_model: POTION}：实测那样图标是空白的。</li>
     * </ol>
     */
    private @Nullable Material iconOf(@NotNull BlockData state) {
        if (isUsableIcon(state.getMaterial())) {
            return state.getMaterial();
        }
        try {
            Material placement = state.getPlacementMaterial();
            if (isUsableIcon(placement)) {
                return placement;
            }
        } catch (RuntimeException ignored) {
            // 某些方块没有放置物品；忽略即可
        }
        if (config.debug()) {
            plugin.getLogger().info("[debug] 方块 " + state.getAsString()
                    + " 没有可用图标 ⇒ 用易容药水外观");
        }
        return null;
    }

    private boolean isUsableIcon(@Nullable Material material) {
        return material != null && material.isItem() && !invisibleIconBlocks.contains(material);
    }

    /** 内部物品 id（只为 ItemCreator YAML 的顶层键，无任何语义）。 */
    public static @NotNull String itemIdOf(@NotNull String blockState) {
        return "masquerade_" + Integer.toHexString(blockState.hashCode());
    }

    // === 识别（只认 PDC） ===

    public @NotNull ItemKind.Classification kindOf(@Nullable ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return ItemKind.NOT_OURS;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return ItemKind.NOT_OURS;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return ItemKind.classify(
                pdc.get(MasqKeys.TYPE, PersistentDataType.STRING),
                pdc.get(MasqKeys.BLOCK, PersistentDataType.STRING),
                pdc.get(MasqKeys.MOB_TYPE, PersistentDataType.STRING)
        );
    }

    /** 生物易容术：按快照 + 类型生成（不缓存，每个快照都不同）。 */
    public @NotNull Optional<ItemStack> masqueradeOfMob(@NotNull String snapshot, @NotNull EntityType type) {
        Material icon = MasqMobIcons.iconOf(type);
        String itemId = mobItemIdOf(type);
        String yaml = MasqItemYaml.buildMob(
                itemId,
                Material.POTION.name(),
                icon == null ? null : icon.name(),
                type.name(),
                snapshot,
                config.items().masqueradeMobName(),
                config.items().mobLore(),
                config.items().cooldownSeconds(),
                config.items().cooldownGroup()
        );
        Map<String, ItemStack> parsed;
        try {
            parsed = api.parseYamlToItems(parseYaml(yaml));
        } catch (InvalidConfigurationException | RuntimeException ex) {
            plugin.getLogger().warning("生成生物易容术（" + type + "）失败：" + ex.getMessage());
            return Optional.empty();
        }
        ItemStack built = parsed.get(itemId);
        if (built == null) {
            plugin.getLogger().warning("生成生物易容术（" + type + "）失败：ItemCreator 未返回物品。");
            return Optional.empty();
        }
        ItemStack finished = decorate(colorize(built),
                config.items().masqueradeMobName(), config.items().mobLore(),
                mobName(type), config.effect().invisibility());
        if (config.debug()) {
            plugin.getLogger().info("[debug] 已生成生物易容术 " + type
                    + "（图标 " + (icon == null ? "无 ⇒ 易容药水外观" : icon.name()) + "）");
        }
        return Optional.of(finished);
    }

    public static @NotNull String mobItemIdOf(@NotNull EntityType type) {
        return "masquerade_mob_" + type.name().toLowerCase(Locale.ROOT);
    }

    /** 读物品 PDC 里的生物类型；不合法 ⇒ 空。 */
    public @NotNull Optional<EntityType> mobTypeOf(@Nullable ItemKind.Classification classification) {
        if (classification == null || !classification.isMob() || classification.mobType() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(EntityType.valueOf(classification.mobType().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /** 读物品 PDC 里的快照 SNBT。 */
    public @Nullable String mobSnapshotOf(@Nullable ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        return meta.getPersistentDataContainer().get(MasqKeys.MOB, PersistentDataType.STRING);
    }

    /** 客户端翻译的生物名（用于消息里的 %s）。 */
    public @NotNull Component mobName(@NotNull EntityType type) {
        return Component.translatable(type.translationKey());
    }

    /** 消耗主手 1 瓶易容药水（取样用；与方块取样完全一致）。 */
    public void consumeOnePotion(@NotNull Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        int amount = hand.getAmount();
        if (amount <= 1) {
            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
            return;
        }
        ItemStack copy = hand.clone();
        copy.setAmount(amount - 1);
        player.getInventory().setItemInMainHand(copy);
    }

    /** 把物品 PDC 解析成方块状态；非法/空气/黑名单 ⇒ 空。 */
    public @NotNull Optional<BlockData> masqueradeBlock(@Nullable ItemKind.Classification classification) {
        if (classification == null || !classification.isMasquerade()) {
            return Optional.empty();
        }
        return parseBlockState(classification.blockName());
    }

    /** 容错解析方块状态：先按 {@code BlockData} 字符串，再按旧版 {@code Material} 名。 */
    public @NotNull Optional<BlockData> parseBlockState(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String value = raw.trim();
        try {
            BlockData data = Bukkit.createBlockData(value);
            if (isConvertible(data)) {
                return Optional.of(data);
            }
        } catch (IllegalArgumentException ignored) {
            // 落到下面的 Material 兼容分支
        }
        Material material = Material.matchMaterial(value);
        if (material == null) {
            return Optional.empty();
        }
        BlockData data = material.createBlockData();
        return isConvertible(data) ? Optional.of(data) : Optional.empty();
    }

    // === 采样冷却 ===

    public int samplingCooldownTicks() {
        return Math.max(0, config.items().sampleCooldownSeconds() * 20);
    }

    public boolean hasSamplingCooldown(@NotNull Player player) {
        ItemStack template = potionTemplate;
        return template != null && player.hasCooldown(template);
    }

    /** 剩余秒数（向上取整，至少 1）。 */
    public int samplingCooldownRemainingSeconds(@NotNull Player player) {
        ItemStack template = potionTemplate;
        if (template == null) {
            return 0;
        }
        int ticks = player.getCooldown(template);
        return ticks <= 0 ? 0 : Math.max(1, (int) Math.ceil(ticks / 20.0));
    }

    public void applySamplingCooldown(@NotNull Player player) {
        ItemStack template = potionTemplate;
        int ticks = samplingCooldownTicks();
        if (template != null && ticks > 0) {
            player.setCooldown(template, ticks);
        }
    }

    // === 发放 ===

    /**
     * 按堆叠上限拆堆发放；返回**掉在脚下的件数**（0 = 全部进背包）。
     */
    public int give(@NotNull Player player, @NotNull ItemStack template, int count) {
        int max = Math.max(1, template.getMaxStackSize());
        int remaining = Math.max(0, count);
        int dropped = 0;
        while (remaining > 0) {
            int amount = Math.min(max, remaining);
            ItemStack stack = template.clone();
            stack.setAmount(amount);
            for (ItemStack leftover : player.getInventory().addItem(stack).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                dropped += leftover.getAmount();
            }
            remaining -= amount;
        }
        return dropped;
    }
}
