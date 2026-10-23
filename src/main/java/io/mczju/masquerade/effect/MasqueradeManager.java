package io.mczju.masquerade.effect;

import io.mczju.masquerade.MasqKeys;
import io.mczju.masquerade.MasqueradePlugin;
import io.mczju.masquerade.config.MasqConfig;
import io.mczju.masquerade.config.Messages;
import io.mczju.masquerade.item.ItemKind;
import io.mczju.masquerade.item.MasqItems;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.SmallDripleaf;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 易容效果的唯一执行处：**只有这里**取样、改 {@code Attribute.SCALE}、加/移除隐身、
 * spawn/跟随/移除展示实体。
 *
 * <p>回收链（{@code AGENTS.md} 铁律 14）：正常结束 / 退出 / 死亡 / 关服 / 重启兜底，五条全在本类里。</p>
 */
public final class MasqueradeManager implements Listener {

    /** 展示实体连续生成失败多少次就放弃（避免"缩小+隐身但没有方块"的半截状态）。 */
    private static final int DISPLAY_MAX_FAILURES = 5;
    /** 展示实体离玩家超过这个距离（平方，格²）就判定跟丢了 ⇒ 重 spawn。 */
    private static final double DISPLAY_MAX_DISTANCE_SQUARED = 16.0;
    /** 每多少 tick 做一次「到点 / 隐身 / 展示实体」检查（跟随是每 tick）。 */
    private static final int CHECK_INTERVAL_TICKS = 20;

    private final MasqueradePlugin plugin;
    private final MasqConfig config;
    private final MasqItems items;
    private final Messages messages;
    private final Map<UUID, MasqState> active = new LinkedHashMap<>();

    private @Nullable BukkitTask task;
    private int tickCounter;
    /** 生物附身管理器（构造顺序在后，由插件注入）；只用于「换形态前先结束另一种」。 */
    private @Nullable MobMasqueradeManager mobManager;

    public MasqueradeManager(
            @NotNull MasqueradePlugin plugin,
            @NotNull MasqConfig config,
            @NotNull MasqItems items
    ) {
        this.plugin = plugin;
        this.config = config;
        this.items = items;
        this.messages = config.messages();
    }

    /** 注入生物附身管理器（避免构造期循环依赖）。 */
    public void mobManager(@Nullable MobMasqueradeManager mobManager) {
        this.mobManager = mobManager;
    }

    public void start() {
        stopTask();
        // 每 tick 跟随（R13：不再当乘客）；内部每 CHECK_INTERVAL_TICKS 做一次状态检查
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** 关服 / 插件禁用：还原所有仍在线的玩家，并取消任务。 */
    public void shutdown() {
        stopTask();
        int cleared = 0;
        for (MasqState state : new ArrayList<>(active.values())) {
            Player player = Bukkit.getPlayer(state.playerId());
            if (player != null && player.isOnline()) {
                if (end(player, EndReason.SHUTDOWN, true)) {
                    cleared++;
                }
            } else {
                removeDisplay(state);
            }
        }
        active.clear();
        plugin.getLogger().info("已清除 " + cleared + " 名玩家的易容效果（关服）。");
    }

    public int activeCount() {
        return active.size();
    }

    public boolean isMasquerading(@NotNull Player player) {
        return active.containsKey(player.getUniqueId());
    }

    /** {@code /masq clear} / 换形态的入口：静默结束，由调用方统一发一条汇总消息。 */
    public boolean clear(@NotNull Player player) {
        return end(player, EndReason.COMMAND, true);
    }

    // === 取样（R11） ===

    @EventHandler(ignoreCancelled = true)
    public void onInteract(@NotNull PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                && event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        // ★ 组合键退出：蹲下 + 右键（方块/空气都算）；优先于取样，避免"想退出却扣了药水"
        if (isMasquerading(player) && sneakHeld(player)) {
            event.setCancelled(true);
            if (mobManager != null) {
                mobManager.end(player, "组合键", true);
            }
            end(player, EndReason.COMBO, false);
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!player.isSneaking()) {
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (items.kindOf(held).kind() != ItemKind.Kind.MORPH_POTION) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        if (items.hasSamplingCooldown(player)) {
            messages.send(player, "sample-cooldown", items.samplingCooldownRemainingSeconds(player));
            return;
        }
        BlockData state = clicked.getBlockData();
        if (!items.isConvertible(state)) {
            messages.send(player, "sample-invalid");
            return;
        }
        // 先产物后扣费：构建失败不扣药水
        Optional<ItemStack> result = items.masqueradeOf(state);
        if (result.isEmpty()) {
            messages.send(player, "effect-failed");
            return;
        }
        event.setCancelled(true);           // 取消这次方块交互（箱子/熔炉不打开）
        consumeOnePotion(player);
        items.applySamplingCooldown(player);
        int dropped = items.give(player, result.get(), 1);
        Component name = items.blockName(state.getMaterial());
        if (dropped > 0) {
            messages.send(player, "sample-dropped", name);
        } else {
            messages.send(player, "sample-success", name);
        }
        debug("取样：" + player.getName() + " / " + state.getAsString() + "（掉落 " + dropped + "）");
    }

    /** 方块形态下右键实体也要能退出（右键方块走 {@link #onInteract}）。 */
    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(@NotNull PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (!isMasquerading(player) || !sneakHeld(player)) {
            return;
        }
        event.setCancelled(true);
        if (mobManager != null) {
            mobManager.end(player, "组合键", true);
        }
        end(player, EndReason.COMBO, false);
    }

    /** 方块形态下按蹲：传送到自身方块中心（x/z 中心，保留脚底 Y）并把 yaw 矫正到 90° 倍数。 */
    @EventHandler(ignoreCancelled = true)
    public void onSneak(@NotNull PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) {
            return;
        }
        Player player = event.getPlayer();
        if (!isMasquerading(player) || !config.effect().snapOnSneak()) {
            return;
        }
        // 生物附身时蹲是下降/姿态键，不能在这里抢
        if (mobManager != null && mobManager.isActive(player)) {
            return;
        }
        // 载具上强行传送会下车，保持原样
        if (player.isInsideVehicle()) {
            return;
        }
        Location loc = player.getLocation();
        float snappedYaw = Math.round(loc.getYaw() / 90.0F) * 90.0F;
        snappedYaw = ((snappedYaw % 360.0F) + 360.0F) % 360.0F;
        Location target = new Location(
                loc.getWorld(),
                loc.getBlockX() + 0.5,
                loc.getY(),
                loc.getBlockZ() + 0.5,
                snappedYaw,
                loc.getPitch());
        player.teleport(target);
        debug("下蹲对齐：" + player.getName() + " → "
                + target.getX() + "," + target.getY() + "," + target.getZ()
                + " yaw=" + snappedYaw);
    }

    /**
     * 生物附身时 {@code PlayerToggleSneakEvent} 被取消，{@code isSneaking()} 可能一直是 false，
     * 但输入包里的 sneak 仍然可读（探针/相机方案已验证）。
     */
    private static boolean sneakHeld(@NotNull Player player) {
        return player.isSneaking() || player.getCurrentInput().isSneak();
    }

    private void consumeOnePotion(@NotNull Player player) {
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

    // === 喝下 ===

    @EventHandler(ignoreCancelled = true)
    public void onConsume(@NotNull PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        ItemKind.Classification kind = items.kindOf(event.getItem());
        switch (kind.kind()) {
            case NOT_OURS -> {
                // 普通物品，不插手
            }
            case MORPH_POTION -> {
                // 需求裁定 E4：原料药水不能喝（取消消耗并提示）
                event.setCancelled(true);
                messages.send(player, "morphpotion-not-drinkable");
            }
            case INVALID -> {
                event.setCancelled(true);
                messages.send(player, "effect-block-invalid");
            }
            case MASQUERADE_MOB -> {
                // 生物易容术由 MobMasqueradeManager 处理，这里不插手
            }
            case MASQUERADE -> {
                BlockData block = items.masqueradeBlock(kind).orElse(null);
                if (block == null) {
                    event.setCancelled(true);
                    messages.send(player, "effect-block-invalid");
                    return;
                }
                if (!apply(player, block)) {
                    event.setCancelled(true);
                    messages.send(player, "effect-failed");
                }
            }
        }
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        Player player = event.getPlayer();
        MasqState state = active.remove(player.getUniqueId());
        if (state != null) {
            // 玩家已离线 ⇒ 属性还原留给登录兜底（PDC 里记着原值），这里只收实体
            removeDisplay(state);
            debug("退出时移除展示实体：" + player.getName() + " / " + state.block().getAsString());
        }
    }

    @EventHandler
    public void onDeath(@NotNull PlayerDeathEvent event) {
        if (!config.effect().endOnDeath()) {
            return;
        }
        end(event.getEntity(), EndReason.DEATH, true);
    }

    @EventHandler
    public void onJoin(@NotNull PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.getPersistentDataContainer().get(MasqKeys.UNTIL, PersistentDataType.LONG) == null) {
            return;
        }
        // 延迟 1 tick：等玩家/世界状态就绪再 spawn 展示实体
        Bukkit.getScheduler().runTask(plugin, () -> resolveOnJoin(player));
    }

    // === 应用 / 结束 ===

    private boolean apply(@NotNull Player player, @NotNull BlockData block) {
        AttributeInstance scale = player.getAttribute(Attribute.SCALE);
        if (scale == null) {
            plugin.getLogger().warning("玩家 " + player.getName() + " 没有 Attribute.SCALE，易容取消。");
            return false;
        }
        // 先结束旧状态：保证下面读到的 baseValue 是"真正的原值"
        end(player, EndReason.REPLACED, true);
        // 反向互斥（Q9 只处理了「生物盖方块」；这里补「方块盖生物」，否则相机会留在傀儡身上）
        if (mobManager != null) {
            mobManager.end(player, "换成方块易容", true);
        }

        double original = scale.getBaseValue();
        long now = System.currentTimeMillis();
        long until = now + config.effect().durationSeconds() * 1000L;
        boolean giveInvisibility = config.effect().invisibility();
        PotionEffect previousInvisibility = giveInvisibility
                ? player.getPotionEffect(PotionEffectType.INVISIBILITY)
                : null;

        // 门/床/高花等两格方块 → 拆成上下（或床的头/脚）两个展示实体
        DisplayPlan plan = planDisplays(block);
        // ★ 归属证据先落盘（铁律 14：进程突然消失后，重启靠它还原/续上）
        writeStoredState(player, plan.primary(), until, original);

        DisplayIds displayIds = null;
        try {
            scale.setBaseValue(config.effect().scale());
            if (giveInvisibility) {
                player.addPotionEffect(new PotionEffect(
                        PotionEffectType.INVISIBILITY,
                        config.effect().durationSeconds() * 20,
                        0, false, false, true));
            }
            displayIds = spawnDisplays(player, plan);
            if (displayIds == null) {
                throw new IllegalStateException("展示实体生成失败");
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("为 " + player.getName() + " 应用易容失败：" + ex.getMessage());
            restoreScale(player, original);
            if (giveInvisibility) {
                player.removePotionEffect(PotionEffectType.INVISIBILITY);
                restorePreviousInvisibility(player, previousInvisibility, now);
            }
            clearStoredState(player);
            return false;
        }

        MasqState state = new MasqState(
                player.getUniqueId(), plan.primary(), plan.second(), plan.offset(),
                until, now, original, giveInvisibility, previousInvisibility);
        state.displayId(displayIds.primary());
        state.secondDisplayId(displayIds.second());
        active.put(player.getUniqueId(), state);

        messages.send(player, "effect-applied",
                items.blockName(plan.primary().getMaterial()), config.effect().durationSeconds());
        debug("已应用易容：" + player.getName() + " / " + plan.primary().getAsString() + " / 到 " + until);
        return true;
    }

    /** 结束一名玩家的易容（幂等：不在表里 ⇒ 返回 false，什么都不做）。 */
    public boolean end(@NotNull Player player, @NotNull EndReason reason, boolean silent) {
        MasqState state = active.remove(player.getUniqueId());
        if (state == null) {
            return false;
        }
        restoreScale(player, state.originalScale());
        if (state.gaveInvisibility()) {
            player.removePotionEffect(PotionEffectType.INVISIBILITY);
            restorePreviousInvisibility(player, state.previousInvisibility(), state.appliedAtMillis());
        }
        removeDisplay(state);
        clearStoredState(player);
        if (!silent) {
            messages.send(player, "effect-ended");
        }
        debug("易容结束（" + reason.label() + "）：" + player.getName() + " / " + state.block().getAsString());
        return true;
    }

    // === 每 tick 跟随 + 定期检查（事件 ∪ 状态） ===

    private void tick() {
        if (active.isEmpty()) {
            return;
        }
        boolean check = (++tickCounter % CHECK_INTERVAL_TICKS == 0);
        long now = System.currentTimeMillis();
        for (MasqState state : new ArrayList<>(active.values())) {
            Player player = Bukkit.getPlayer(state.playerId());
            if (player == null || !player.isOnline()) {
                removeDisplay(state);
                active.remove(state.playerId());
                continue;
            }
            follow(player, state);
            if (check) {
                checkState(player, state, now);
            }
        }
    }

    /**
     * 每 tick 把展示实体传送到玩家脚底，并在朝向变化时重建 {@code Transformation}（R13）。
     *
     * <p>★ 实体 yaw 恒为 0：方块模型在 {@code 0..1} 空间，居中补偿写在 Transformation 里；
     * 若用实体 yaw 转，补偿量会跟着转轴一起歪（2026-10-22 实机 bug）。</p>
     */
    private void follow(@NotNull Player player, @NotNull MasqState state) {
        float yaw = currentYaw(player);
        Location base = player.getLocation().clone();
        base.setYaw(0.0F);
        base.setPitch(0.0F);

        Display primary = displayOf(state, state.displayId());
        if (primary != null) {
            primary.teleport(base);
        }
        Display second = null;
        if (state.secondBlock() != null) {
            Display display = displayOf(state, state.secondDisplayId());
            if (display != null) {
                second = display;
                Location target = base.clone().add(rotateOffset(state.secondOffset(), yaw));
                display.teleport(target);
            }
        }

        if (Float.isNaN(state.lastAppliedYaw()) || Math.abs(yaw - state.lastAppliedYaw()) > 1.0F) {
            Transformation transformation = buildTransformation(yaw);
            if (primary != null) {
                primary.setTransformation(transformation);
            }
            if (second != null) {
                second.setTransformation(transformation);
            }
            state.lastAppliedYaw(yaw);
        }
    }

    /** 把第二格的基础偏移按展示 yaw 旋转（门的 +Y 不受影响，床的头会跟着转）。 */
    private static @NotNull Vector rotateOffset(@Nullable Vector base, float yaw) {
        if (base == null) {
            return new Vector();
        }
        float[] rotated = DisplayGeometry.rotateY(yaw, (float) base.getX(), (float) base.getZ());
        return new Vector(rotated[0], base.getY(), rotated[1]);
    }

    private float currentYaw(@NotNull Player player) {
        return config.display().syncRotation() ? player.getLocation().getYaw() : 0.0F;
    }

    /** 方块以玩家为中心、底面贴脚底、绕自身中心朝向 yaw。 */
    private @NotNull Transformation buildTransformation(float yaw) {
        float scale = (float) config.display().scale();
        float[] t = DisplayGeometry.translation(
                yaw, scale,
                (float) config.display().translationX(),
                (float) config.display().translationY(),
                (float) config.display().translationZ());
        return new Transformation(
                new Vector3f(t[0], t[1], t[2]),
                new Quaternionf().rotationY(DisplayGeometry.yawRadians(yaw)),
                new Vector3f(scale),
                new Quaternionf());
    }

    private void checkState(@NotNull Player player, @NotNull MasqState state, long now) {
        boolean hasInvisibility = player.getPotionEffect(PotionEffectType.INVISIBILITY) != null;
        boolean displayOk = isDisplayOk(player, state);
        MasqState.TickAction action = MasqState.decide(
                now, state.untilMillis(), hasInvisibility,
                config.effect().endWhenInvisibilityLost(), displayOk);
        switch (action) {
            case END_TIMEOUT -> {
                end(player, EndReason.TIMEOUT, false);
                return;
            }
            case END_INVISIBILITY_LOST -> {
                end(player, EndReason.INVISIBILITY_LOST, false);
                return;
            }
            case RESPAWN_DISPLAY -> respawnDisplay(player, state);
            case CONTINUE -> {
                // 继续
            }
        }
        AttributeInstance scale = player.getAttribute(Attribute.SCALE);
        if (scale != null && Math.abs(scale.getBaseValue() - config.effect().scale()) > 1.0E-4) {
            scale.setBaseValue(config.effect().scale());
        }
    }

    private void respawnDisplay(@NotNull Player player, @NotNull MasqState state) {
        removeDisplay(state);
        DisplayIds ids = spawnDisplays(player,
                new DisplayPlan(state.block(), state.secondBlock(), state.secondOffset()));
        if (ids == null) {
            int failures = state.incrementDisplayFailures();
            if (failures >= DISPLAY_MAX_FAILURES) {
                plugin.getLogger().warning("玩家 " + player.getName() + " 的展示实体连续 "
                        + failures + " 次生成失败，提前结束易容。");
                end(player, EndReason.TIMEOUT, false);
            }
            return;
        }
        state.resetDisplayFailures();
        state.displayId(ids.primary());
        state.secondDisplayId(ids.second());
        state.lastAppliedYaw(Float.NaN);   // 新实体 ⇒ 下一 tick 重写 Transformation
        debug("已重新生成展示实体：" + player.getName());
    }

    // === 重启兜底（登录时按 PDC 续上或还原） ===

    private void resolveOnJoin(@NotNull Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        Long until = pdc.get(MasqKeys.UNTIL, PersistentDataType.LONG);
        if (until == null) {
            return;
        }
        Double storedScale = pdc.get(MasqKeys.SCALE, PersistentDataType.DOUBLE);
        String blockRaw = pdc.get(MasqKeys.BLOCK, PersistentDataType.STRING);
        double original = storedScale == null ? 1.0 : storedScale;
        long now = System.currentTimeMillis();
        Optional<BlockData> block = items.parseBlockState(blockRaw);

        if (until > now && block.isPresent()) {
            resume(player, block.get(), until, original);
            return;
        }

        // 已过期 / 方块数据失效 ⇒ 还原并清 PDC（玩家不会永久 50%）
        restoreScale(player, original);
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        clearStoredState(player);
        messages.send(player, "effect-ended");
        debug("登录兜底：还原过期的易容状态 " + player.getName());
    }

    private void resume(@NotNull Player player, @NotNull BlockData block, long until, double original) {
        AttributeInstance scale = player.getAttribute(Attribute.SCALE);
        if (scale == null) {
            clearStoredState(player);
            return;
        }
        MasqState stale = active.remove(player.getUniqueId());
        if (stale != null) {
            removeDisplay(stale);
        }

        long now = System.currentTimeMillis();
        long remainingMillis = Math.max(0L, until - now);
        int remainingTicks = (int) Math.min(Integer.MAX_VALUE, remainingMillis / 50L);
        boolean giveInvisibility = config.effect().invisibility();

        scale.setBaseValue(config.effect().scale());
        if (giveInvisibility) {
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.INVISIBILITY, Math.max(1, remainingTicks), 0, false, false, true));
        }
        DisplayPlan plan = planDisplays(block);
        DisplayIds ids = spawnDisplays(player, plan);

        MasqState state = new MasqState(
                player.getUniqueId(), plan.primary(), plan.second(), plan.offset(),
                until, now, original, giveInvisibility, null);
        if (ids != null) {
            state.displayId(ids.primary());
            state.secondDisplayId(ids.second());
        }
        active.put(player.getUniqueId(), state);

        messages.send(player, "effect-applied",
                items.blockName(plan.primary().getMaterial()), (int) (remainingMillis / 1000L));
        debug("登录续上易容：" + player.getName() + " / " + plan.primary().getAsString());
    }

    // === 展示实体（只碰自己 spawn 的 UUID） ===

    private @Nullable DisplayIds spawnDisplays(@NotNull Player player, @NotNull DisplayPlan plan) {
        UUID primary = spawnDisplay(player.getLocation(), player, plan.primary());
        if (primary == null) {
            return null;
        }
        if (plan.second() == null || plan.offset() == null) {
            return new DisplayIds(primary, null);
        }
        Location secondAt = player.getLocation().clone()
                .add(rotateOffset(plan.offset(), currentYaw(player)));
        UUID second = spawnDisplay(secondAt, player, plan.second());
        if (second == null) {
            removeEntity(primary);
            return null;
        }
        return new DisplayIds(primary, second);
    }

    private @Nullable UUID spawnDisplay(@NotNull Location at, @NotNull Player player, @NotNull BlockData block) {
        try {
            BlockDisplay display = player.getWorld().spawn(at, BlockDisplay.class, entity -> {
                entity.setBlock(block);
                entity.setPersistent(false);           // ★ 不落盘 ⇒ 重启不留世界残留
                entity.setInvulnerable(true);
                entity.setBillboard(Display.Billboard.FIXED);
                entity.setViewRange((float) config.display().viewRange());
                entity.setTeleportDuration(config.display().teleportDuration());
                entity.setRotation(0.0F, 0.0F);        // 实体 yaw 恒 0，朝向交给 Transformation
                entity.setTransformation(buildTransformation(currentYaw(player)));
                entity.getPersistentDataContainer()
                        .set(MasqKeys.DISPLAY, PersistentDataType.STRING, "1");
            });
            if (display == null) {
                return null;
            }
            if (config.display().hideFromSelf()) {
                player.hideEntity(plugin, display);
            }
            return display.getUniqueId();
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("生成展示实体失败（" + player.getName() + " / "
                    + block.getAsString() + "）：" + ex.getMessage());
            return null;
        }
    }

    private @Nullable Display displayOf(@NotNull MasqState state, @Nullable UUID id) {
        if (id == null) {
            return null;
        }
        Entity entity = Bukkit.getEntity(id);
        // UUID 快照是主证据；PDC 再核一次，防止 UUID 被别的实体复用时去 teleport 别人
        if (entity == null || !entity.isValid() || !isOurDisplay(entity)
                || !(entity instanceof Display display)) {
            return null;
        }
        return display;
    }

    private static boolean isOurDisplay(@NotNull Entity entity) {
        return entity.getPersistentDataContainer().has(MasqKeys.DISPLAY, PersistentDataType.STRING);
    }

    private boolean isDisplayOk(@NotNull Player player, @NotNull MasqState state) {
        Display primary = displayOf(state, state.displayId());
        if (primary == null || !primary.getWorld().equals(player.getWorld())
                || primary.getLocation().distanceSquared(player.getLocation()) > DISPLAY_MAX_DISTANCE_SQUARED) {
            return false;
        }
        if (state.secondBlock() == null) {
            return true;
        }
        Display second = displayOf(state, state.secondDisplayId());
        if (second == null || !second.getWorld().equals(player.getWorld())) {
            return false;
        }
        Location expected = player.getLocation().clone()
                .add(rotateOffset(state.secondOffset(), currentYaw(player)));
        return second.getLocation().distanceSquared(expected) <= DISPLAY_MAX_DISTANCE_SQUARED;
    }

    private void removeDisplay(@NotNull MasqState state) {
        removeEntity(state.displayId());
        state.displayId(null);
        removeEntity(state.secondDisplayId());
        state.secondDisplayId(null);
    }

    private void removeEntity(@Nullable UUID id) {
        if (id == null) {
            return;
        }
        Entity entity = Bukkit.getEntity(id);
        if (entity == null) {
            return;
        }
        if (!isOurDisplay(entity)) {
            // 主证据仍是「本类 spawn 时记下的 UUID」；PDC 缺失只告警，不把它留在世界里变成残留
            plugin.getLogger().warning("展示实体 " + id + " 缺少 " + MasqKeys.DISPLAY
                    + " 标记，按内存 UUID 移除。");
        }
        entity.remove();
    }

    /**
     * 把方块拆成「主格 + 第二格」：
     * 门/高花 = 下 + 上；床 = 脚 + 头（水平偏移）；单格方块只有主格。
     */
    private static @NotNull DisplayPlan planDisplays(@NotNull BlockData block) {
        if (block instanceof Door door) {
            Door bottom = (Door) door.clone();
            bottom.setHalf(Bisected.Half.BOTTOM);
            Door top = (Door) door.clone();
            top.setHalf(Bisected.Half.TOP);
            return new DisplayPlan(bottom, top, new Vector(0, 1, 0));
        }
        if (block instanceof Bed bed) {
            Bed foot = (Bed) bed.clone();
            foot.setPart(Bed.Part.FOOT);
            Bed head = (Bed) bed.clone();
            head.setPart(Bed.Part.HEAD);
            return new DisplayPlan(foot, head, bed.getFacing().getDirection());
        }
        // 高花/大型蕨等：Bisected，但排除单格的活板门和小型垂滴叶
        if (block instanceof Bisected bisected
                && !(block instanceof TrapDoor)
                && !(block instanceof SmallDripleaf)) {
            Bisected bottom = (Bisected) bisected.clone();
            bottom.setHalf(Bisected.Half.BOTTOM);
            Bisected top = (Bisected) bisected.clone();
            top.setHalf(Bisected.Half.TOP);
            return new DisplayPlan(bottom, top, new Vector(0, 1, 0));
        }
        return new DisplayPlan(block, null, null);
    }

    private record DisplayPlan(@NotNull BlockData primary, @Nullable BlockData second,
                               @Nullable Vector offset) {
    }

    private record DisplayIds(@NotNull UUID primary, @Nullable UUID second) {
    }

    // === 玩家属性 / PDC ===

    private void restoreScale(@NotNull Player player, double original) {
        AttributeInstance scale = player.getAttribute(Attribute.SCALE);
        if (scale != null) {
            scale.setBaseValue(original);
        }
    }

    static void restorePreviousInvisibility(@NotNull Player player, @Nullable PotionEffect previous, long appliedAt) {
        if (previous == null) {
            return;
        }
        int remaining;
        if (previous.isInfinite()) {
            remaining = PotionEffect.INFINITE_DURATION;
        } else {
            long elapsedTicks = Math.max(0L, (System.currentTimeMillis() - appliedAt) / 50L);
            remaining = previous.getDuration() - (int) Math.min(Integer.MAX_VALUE, elapsedTicks);
            if (remaining <= 0) {
                return;
            }
        }
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.INVISIBILITY,
                remaining,
                previous.getAmplifier(),
                previous.isAmbient(),
                previous.hasParticles(),
                previous.hasIcon()));
    }

    private void writeStoredState(@NotNull Player player, @NotNull BlockData block, long until, double original) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        pdc.set(MasqKeys.UNTIL, PersistentDataType.LONG, until);
        pdc.set(MasqKeys.BLOCK, PersistentDataType.STRING, block.getAsString());
        pdc.set(MasqKeys.SCALE, PersistentDataType.DOUBLE, original);
    }

    private void clearStoredState(@NotNull Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        pdc.remove(MasqKeys.UNTIL);
        pdc.remove(MasqKeys.BLOCK);
        pdc.remove(MasqKeys.SCALE);
    }

    private void stopTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void debug(@NotNull String message) {
        if (config.debug()) {
            plugin.getLogger().info("[debug] " + message);
        }
    }
}
