package io.mczju.masquerade.command;

import io.mczju.masquerade.MasqueradePlugin;
import io.mczju.masquerade.config.MasqConfig;
import io.mczju.masquerade.config.Messages;
import io.mczju.masquerade.effect.MasqueradeManager;
import io.mczju.masquerade.effect.MobMasqueradeManager;
import io.mczju.masquerade.item.CommandIds;
import io.mczju.masquerade.item.MasqBlocks;
import io.mczju.masquerade.item.MasqItems;
import io.mczju.masquerade.nms.MasqNms;
import org.bukkit.Bukkit;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /masq} 指令（需求 R5~R8）。
 *
 * <ul>
 *   <li>{@code /masq item <item_id> [count]}（玩家）</li>
 *   <li>{@code /masq item morphpotion [count]}（玩家）</li>
 *   <li>{@code /masq clear [selector]}（玩家/控制台；默认 {@code @s}，拒绝 {@code @e}）</li>
 * </ul>
 *
 * <p>★ 只碰「执行者本人 / 选择器选出的玩家」，不广播、不遍历世界（铁律 10）。</p>
 */
public final class MasqCommand implements CommandExecutor, TabCompleter {

    /** 管理指令（item / clear）：plugin.yml 的 {@code masquerade.admin}。 */
    public static final String PERMISSION = "masquerade.admin";
    /** 玩家自助结束（end）：plugin.yml 的 {@code masquerade.use}（default true）。 */
    public static final String USE_PERMISSION = "masquerade.use";

    private final MasqueradePlugin plugin;
    private final MasqConfig config;
    private final MasqItems items;
    private final MasqueradeManager manager;
    private final MobMasqueradeManager mobManager;
    private final Messages messages;

    public MasqCommand(
            @NotNull MasqueradePlugin plugin,
            @NotNull MasqConfig config,
            @NotNull MasqItems items,
            @NotNull MasqueradeManager manager,
            @NotNull MobMasqueradeManager mobManager
    ) {
        this.plugin = plugin;
        this.config = config;
        this.items = items;
        this.manager = manager;
        this.mobManager = mobManager;
        this.messages = config.messages();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            messages.send(sender, "usage");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("end")) {
            if (!hasUse(sender)) {
                messages.send(sender, "no-permission");
                return true;
            }
            handleEnd(sender);
            return true;
        }
        if (!sender.hasPermission(PERMISSION)) {
            messages.send(sender, "no-permission");
            return true;
        }
        switch (sub) {
            case "item" -> handleItem(sender, args);
            case "clear" -> handleClear(sender, args);
            default -> messages.send(sender, "usage");
        }
        return true;
    }

    private static boolean hasUse(@NotNull CommandSender sender) {
        return sender.hasPermission(USE_PERMISSION) || sender.hasPermission(PERMISSION);
    }

    // === /masq end（玩家自助，结束方块 + 生物） ===

    private void handleEnd(@NotNull CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "players-only");
            return;
        }
        boolean block = manager.clear(player);
        boolean mob = mobManager.end(player, "指令", true);
        if (!block && !mob) {
            messages.send(sender, "effect-none");
        } else {
            messages.send(sender, "effect-ended");
        }
        if (config.debug()) {
            plugin.getLogger().info("[debug] " + player.getName() + " 用 /masq end 结束（方块="
                    + block + "，生物=" + mob + "）");
        }
    }

    // === /masq item ===

    private void handleItem(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "players-only");
            return;
        }
        if (args.length < 2) {
            messages.send(sender, "usage");
            return;
        }
        int max = config.commands().itemCountMax();
        int count = 1;
        if (args.length >= 3) {
            try {
                count = Integer.parseInt(args[2].trim());
            } catch (NumberFormatException ex) {
                messages.send(sender, "count-invalid", max);
                return;
            }
        }
        if (count < 1 || count > max) {
            messages.send(sender, "count-invalid", max);
            return;
        }

        CommandIds.Parsed parsed = CommandIds.resolve(args[1]);
        switch (parsed.kind()) {
            case MORPH_POTION -> {
                ItemStack template = items.morphpotion().orElse(null);
                if (template == null) {
                    messages.send(sender, "unknown-item", "morphpotion");
                    return;
                }
                give(player, template, count);
            }
            case BLOCK -> {
                org.bukkit.block.data.BlockData state;
                try {
                    state = Bukkit.createBlockData(parsed.block());
                } catch (IllegalArgumentException ex) {
                    // 不是方块 ⇒ 试生物类型（/masq item zombie）
                    EntityType mobType = mobTypeOf(parsed.block());
                    if (mobType != null) {
                        giveMobItem(player, mobType, count);
                        return;
                    }
                    messages.send(sender, "unknown-item", args[1]);
                    return;
                }
                if (!items.isConvertible(state)) {
                    messages.send(sender, "not-a-block", items.blockName(state.getMaterial()));
                    return;
                }
                ItemStack template = items.masqueradeOf(state).orElse(null);
                if (template == null) {
                    messages.send(sender, "effect-failed");
                    return;
                }
                give(player, template, count);
            }
            case UNKNOWN -> messages.send(sender, "unknown-item", args[1]);
        }
    }

    /** 生物类型（`zombie` / `minecraft:zombie` / `ZOMBIE`）；不是 Mob 或已移除的 Boss ⇒ null。 */
    private @Nullable EntityType mobTypeOf(@NotNull String raw) {
        String key = raw.startsWith("minecraft:") ? raw.substring("minecraft:".length()) : raw;
        try {
            EntityType type = EntityType.valueOf(key.toUpperCase(Locale.ROOT));
            if (type == EntityType.ENDER_DRAGON || type == EntityType.WITHER) {
                return null;
            }
            Class<? extends Entity> clazz = type.getEntityClass();
            return clazz != null && Mob.class.isAssignableFrom(clazz) ? type : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * `/masq item <生物>`：用最小 NBT 快照生成默认生物物品。
     * ★ 不再临时 spawn（2026-10-23 用户反馈在部分区域 spawn 失败导致「易容失败」）。
     */
    private void giveMobItem(@NotNull Player player, @NotNull EntityType type, int count) {
        String snapshot = MasqNms.minimalSnapshot(type);
        ItemStack template = items.masqueradeOfMob(snapshot, type).orElse(null);
        if (template == null) {
            messages.send(player, "effect-failed");
            return;
        }
        give(player, template, count);
    }

    private void give(@NotNull Player player, @NotNull ItemStack template, int count) {
        int dropped = items.give(player, template, count);
        if (dropped > 0) {
            messages.send(player, "item-given-dropped", template.effectiveName(), count, dropped);
        } else {
            messages.send(player, "item-given", template.effectiveName(), count);
        }
        if (config.debug()) {
            plugin.getLogger().info("[debug] " + player.getName() + " 通过指令获得 "
                    + template.getType() + " ×" + count + "（掉落 " + dropped + "）");
        }
    }

    // === /masq clear ===

    private void handleClear(@NotNull CommandSender sender, @NotNull String[] args) {
        String raw = args.length >= 2 ? args[1] : "";
        SelectorGuard.Decision decision = SelectorGuard.precheck(raw, sender instanceof Player);
        if (!decision.ok()) {
            if (decision.reason() == SelectorGuard.Reason.REJECT_AT_E) {
                messages.send(sender, "selector-rejected");
            } else {
                messages.send(sender, "players-only");
            }
            return;
        }

        List<Entity> selected;
        try {
            selected = Bukkit.selectEntities(sender, decision.selector());
        } catch (IllegalArgumentException ex) {
            String detail = ex.getMessage() == null ? decision.selector() : ex.getMessage();
            messages.send(sender, "selector-invalid", detail);
            return;
        }

        Set<UUID> seen = new HashSet<>();
        int cleared = 0;
        for (Entity entity : selected) {
            if (!(entity instanceof Player player) || !seen.add(player.getUniqueId())) {
                continue;
            }
            // ★ 方块 + 生物都要清：只清方块会把傀儡/相机/隐身留在场上（2026-10-23 安全复核）
            boolean block = manager.clear(player);
            boolean mob = mobManager.end(player, "指令", true);
            if (block || mob) {
                cleared++;
            }
        }
        if (cleared == 0) {
            messages.send(sender, "clear-none");
            return;
        }
        messages.send(sender, "clear-done", cleared);
        if (config.debug()) {
            plugin.getLogger().info("[debug] " + sender.getName() + " 清除了 " + cleared + " 名玩家的易容（"
                    + decision.selector() + "）");
        }
    }

    // === Tab 补全 ===

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                               @NotNull String alias, @NotNull String[] args) {
        boolean admin = sender.hasPermission(PERMISSION);
        boolean use = hasUse(sender);
        if (!admin && !use) {
            return List.of();
        }
        List<String> subs = new ArrayList<>(3);
        if (admin) {
            subs.add("item");
            subs.add("clear");
        }
        if (use) {
            subs.add("end");
        }
        if (args.length == 1) {
            return filter(subs, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!subs.contains(sub)) {
            return List.of();
        }
        if (args.length == 2) {
            if (sub.equals("item")) {
                List<String> ids = new ArrayList<>(items.convertibleMaterials().size() + 1);
                ids.add("morphpotion");
                for (var material : items.convertibleMaterials()) {
                    ids.add(MasqBlocks.idOf(material));
                }
                for (EntityType type : EntityType.values()) {
                    Class<? extends Entity> clazz = type.getEntityClass();
                    if (clazz != null && Mob.class.isAssignableFrom(clazz)) {
                        ids.add(type.getKey().getKey());
                    }
                }
                // 已经写了 "furnace[" 时按 "[" 之前的部分过滤（补全只给基础 id）
                String typed = args[1];
                int bracket = typed.indexOf('[');
                return filter(ids, bracket >= 0 ? typed.substring(0, bracket) : typed);
            }
            if (sub.equals("clear")) {
                // 不给玩家名（避免遍历在线玩家）；选择器写法由客户端提示
                return filter(List.of("@a", "@p", "@s"), args[1]);
            }
        }
        if (args.length == 3 && sub.equals("item")) {
            return filter(List.of("1", "8", "16", "32", "64"), args[2]);
        }
        return List.of();
    }

    private static @NotNull List<String> filter(@NotNull List<String> options, @NotNull String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>(Math.min(options.size(), 64));
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(option);
                if (out.size() >= 64) {
                    break;
                }
            }
        }
        return out;
    }
}
