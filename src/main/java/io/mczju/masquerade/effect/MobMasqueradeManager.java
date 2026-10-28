package io.mczju.masquerade.effect;

import com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent;
import io.mczju.masquerade.MasqKeys;
import io.mczju.masquerade.MasqueradePlugin;
import io.mczju.masquerade.config.MasqConfig;
import io.mczju.masquerade.config.Messages;
import io.mczju.masquerade.config.MobBlacklist;
import io.mczju.masquerade.item.ItemKind;
import io.mczju.masquerade.item.MasqItems;
import io.mczju.masquerade.nms.MasqNms;
import net.kyori.adventure.text.Component;
import com.destroystokyo.paper.entity.RangedEntity;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.Particle;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Armadillo;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BreezeWindCharge;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.EnderDragonPart;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.entity.Fox;
import org.bukkit.entity.Goat;
import org.bukkit.entity.Hoglin;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.LlamaSpit;
import org.bukkit.entity.MagmaCube;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Panda;
import org.bukkit.entity.PiglinAbstract;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.PufferFish;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.Sittable;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Sniffer;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Witch;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 生物易容：取样 / 喝下 / drive 附身 / 左右键拦截 / 回收（docs/10）。
 *
 * <p>★ 只碰触发者本人 + 本插件生成的一只傀儡（内存 UUID + PDC 标记）；傀儡
 * {@code setPersistent(false)}；退出/死亡/切世界/关服全部还原（铁律 10 / 14）。</p>
 */
public final class MobMasqueradeManager implements Listener {

    /** A/D 每 tick 转多少度（真机手感值）。 */
    private static final float TURN_PER_TICK = 10.0f;
    /** 左/右键效果的最小间隔（防止一次点击产生两个事件）。 */
    private static final long EFFECT_COOLDOWN_MILLIS = 400L;
    /** 近战/左键可及距离（格）。 */
    private static final double LEFT_REACH = 3.0;
    /** 飞行/游泳生物的 Ctrl 冲刺倍率。 */
    private static final float SPRINT_MULTIPLIER = 1.3f;

    private static final Set<EntityType> FLYERS = EnumSet.of(
            EntityType.BAT, EntityType.BEE, EntityType.PARROT, EntityType.ALLAY,
            EntityType.PHANTOM, EntityType.GHAST, EntityType.HAPPY_GHAST,
            EntityType.BLAZE, EntityType.VEX, EntityType.ENDER_DRAGON, EntityType.WITHER);

    /** 2026-10-23 用户：末影龙不能动、凋灵很卡 ⇒ 两个 Boss 先移除。 */
    private static final Set<EntityType> REMOVED_BOSSES = EnumSet.of(EntityType.ENDER_DRAGON, EntityType.WITHER);

    /** 狐狸已按用户要求降级为睡觉 toggle，不再走 goal AI 接管。 */
    private static final Set<EntityType> AI_GOAL_SKILLS = EnumSet.noneOf(EntityType.class);
    /** 山羊是 brain 生物（goals 0，冲撞在 brain 的 RamGoal），需要保留 brain。 */
    private static final Set<EntityType> AI_BRAIN_SKILLS = EnumSet.of(EntityType.GOAT);
    /** 关 AI + 传送驱动的生物：蝙蝠（随机飞行）、监守者（brain 自动音波/穿地）。 */
    private static final Set<EntityType> TELEPORT_DRIVE = EnumSet.of(EntityType.BAT, EntityType.WARDEN);
    /** 下蹲键切换姿态的生物：狐狸=蹲伏，狼/猫=坐下。鹦鹉改左键（否则下蹲不能下降）。 */
    private static final Set<EntityType> POSE_ANIMALS = EnumSet.of(
            EntityType.FOX, EntityType.WOLF, EntityType.CAT);

    private final MasqueradePlugin plugin;
    private final MasqConfig config;
    private final MasqItems items;
    private final MasqueradeManager blockManager;
    private final Messages messages;
    private final Map<UUID, MobState> active = new LinkedHashMap<>();
    private final Map<UUID, Long> lastEffect = new LinkedHashMap<>();
    /** 只在这只傀儡被 {@code doHurtTarget} 的瞬间抑制伤害（山羊 ram 等原版技能不受影响）。 */
    private final Set<UUID> suppressDamage = new HashSet<>();
    /** 已鼓起的河豚傀儡（它的 tick 会把 puffState 压回去，需要每 tick 维持）。 */
    private final Set<UUID> puffed = new HashSet<>();
    /** 拉弓的显式状态（不能读实体 flag，会被实体 tick 重置）。 */
    private final Set<UUID> chargingToggled = new HashSet<>();
    /** 正在长 AI 接管窗口里的傀儡（窗口内不读玩家输入）。 */
    private final Set<UUID> aiWindow = new HashSet<>();
    /** goal 生物在清空前保存的 goals（技能窗口恢复）。 */
    private final Map<UUID, List<MasqNms.GoalEntry>> savedGoals = new HashMap<>();
    /** 狐狸睡觉 toggle。 */
    private final Set<UUID> foxSleeping = new HashSet<>();
    /** 狐狸蹲伏 / 狼猫坐下 toggle。 */
    private final Set<UUID> poseToggled = new HashSet<>();
    /** 嗅探兽左键内置 8 秒 CD（与动画同长，防重复触发显示 bug）。 */
    private final Map<UUID, Long> snifferCooldown = new HashMap<>();
    /** 监守者右键音波 3 秒内置 CD。 */
    private final Map<UUID, Long> wardenSonicCooldown = new HashMap<>();

    private @Nullable BukkitTask task;

    public MobMasqueradeManager(@NotNull MasqueradePlugin plugin, @NotNull MasqConfig config,
                                @NotNull MasqItems items, @NotNull MasqueradeManager blockManager) {
        this.plugin = plugin;
        this.config = config;
        this.items = items;
        this.blockManager = blockManager;
        this.messages = config.messages();
    }

    /**
     * ★ 不在 onEnable 时调度：启动时 active 为空，tick 第一拍就会自取消，
     * 导致之后喝下药水也永远没有 drive（2026-10-22 实机 bug）。
     * 改为第一次附身时 {@link #ensureTask()}。
     */
    public void start() {
        // no-op：任务在第一次附身时启动
    }

    private void ensureTask() {
        if (task == null) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        }
    }

    public boolean isActive(@NotNull Player player) {
        return active.containsKey(player.getUniqueId());
    }

    /**
     * 2026-10-29 生物黑名单：命中的生物**不能取样、不能 `/masq item`、已有的对应易容术也不能喝**。
     * 判据在纯函数 {@link MobBlacklist#contains}（大小写 / `minecraft:` 前缀已规范化）。
     */
    private boolean isBlacklisted(@NotNull EntityType type) {
        // UNKNOWN 没有 key：getKey() 会抛 IllegalArgumentException；它也不可能是可取样/可饮用的生物
        if (type == EntityType.UNKNOWN) {
            return false;
        }
        return MobBlacklist.contains(config.items().mobBlacklist(), type.getKey().getKey());
    }

    public void shutdown() {
        stopTask();
        for (UUID id : new ArrayList<>(active.keySet())) {
            MobState state = active.remove(id);
            if (state == null) {
                continue;
            }
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                cleanup(player, state, false);
            } else {
                removePuppet(state);
            }
        }
        active.clear();
        lastEffect.clear();
        plugin.getLogger().info("[mob] 关服清理完成。");
    }

    // === 取样（潜行 + 主手药水 + 右键生物） ===

    @EventHandler(ignoreCancelled = true)
    public void onSample(@NotNull PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (isActive(player) || !player.isSneaking()) {
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (items.kindOf(held).kind() != ItemKind.Kind.MORPH_POTION) {
            return;
        }
        Entity target = event.getRightClicked();
        // 末影龙右键会命中 EnderDragonPart（不是 Mob）⇒ 映射回本体
        if (target instanceof EnderDragonPart part) {
            target = part.getParent();
        }
        if (target != null && REMOVED_BOSSES.contains(target.getType())) {
            messages.send(player, "mob-sample-invalid");
            return;
        }
        if (!(target instanceof Mob mob)) {
            messages.send(player, "mob-sample-invalid");
            return;
        }
        // 2026-10-29 生物黑名单：不允许取样的生物，直接提示、不扣药水、不进冷却
        if (isBlacklisted(mob.getType())) {
            messages.send(player, "mob-blacklisted", items.mobName(mob.getType()));
            return;
        }
        if (items.hasSamplingCooldown(player)) {
            messages.send(player, "sample-cooldown", items.samplingCooldownRemainingSeconds(player));
            return;
        }
        EntitySnapshot snapshot;
        try {
            snapshot = mob.createSnapshot();
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("[mob] 取样快照失败：" + ex);
            messages.send(player, "effect-failed");
            return;
        }
        if (snapshot == null) {
            messages.send(player, "effect-failed");
            return;
        }
        Optional<ItemStack> result = items.masqueradeOfMob(snapshot.getAsString(), mob.getType());
        if (result.isEmpty()) {
            messages.send(player, "effect-failed");
            return;
        }
        event.setCancelled(true);
        items.consumeOnePotion(player);
        items.applySamplingCooldown(player);
        int dropped = items.give(player, result.get(), 1);
        Component name = items.mobName(mob.getType());
        if (dropped > 0) {
            messages.send(player, "sample-dropped", name);
        } else {
            messages.send(player, "sample-success", name);
        }
        plugin.getLogger().info("[mob] 取样 " + player.getName() + " → " + mob.getType()
                + "（" + snapshot.getAsString().length() + " 字节 SNBT）");
    }

    // === 喝下 ===

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(@NotNull PlayerItemConsumeEvent event) {
        ItemKind.Classification kind = items.kindOf(event.getItem());
        if (!kind.isMob()) {
            return;
        }
        Player player = event.getPlayer();
        if (!apply(player, event.getItem())) {
            event.setCancelled(true);
        }
    }

    private boolean apply(@NotNull Player player, @NotNull ItemStack item) {
        ItemKind.Classification kind = items.kindOf(item);
        Optional<EntityType> type = items.mobTypeOf(kind);
        String snapshot = items.mobSnapshotOf(item);
        if (type.isEmpty() || snapshot == null || snapshot.isBlank()) {
            messages.send(player, "mob-invalid");
            return false;
        }
        // 2026-10-29 生物黑名单：已有的对应易容术也不能喝（否则旧物品可以绕过黑名单）
        if (isBlacklisted(type.get())) {
            messages.send(player, "mob-blacklisted", items.mobName(type.get()));
            return false;
        }
        if (REMOVED_BOSSES.contains(type.get())) {
            messages.send(player, "mob-invalid");
            return false;
        }
        if (type.get().getEntityClass() == null
                || !Mob.class.isAssignableFrom(type.get().getEntityClass())) {
            messages.send(player, "mob-invalid");
            return false;
        }

        // Q9：先结束方块易容，避免 scale / 隐身 / 展示实体打架
        if (blockManager.isMasquerading(player)) {
            blockManager.clear(player);
        }
        end(player, "重新开始");

        EntitySnapshot snapshotObject;
        Entity spawned;
        try {
            snapshotObject = MasqNms.snapshotFrom(snapshot, type.get());
            spawned = MasqNms.createFromSnapshot(snapshotObject, player.getLocation());
        } catch (Throwable error) {
            plugin.getLogger().warning("[mob] 还原快照失败：" + error);
            messages.send(player, "effect-failed");
            return false;
        }
        if (!(spawned instanceof Mob mob)) {
            spawned.remove();
            messages.send(player, "mob-invalid");
            return false;
        }

        // 只记「Bukkit 隐身 flag」本身；药水隐身由服务端自己管理，不能把 flag 一起钉死
        boolean wasInvisible = player.isInvisible()
                && player.getPotionEffect(PotionEffectType.INVISIBILITY) == null;
        // 易容术本身是 3 分钟隐身药水：记下喝之前的隐身效果，退出时还原（否则会残留药水隐身）
        PotionEffect previousInvisibility = player.getPotionEffect(PotionEffectType.INVISIBILITY);
        long appliedAt = System.currentTimeMillis();
        try {
            normalizePuppet(mob);
            boolean goalSkill = AI_GOAL_SKILLS.contains(type.get());
            boolean brainSkill = AI_BRAIN_SKILLS.contains(type.get());
            List<MasqNms.GoalEntry> goals = goalSkill ? MasqNms.captureGoals(mob) : List.of();
            int goalsBefore = MasqNms.goalCount(mob);
            boolean melee = MasqNms.hasMeleeGoal(mob);
            MasqNms.clearGoals(mob);
            // 山羊是 brain 生物：清 brain 会把 RamGoal 一起清掉 ⇒ 保留 brain，常态关 AI + 传送
            boolean brainCleared = brainSkill ? false : MasqNms.clearBrain(mob);
            int goalsAfter = MasqNms.goalCount(mob);
            if (goalSkill) {
                savedGoals.put(mob.getUniqueId(), goals);
            }
            plugin.getLogger().info("[mob] 傀儡 " + type.get() + " goals " + goalsBefore + "→" + goalsAfter
                    + "，brain 清理=" + brainCleared + "，goalSkill=" + goalSkill
                    + "，brainSkill=" + brainSkill + "，近战 goal=" + melee);
            // 蝙蝠随机飞行 / 监守者自动音波；山羊 brain 技能 ⇒ 关 AI + 传送；Boss 已移除
            boolean teleportDrive = TELEPORT_DRIVE.contains(type.get()) || brainSkill;
            if (teleportDrive) {
                mob.setAI(false);
            }
            float yaw = player.getLocation().getYaw();
            MasqNms.turn(mob, yaw);
            mob.teleport(player.getLocation());

            // ★ 归属证据先落盘：进程被 kill -9 后，登录兜底靠这两个标记还原隐身 flag
            player.getPersistentDataContainer().set(MasqKeys.MOB_ACTIVE,
                    PersistentDataType.BYTE, (byte) 1);
            player.getPersistentDataContainer().set(MasqKeys.MOB_WAS_INVISIBLE,
                    PersistentDataType.BYTE, (byte) (wasInvisible ? 1 : 0));
            player.setInvisible(true);
            hideFromOthers(player);
            MasqNms.setCamera(player, mob);
            if (!isCameraOn(player, mob)) {
                throw new IllegalStateException("相机没挂上（可能被其他插件取消）");
            }
            MobState state = new MobState(
                    mob.getUniqueId(), player.getWorld().getName(), type.get(),
                    appliedAt + config.effect().durationSeconds() * 1000L,
                    appliedAt, previousInvisibility,
                    speedOf(mob), melee, FLYERS.contains(type.get()), teleportDrive, wasInvisible, yaw);
            active.put(player.getUniqueId(), state);
            ensureTask();
            messages.send(player, "effect-applied", items.mobName(type.get()),
                    config.effect().durationSeconds());
            plugin.getLogger().info("[mob] 附身 " + player.getName() + " → " + type.get()
                    + "（goals " + goalsBefore + "→" + goalsAfter + "，brain=" + brainCleared
                    + "，近战 goal=" + melee + "，剩余 " + active.size() + " 个）");
            return true;
        } catch (Throwable error) {
            forgetPuppet(mob.getUniqueId());
            mob.remove();
            try {
                MasqNms.setCamera(player, null);
            } catch (Throwable ignored) {
                // 还原失败不能把玩家卡在相机里
            }
            player.setInvisible(wasInvisible);
            player.getPersistentDataContainer().remove(MasqKeys.MOB_ACTIVE);
            player.getPersistentDataContainer().remove(MasqKeys.MOB_WAS_INVISIBLE);
            showToAll(player);
            plugin.getLogger().warning("[mob] 附身失败：" + error);
            messages.send(player, "effect-failed");
            return false;
        }
    }

    private void normalizePuppet(@NotNull Mob mob) {
        mob.setPersistent(false);
        mob.setInvulnerable(true);
        mob.setCollidable(false);
        mob.setSilent(false);
        mob.setGlowing(false);
        mob.setCustomName(null);
        mob.setCustomNameVisible(false);
        mob.setAI(true);
        // 快照里的“坐着/睡觉/蜷着/趴着”姿态必须清掉，否则傀儡会一直保持（2026-10-23 用户：坐着的狐狸）
        if (mob instanceof Sittable sittable) {
            sittable.setSitting(false);
        }
        if (mob instanceof Fox fox) {
            fox.setCrouching(false);
            fox.setLeaping(false);
            fox.setSleeping(false);
            fox.setFaceplanted(false);
            fox.setInterested(false);
            fox.setDefending(false);
        }
        // 主世界僵尸化：疣猪兽/猪灵/猪灵蛮兵（2026-10-23 用户反馈；IG 同款阻断）
        if (mob instanceof Hoglin hoglin) {
            hoglin.setImmuneToZombification(true);
        }
        if (mob instanceof PiglinAbstract piglin) {
            piglin.setImmuneToZombification(true);
            piglin.setConversionTime(0);
        }
        mob.getPersistentDataContainer().set(MasqKeys.MOB_PUPPET, PersistentDataType.BYTE, (byte) 1);
        AttributeInstance maxHealth = mob.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null && maxHealth.getValue() > 0) {
            mob.setHealth(maxHealth.getValue());
        }
        mob.setVelocity(new Vector(0, 0, 0));
    }

    private static float speedOf(@NotNull Mob mob) {
        AttributeInstance attribute = mob.getAttribute(Attribute.MOVEMENT_SPEED);
        if (attribute == null) {
            return 0.3f;
        }
        return (float) Math.max(0.1, attribute.getValue());
    }

    // === 每 tick：drive + 3 分钟 + 相机/世界校验 ===

    private void tick() {
        if (active.isEmpty()) {
            stopTask();
            return;
        }
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, MobState>> it = active.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, MobState> entry = it.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            MobState state = entry.getValue();
            if (player == null || !player.isOnline()) {
                it.remove();
                lastEffect.remove(entry.getKey());
                removePuppet(state);
                continue;
            }
            if (!player.getWorld().getName().equals(state.worldName)) {
                it.remove();
                cleanup(player, state, false);
                messages.send(player, "mob-world-changed");
                continue;
            }
            Entity puppet = plugin.getServer().getEntity(state.puppetId);
            if (!(puppet instanceof Mob mob) || mob.isDead() || !mob.isValid() || !isOurPuppet(mob)) {
                it.remove();
                cleanup(player, state, false);
                messages.send(player, "mob-puppet-lost");
                continue;
            }
            if (now > state.untilMillis) {
                it.remove();
                cleanup(player, state, true);
                continue;
            }
            if (!isCameraOn(player, mob)) {
                it.remove();
                cleanup(player, state, false);
                messages.send(player, "mob-camera-lost");
                continue;
            }
            try {
                // 下蹲只做下降键：清掉服务端 shiftKeyDown，阻止原版「下蹲脱离相机」
                MasqNms.clearShift(player);
                if (aiWindow.contains(mob.getUniqueId())) {
                    // 长 AI 接管窗口：完全交给原版 AI，不读玩家输入
                    continue;
                }
                org.bukkit.Input input = player.getCurrentInput();
                float right = (input.isRight() ? 1 : 0) - (input.isLeft() ? 1 : 0);
                float forward = (input.isForward() ? 1 : 0) - (input.isBackward() ? 1 : 0);
                float yaw = (state.yaw + right * TURN_PER_TICK) % 360.0f;
                if (yaw < 0) {
                    yaw += 360.0f;
                }
                state.yaw = yaw;
                MasqNms.turn(mob, yaw);
                boolean jumpInput = input.isJump();
                boolean jumpPressed = jumpInput && !state.jumpHeld;
                state.jumpHeld = jumpInput;
                boolean sneakInput = input.isSneak();
                if (sneakInput && !state.sneakHeld && POSE_ANIMALS.contains(state.type)) {
                    togglePose(mob, state);
                }
                state.sneakHeld = sneakInput;
                boolean poseAnimal = POSE_ANIMALS.contains(state.type);
                boolean down = sneakInput && !poseAnimal;
                boolean jump = jumpInput && (mob.isOnGround() || state.flying);
                float speed = state.speed * (input.isSprint() ? SPRINT_MULTIPLIER : 1.0f);
                if (state.type == EntityType.STRIDER && onLava(mob)) {
                    // 原版炽足兽在岩浆上更快；这里给一个加速倍率（用户 2026-10-22 反馈不会加速）
                    speed *= 1.5f;
                }
                if (state.teleportDrive) {
                    // 地面传送驱动只认「刚按下」的跳跃边沿，否则按住空格会一直往上飞（山羊 2026-10-23）
                    teleportStep(mob, yaw, forward, state.flying ? jumpInput : jumpPressed, down, speed, state.flying);
                } else {
                    Vector desired = MasqNms.driveVector(
                            mob.getVelocity().getY(), yaw, forward, jump, down, speed, state.flying);
                    // ★ 2026-10-29 用户收窄后的唯一限制：只对恼鬼（Vex）禁止钻进实心方块。
                    //   其他生物、其他移动方式、射线、传送全部保持原样（跳跃/末影人/监守者音波不受影响）。
                    mob.setVelocity(state.type == EntityType.VEX ? vexVelocity(mob, desired) : desired);
                }
                if (puffed.contains(mob.getUniqueId()) && mob instanceof PufferFish puffer) {
                    puffer.setPuffState(2);
                    poisonNearby(puffer);
                }
                if (state.type == EntityType.FOX && mob instanceof Fox fox
                        && poseToggled.contains(mob.getUniqueId())) {
                    // 狐狸的 crouch 数据会被它自己的 tick 抹掉 ⇒ 每 tick 重新压回去
                    fox.setCrouching(true);
                }
            } catch (Throwable error) {
                it.remove();
                cleanup(player, state, false);
                plugin.getLogger().warning("[mob] drive 失败：" + error);
                messages.send(player, "mob-drive-failed");
            }
        }
        if (active.isEmpty()) {
            stopTask();
        }
    }

    /** 下蹲键切换姿态：狐狸蹲伏；狼/猫坐下。 */
    private void togglePose(@NotNull Mob mob, @NotNull MobState state) {
        UUID id = mob.getUniqueId();
        if (state.type == EntityType.FOX && mob instanceof Fox fox) {
            if (poseToggled.remove(id)) {
                fox.setCrouching(false);
            } else {
                // 睡觉/蹲伏互斥：按蹲伏时先醒
                fox.setSleeping(false);
                foxSleeping.remove(id);
                fox.setCrouching(true);
                poseToggled.add(id);
            }
            return;
        }
        if (mob instanceof Sittable sittable) {
            if (poseToggled.remove(id)) {
                sittable.setSitting(false);
            } else {
                sittable.setSitting(true);
                poseToggled.add(id);
            }
        }
    }

    /** 关 AI 后的传送驱动（蝙蝠=飞行；brain 技能的常态驱动=地面，带简单重力）。 */
    private void teleportStep(@NotNull Mob mob, float yaw, float forward, boolean jump, boolean down,
                              float speed, boolean flying) {
        double step = Math.max(0.2, speed);
        double radians = Math.toRadians(yaw);
        double dx = -Math.sin(radians) * step * forward;
        double dz = Math.cos(radians) * step * forward;
        double dy;
        if (jump) {
            dy = 0.2;
        } else if (down) {
            dy = -0.2;
        } else if (flying) {
            dy = 0.0;
        } else {
            // teleport 不会更新 isOnGround；用脚下方块判断，否则跳一次后会一直悬空（山羊会飞）
            boolean onGround = !mob.getLocation().clone().subtract(0, 0.05, 0).getBlock().isPassable();
            dy = onGround ? 0.0 : -0.08;
        }
        Location next = mob.getLocation().clone().add(dx, dy, dz);
        next.setYaw(yaw);
        next.setPitch(0.0f);
        if (next.getBlock().isPassable()) {
            mob.teleport(next);
            return;
        }
        // ★ 只允许“向上”的备用位移；向下的备用会把生物传进脚下方块里（监守者下蹲穿地）
        if (dy > 0.0) {
            Location up = mob.getLocation().clone().add(0, dy, 0);
            up.setYaw(yaw);
            up.setPitch(0.0f);
            if (up.getBlock().isPassable()) {
                mob.teleport(up);
            }
        }
    }

    /**
     * ★ 2026-10-29 用户收窄后的**唯一**穿墙限制：恼鬼（Vex）不许钻进实心方块。
     *
     * <p>起因：恼鬼原版 {@code Vex#tick} 在 {@code super.tick()} 期间把 {@code noPhysics} 置 true
     * （{@code javap} 已核），玩家变身恼鬼后可以**大范围、远距离**穿过地形，钻进服务器地下放命令方块
     * 的房间。用户明确：只限制这一条，不要动其他生物的正常玩法。</p>
     *
     * <p>做法：按恼鬼**自己的碰撞箱**把这一 tick 的位移逐段采样（{@link WallGuard#SAMPLE_STEP}），
     * 任意一段会进实心方块 ⇒ 本 tick 速度归零（停在墙前）。恼鬼本来就悬空、无重力，
     * 速度就是它唯一的位移来源，所以预检足够，不需要每 tick 回滚。</p>
     *
     * <p>★ 不校验玩家本体、不校验其他生物、不改传送/射线：那些都是上一次过宽修复的错误，
     * 已全部回退（见 {@code dev-log/2026-10-29-040}）。</p>
     */
    private @NotNull Vector vexVelocity(@NotNull Mob mob, @NotNull Vector desired) {
        Location from = mob.getLocation();
        if (!bodyClear(mob, from)) {
            // 已经卡在方块里（极端情况）：允许往外飞，否则永远出不来
            return desired;
        }
        for (double[] point : WallGuard.sweep(from.getX(), from.getY(), from.getZ(),
                from.getX() + desired.getX(), from.getY() + desired.getY(), from.getZ() + desired.getZ(),
                WallGuard.SAMPLE_STEP)) {
            if (!bodyClear(mob, new Location(from.getWorld(), point[0], point[1], point[2]))) {
                return new Vector(0, 0, 0);
            }
        }
        return desired;
    }

    /** 恼鬼自己的身体在这个脚底位置是否不与方块碰撞（水面/草等可穿过方块不算）。 */
    private static boolean bodyClear(@NotNull Mob mob, @NotNull Location feet) {
        World world = feet.getWorld();
        return world != null && WallGuard.clear(world, WallGuard.box(
                feet.getX(), feet.getY(), feet.getZ(), mob.getWidth(), mob.getHeight()));
    }

    private static boolean onLava(@NotNull Entity entity) {
        Location location = entity.getLocation();
        return location.getBlock().getType() == Material.LAVA
                || location.clone().subtract(0, 0.2, 0).getBlock().getType() == Material.LAVA;
    }

    private static void poisonNearby(@NotNull PufferFish puffer) {
        for (LivingEntity living : puffer.getWorld().getNearbyLivingEntities(
                puffer.getLocation(), 0.9, 0.9, 0.9,
                entity -> entity != puffer && !(entity instanceof ArmorStand))) {
            living.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 0));
        }
    }

    private static boolean isCameraOn(@NotNull Player player, @NotNull Entity mob) {
        Entity target = player.getSpectatorTarget();
        return target != null && target.getUniqueId().equals(mob.getUniqueId());
    }

    // === 左/右键 ===

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLeftAnimation(@NotNull PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }
        Player player = event.getPlayer();
        MobState state = active.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        event.setCancelled(true);
        onLeft(player, state);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onRightClick(@NotNull PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        MobState state = active.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (sneakHeld(player)) {
            blockManager.clear(player);  // 理论上互斥；保险起见把方块形态也收掉
            end(player, "组合键", false);
            return;
        }
        onRight(player, state);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onRightEntity(@NotNull PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        MobState state = active.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (sneakHeld(player)) {
            blockManager.clear(player);
            end(player, "组合键", false);
            return;
        }
        onRight(player, state);
    }

    /** 生物附身时 sneak 事件被取消，必须同时读输入包。 */
    private static boolean sneakHeld(@NotNull Player player) {
        return player.isSneaking() || player.getCurrentInput().isSneak();
    }

    private void onLeft(@NotNull Player player, @NotNull MobState state) {
        if (!effectReady(player)) {
            return;
        }
        // 用户 2026-10-23 降级：苦力怕左键不绑定任何东西
        if (state.type == EntityType.CREEPER) {
            return;
        }
        Entity puppet = plugin.getServer().getEntity(state.puppetId);
        if (!(puppet instanceof Mob mob)) {
            return;
        }
        LivingEntity target = rayTarget(player, mob);
        if (specialLeft(player, state, mob, target)) {
            if (config.debug()) {
                plugin.getLogger().info("[debug] 专属左键 " + state.type
                        + (target == null ? "（无目标）" : " → " + target.getType()));
            }
            return;
        }
        // 用户 2026-10-23 降级：默认左键恢复成「仅击退」，不再尝试攻击动画
        // 监守者 goals=0（brain 生物），但它左键应该有反馈 ⇒ 单独放行击退
        if ((state.hasMeleeGoal || state.type == EntityType.WARDEN) && target != null) {
            knockback(mob, target, state.yaw);
        }
        if (config.debug()) {
            plugin.getLogger().info("[debug] 默认左键 " + state.type
                    + (target == null ? "（无目标）" : " → " + target.getType()));
        }
    }

    /** 用户 2026-10-22 点名的专属左键（docs/10 §11.3）；返回 true = 已处理。 */
    private boolean specialLeft(@NotNull Player player, @NotNull MobState state, @NotNull Mob mob,
                                @Nullable LivingEntity target) {
        World world = mob.getWorld();
        switch (state.type) {
            case CHICKEN -> {
                Material egg = Material.EGG;
                if (mob instanceof Chicken chicken) {
                    String variant = chicken.getVariant().getKey().getKey();
                    egg = switch (variant) {
                        case "cold" -> Material.BLUE_EGG;
                        case "warm" -> Material.BROWN_EGG;
                        default -> Material.EGG;
                    };
                }
                world.dropItem(mob.getLocation(), new ItemStack(egg));
                world.playSound(mob.getLocation(), Sound.ENTITY_CHICKEN_EGG, 1.0f, 1.0f);
                return true;
            }
            case FOX -> {
                // 用户 2026-10-23：狐狸降级为「左键睡觉 / 再按醒」toggle
                if (mob instanceof Fox fox) {
                    UUID id = mob.getUniqueId();
                    if (foxSleeping.remove(id)) {
                        fox.setSleeping(false);
                    } else {
                        fox.setSleeping(true);
                        foxSleeping.add(id);
                    }
                }
                return true;
            }
            case PUFFERFISH -> {
                if (mob instanceof PufferFish puffer) {
                    UUID id = mob.getUniqueId();
                    if (puffed.remove(id)) {
                        puffer.setPuffState(0);
                    } else {
                        puffer.setPuffState(2);
                        puffed.add(id);
                    }
                }
                return true;
            }
            case DOLPHIN -> {
                for (Player near : world.getNearbyPlayers(mob.getLocation(), 12.0)) {
                    near.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE, 200, 0));
                }
                return true;
            }
            case HORSE, DONKEY, MULE -> {
                if (mob instanceof AbstractHorse horse) {
                    horse.setRearing(true);
                    world.playSound(mob.getLocation(), Sound.ENTITY_HORSE_ANGRY, 1.0f, 1.0f);
                    later(20, () -> {
                        if (horse.isValid()) {
                            horse.setRearing(false);
                        }
                    });
                }
                return true;
            }
            case CAMEL -> {
                if (mob instanceof Sittable sittable) {
                    sittable.setSitting(!sittable.isSitting());
                }
                return true;
            }
            case PANDA -> {
                if (mob instanceof Panda panda) {
                    panda.setRolling(true);
                    later(20, () -> {
                        if (panda.isValid()) {
                            panda.setRolling(false);
                        }
                    });
                }
                return true;
            }
            case GOAT -> {
                // 山羊的 RamGoal 在 brain 里（log 实测 goals=0）⇒ 必须交给原版 AI 窗口 + 清 RAM_COOLDOWN_TICKS
                if (mob instanceof Goat goat) {
                    LivingEntity nearest = nearestTarget(player, mob, 16.0);
                    if (nearest != null) {
                        goat.setMemory(org.bukkit.entity.memory.MemoryKey.RAM_COOLDOWN_TICKS, 0);
                        startAiWindow(mob, state, 30, nearest);
                        goat.ram(nearest);
                    }
                }
                return true;
            }
            case EVOKER -> {
                MasqNms.evokerCast(mob, true);
                later(20, () -> {
                    if (mob.isValid()) {
                        MasqNms.evokerCast(mob, false);
                    }
                });
                summonFangs(mob);
                return true;
            }
            case WITCH -> {
                if (mob instanceof Witch witch) {
                    witch.setDrinkingPotion(poisonPotion());
                    witch.setPotionUseTimeLeft(20);
                    world.playSound(mob.getLocation(), Sound.ENTITY_WITCH_DRINK, 1.0f, 1.0f);
                }
                return true;
            }

            case SKELETON, STRAY, BOGGED, PARCHED -> {
                if (mob instanceof RangedEntity ranged) {
                    // 切换：拉弓 / 放下（显式状态，不能读 isChargingAttack —— 会被实体 tick 重置）
                    UUID id = mob.getUniqueId();
                    if (chargingToggled.remove(id)) {
                        ranged.setChargingAttack(false);
                    } else {
                        ranged.setChargingAttack(true);
                        chargingToggled.add(id);
                    }
                }
                return true;
            }
            case BREEZE -> {
                BreezeWindCharge charge = world.spawn(mob.getEyeLocation(), BreezeWindCharge.class);
                charge.setShooter(mob);
                charge.setPersistent(false);
                charge.setVelocity(direction(mob).multiply(1.5));
                world.playSound(mob.getLocation(), Sound.ENTITY_BREEZE_SHOOT, 1.0f, 1.0f);
                return true;
            }
            case SNOW_GOLEM -> {
                Snowball snowball = world.spawn(mob.getEyeLocation(), Snowball.class);
                snowball.setShooter(mob);
                snowball.setPersistent(false);
                snowball.setVelocity(direction(mob).multiply(1.5));
                world.playSound(mob.getLocation(), Sound.ENTITY_SNOWBALL_THROW, 1.0f, 1.0f);
                return true;
            }
            case FROG -> {
                // 回调为之前的实现：吐舌音效 + 手动吃掉 1 级史莱姆/岩浆怪。
                // ★ 2026-10-23 安全复核：只吃「没有归属痕迹」的小史莱姆，
                //   玩家命名的宠物/被拴绳的/别的插件带 PDC 的实体一律不删（PreyGuard）。
                if (target instanceof Slime slime && mayEat(slime, slime.getSize())) {
                    slime.remove();
                    world.playSound(mob.getLocation(), Sound.ENTITY_FROG_TONGUE, 1.0f, 1.0f);
                } else if (target instanceof MagmaCube cube && mayEat(cube, cube.getSize())) {
                    cube.remove();
                    world.playSound(mob.getLocation(), Sound.ENTITY_FROG_TONGUE, 1.0f, 1.0f);
                } else {
                    world.playSound(mob.getLocation(), Sound.ENTITY_FROG_TONGUE, 1.0f, 1.0f);
                }
                return true;
            }
            case PARROT -> {
                // 用户 2026-10-23：鹦鹉坐下改左键，下蹲键仍用于下降（它是飞行生物）
                if (mob instanceof Sittable sittable) {
                    UUID id = mob.getUniqueId();
                    if (poseToggled.remove(id)) {
                        sittable.setSitting(false);
                    } else {
                        sittable.setSitting(true);
                        poseToggled.add(id);
                    }
                }
                return true;
            }
            case SQUID, GLOW_SQUID -> {
                boolean glow = state.type == EntityType.GLOW_SQUID;
                world.spawnParticle(glow ? Particle.GLOW_SQUID_INK : Particle.SQUID_INK,
                        mob.getLocation(), 30, 0.5, 0.5, 0.5, 0.05);
                world.playSound(mob.getLocation(),
                        glow ? Sound.ENTITY_GLOW_SQUID_SQUIRT : Sound.ENTITY_SQUID_SQUIRT, 1.0f, 1.0f);
                return true;
            }
            case SNIFFER -> {
                // 用户 2026-10-23：12 秒太长 ⇒ 8 秒序列 + 8 秒 CD（动画同长）
                if (mob instanceof Sniffer sniffer) {
                    UUID id = mob.getUniqueId();
                    long now = System.currentTimeMillis();
                    Long until = snifferCooldown.get(id);
                    if (until != null && now < until) {
                        return true;
                    }
                    snifferCooldown.put(id, now + 8_000L);
                    sniffer.setState(Sniffer.State.SNIFFING);
                    later(20, () -> {
                        if (!sniffer.isValid()) {
                            return;
                        }
                        sniffer.setState(Sniffer.State.SEARCHING);
                        later(40, () -> {
                            if (!sniffer.isValid()) {
                                return;
                            }
                            sniffer.setState(Sniffer.State.DIGGING);
                            later(80, () -> {
                                if (!sniffer.isValid()) {
                                    return;
                                }
                                sniffer.setState(Sniffer.State.RISING);
                                later(20, () -> {
                                    if (!sniffer.isValid()) {
                                        return;
                                    }
                                    sniffer.setState(Sniffer.State.IDLING);
                                    snifferCooldown.remove(id);
                                    Material loot = ThreadLocalRandom.current().nextBoolean()
                                            ? Material.PITCHER_POD : Material.TORCHFLOWER_SEEDS;
                                    world.dropItem(sniffer.getLocation(), new ItemStack(loot));
                                });
                            });
                        });
                    });
                }
                return true;
            }
            case ENDERMAN -> {
                if (mob instanceof Enderman enderman) {
                    enderman.teleportRandomly();
                }
                return true;
            }
            case LLAMA, TRADER_LLAMA -> {
                spit(mob);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** 青蛙能不能吃掉它：只吃无归属痕迹的 1 级史莱姆/岩浆怪（判据在纯函数 {@link PreyGuard}）。 */
    private static boolean mayEat(@NotNull LivingEntity prey, int size) {
        return PreyGuard.mayConsume(
                size <= 1,
                prey.getCustomName() != null,
                prey.isLeashed(),
                !prey.getPersistentDataContainer().isEmpty(),
                prey.isInsideVehicle() || !prey.getPassengers().isEmpty(),
                !prey.getPassengers().isEmpty());
    }

    private static @NotNull Vector direction(@NotNull Mob mob) {
        double radians = Math.toRadians(mob.getLocation().getYaw());
        return new Vector(-Math.sin(radians), 0, Math.cos(radians)).normalize();
    }

    private static @NotNull ItemStack poisonPotion() {
        ItemStack item = new ItemStack(Material.POTION);
        if (item.getItemMeta() instanceof PotionMeta meta) {
            meta.setBasePotionType(PotionType.POISON);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** 唤魔者：自身周围十字形 4 个尖牙（原版伤害）。 */
    private void summonFangs(@NotNull Mob mob) {
        World world = mob.getWorld();
        Location base = mob.getLocation();
        Vector forward = direction(mob);
        Vector right = new Vector(forward.getZ(), 0, -forward.getX());
        for (Vector offset : new Vector[]{forward.clone().multiply(2), forward.clone().multiply(-2),
                right.clone().multiply(2), right.clone().multiply(-2)}) {
            Location at = base.clone().add(offset);
            EvokerFangs fangs = world.spawn(at, EvokerFangs.class);
            fangs.setOwner(mob);
            fangs.setPersistent(false);
        }
    }

    /** 羊驼：吐口水（原版伤害）。 */
    private void spit(@NotNull Mob mob) {
        World world = mob.getWorld();
        LlamaSpit spit = world.spawn(mob.getEyeLocation(), LlamaSpit.class);
        spit.setShooter(mob);
        spit.setPersistent(false);
        spit.setVelocity(direction(mob).multiply(1.5));
    }

    private void later(int ticks, @NotNull Runnable action) {
        Bukkit.getScheduler().runTaskLater(plugin, action, ticks);
    }

    private void onRight(@NotNull Player player, @NotNull MobState state) {
        if (!effectReady(player)) {
            return;
        }
        Entity puppet = plugin.getServer().getEntity(state.puppetId);
        if (!(puppet instanceof Mob mob)) {
            return;
        }
        if (specialRight(player, state, mob)) {
            return;
        }
        Sound sound = switch (state.type) {
            case ENDER_DRAGON -> Sound.ENTITY_ENDER_DRAGON_GROWL;
            case WITHER -> Sound.ENTITY_WITHER_AMBIENT;
            default -> mob.getAmbientSound();
        };
        if (sound != null) {
            World world = mob.getWorld();
            world.playSound(mob.getLocation(), sound, 1.0f, 1.0f);
        }
        if (config.debug()) {
            plugin.getLogger().info("[debug] 右键 " + state.type + " 音效 " + sound);
        }
    }

    /** 右键专属：苦力怕只嘶嘶；监守者长 AI 接管音波（播放完整动画、伤害抑制）。 */
    private boolean specialRight(@NotNull Player player, @NotNull MobState state, @NotNull Mob mob) {
        if (state.type == EntityType.CREEPER) {
            mob.getWorld().playSound(mob.getLocation(), Sound.ENTITY_CREEPER_PRIMED, 1.0f, 1.0f);
            return true;
        }
        if (state.type == EntityType.WARDEN) {
            // 用户 2026-10-23：0.5 秒蓄力去抖 + 3 秒内置 CD；蓄力期间再点不叠加
            UUID id = mob.getUniqueId();
            long now = System.currentTimeMillis();
            Long until = wardenSonicCooldown.get(id);
            if (until != null && now < until) {
                return true;
            }
            wardenSonicCooldown.put(id, now + 3_000L);
            // 回调为之前的实现：蓄力 + 粒子射线 + 击退，无伤害
            World world = mob.getWorld();
            world.playSound(mob.getLocation(), Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.0f, 1.0f);
            later(10, () -> {
                if (!mob.isValid()) {
                    return;
                }
                world.playSound(mob.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 1.0f, 1.0f);
                Vector dir = direction(mob);
                Location eye = mob.getEyeLocation();
                for (int i = 1; i <= 12; i++) {
                    Location point = eye.clone().add(dir.clone().multiply(i));
                    world.spawnParticle(Particle.SONIC_BOOM, point, 1, 0, 0, 0, 0);
                }
                RayTraceResult result = world.rayTraceEntities(eye, dir, 12.0, 0.5,
                        entity -> entity instanceof LivingEntity
                                && !entity.getUniqueId().equals(mob.getUniqueId())
                                && !(entity instanceof ArmorStand));
                if (result != null && result.getHitEntity() instanceof LivingEntity living) {
                    living.setVelocity(dir.clone().multiply(1.2).setY(0.4));
                }
            });
            return true;
        }
        return false;
    }

    /** 长 AI 接管窗口：goal 生物恢复 goals，brain 生物恢复 AI；窗口结束收回控制。 */
    private void startAiWindow(@NotNull Mob mob, @NotNull MobState state, int ticks, @Nullable LivingEntity target) {
        UUID id = mob.getUniqueId();
        if (!aiWindow.add(id)) {
            return;
        }
        if (AI_GOAL_SKILLS.contains(state.type)) {
            MasqNms.restoreGoals(mob, savedGoals.getOrDefault(id, List.of()));
            mob.setAI(true);
        } else {
            mob.setAI(true);
        }
        if (target != null) {
            mob.setTarget(target);
        }
        if (state.type == EntityType.WARDEN) {
            suppressDamage.add(id);
        }
        later(ticks, () -> {
            aiWindow.remove(id);
            suppressDamage.remove(id);
            if (!mob.isValid()) {
                return;
            }
            if (AI_GOAL_SKILLS.contains(state.type)) {
                try {
                    MasqNms.clearGoals(mob);
                } catch (Throwable ignored) {
                    // 收不回就保持 AI；玩家可用 /masq end
                }
                mob.setAI(true);
            } else {
                mob.setAI(false);
            }
        });
    }

    private boolean effectReady(@NotNull Player player) {
        long now = System.currentTimeMillis();
        Long previous = lastEffect.get(player.getUniqueId());
        if (previous != null && now - previous < EFFECT_COOLDOWN_MILLIS) {
            return false;
        }
        lastEffect.put(player.getUniqueId(), now);
        return true;
    }

    /** 狐狸优先选鸡/兔子（原版 FoxPounceGoal 的猎物），没有就退回最近实体。 */
    private @Nullable LivingEntity nearestFoxPrey(@NotNull Player player, @NotNull Mob mob, double radius) {
        LivingEntity best = null;
        double bestDistance = radius * radius;
        for (LivingEntity living : mob.getWorld().getNearbyLivingEntities(
                mob.getLocation(), radius, radius, radius,
                entity -> !entity.getUniqueId().equals(player.getUniqueId())
                        && !entity.getUniqueId().equals(mob.getUniqueId())
                        && (entity instanceof Chicken || entity instanceof Rabbit))) {
            double distance = living.getLocation().distanceSquared(mob.getLocation());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = living;
            }
        }
        return best != null ? best : nearestTarget(player, mob, radius);
    }

    /** 最近的 LivingEntity（排除玩家本体与傀儡），用于狐狸/山羊的技能目标。 */
    private @Nullable LivingEntity nearestTarget(@NotNull Player player, @NotNull Mob mob, double radius) {
        LivingEntity best = null;
        double bestDistance = radius * radius;
        for (LivingEntity living : mob.getWorld().getNearbyLivingEntities(
                mob.getLocation(), radius, radius, radius,
                entity -> !entity.getUniqueId().equals(player.getUniqueId())
                        && !entity.getUniqueId().equals(mob.getUniqueId())
                        && !(entity instanceof ArmorStand))) {
            double distance = living.getLocation().distanceSquared(mob.getLocation());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = living;
            }
        }
        return best;
    }

    /** 从傀儡眼睛沿头朝向（水平）取最近的 LivingEntity，排除玩家本体与傀儡。 */
    private @Nullable LivingEntity rayTarget(@NotNull Player player, @NotNull Mob mob) {
        Location eye = mob.getEyeLocation();
        double radians = Math.toRadians(mob.getLocation().getYaw());
        Vector direction = new Vector(-Math.sin(radians), 0, Math.cos(radians));
        RayTraceResult result = mob.getWorld().rayTraceEntities(
                eye, direction, LEFT_REACH, 0.3,
                entity -> !entity.getUniqueId().equals(player.getUniqueId())
                        && !entity.getUniqueId().equals(mob.getUniqueId())
                        && !(entity instanceof ArmorStand)
                        && entity instanceof LivingEntity);
        if (result == null || !(result.getHitEntity() instanceof LivingEntity living)) {
            return null;
        }
        return living;
    }

    /** 只用生物原版 ATTACK_KNOCKBACK 属性，不造成任何伤害。 */
    private static void knockback(@NotNull Mob mob, @NotNull LivingEntity target, float yaw) {
        AttributeInstance attribute = mob.getAttribute(Attribute.ATTACK_KNOCKBACK);
        double strength = attribute == null ? 0.0 : attribute.getValue();
        if (strength <= 0.0) {
            return;
        }
        double radians = Math.toRadians(yaw);
        Vector direction = new Vector(-Math.sin(radians), 0, Math.cos(radians)).normalize();
        Vector velocity = target.getVelocity();
        target.setVelocity(velocity.add(direction.multiply(strength)).setY(Math.max(0.2, velocity.getY())));
    }

    // === 拦截原版伤害 / 挖方块 ===

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(@NotNull EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Player player && isActive(player)) {
            event.setCancelled(true);
            return;
        }
        for (MobState state : active.values()) {
            if (state.puppetId.equals(damager.getUniqueId())) {
                // 只抑制我们主动调 doHurtTarget 的那一下；山羊 ram 等原版技能伤害保留
                if (suppressDamage.contains(state.puppetId) || state.type == EntityType.WITHER) {
                    event.setCancelled(true);
                }
                return;
            }
            // 凋灵的凋灵头颅（shooter = 傀儡）：用户反馈它会打周围非亡灵生物
            if (state.type == EntityType.WITHER && damager instanceof Projectile projectile
                    && projectile.getShooter() instanceof Entity shooter
                    && shooter.getUniqueId().equals(state.puppetId)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockDamage(@NotNull BlockDamageEvent event) {
        if (isActive(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(@NotNull BlockBreakEvent event) {
        if (isActive(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onToggleSneak(@NotNull PlayerToggleSneakEvent event) {
        if (event.isSneaking() && isActive(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    // === 回收 ===

    public boolean end(@NotNull Player player, @NotNull String reason) {
        return end(player, reason, false);
    }

    /**
     * @param silent {@code true} = 不在这里发「效果已结束」，由调用方统一发（
     *               避免 /masq end 同时出「已结束」和「切世界」两条）
     */
    public boolean end(@NotNull Player player, @NotNull String reason, boolean silent) {
        MobState state = active.remove(player.getUniqueId());
        lastEffect.remove(player.getUniqueId());
        if (state == null) {
            return false;
        }
        cleanup(player, state, !silent);
        plugin.getLogger().info("[mob] 结束 " + player.getName() + "（" + reason
                + "，剩余 " + active.size() + " 个）");
        return true;
    }

    private void cleanup(@NotNull Player player, @NotNull MobState state, boolean notify) {
        if (player.isOnline()) {
            try {
                MasqNms.setCamera(player, null);
            } catch (Throwable ignored) {
                // 还原失败不能把玩家卡在相机里
            }
            // 易容术本身带 3 分钟隐身；提前结束时把这份药水隐身也收回，别留残留
            player.removePotionEffect(PotionEffectType.INVISIBILITY);
            MasqueradeManager.restorePreviousInvisibility(
                    player, state.previousInvisibility, state.appliedAtMillis);
            player.setInvisible(state.wasInvisible);
            player.getPersistentDataContainer().remove(MasqKeys.MOB_ACTIVE);
            player.getPersistentDataContainer().remove(MasqKeys.MOB_WAS_INVISIBLE);
            showToAll(player);
            if (notify) {
                messages.send(player, "effect-ended");
            }
        }
        removePuppet(state);
    }

    private void removePuppet(@NotNull MobState state) {
        forgetPuppet(state.puppetId);
        Entity puppet = plugin.getServer().getEntity(state.puppetId);
        if (puppet == null) {
            return;
        }
        if (!isOurPuppet(puppet)) {
            // 主证据是内存 UUID（spawn 时记下的）；PDC 缺失只告警，避免留下无法对账的残留
            plugin.getLogger().warning("傀儡 " + state.puppetId + " 缺少 " + MasqKeys.MOB_PUPPET
                    + " 标记，按内存 UUID 移除。");
        }
        puppet.remove();
    }

    /** 清掉与某只傀儡相关的全部内存状态（附身失败时也要调用，避免 Map 泄漏）。 */
    private void forgetPuppet(@NotNull UUID id) {
        puffed.remove(id);
        suppressDamage.remove(id);
        chargingToggled.remove(id);
        aiWindow.remove(id);
        savedGoals.remove(id);
        foxSleeping.remove(id);
        poseToggled.remove(id);
        snifferCooldown.remove(id);
        wardenSonicCooldown.remove(id);
    }

    private static boolean isOurPuppet(@NotNull Entity entity) {
        return entity.getPersistentDataContainer().has(MasqKeys.MOB_PUPPET, PersistentDataType.BYTE);
    }

    private void hideFromOthers(@NotNull Player player) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.getUniqueId().equals(player.getUniqueId())) {
                viewer.hideEntity(plugin, player);
            }
        }
    }

    private void showToAll(@NotNull Player player) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.getUniqueId().equals(player.getUniqueId())) {
                viewer.showEntity(plugin, player);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        Player player = event.getPlayer();
        MobState state = active.remove(player.getUniqueId());
        lastEffect.remove(player.getUniqueId());
        if (state == null) {
            return;
        }
        // 退出时先还原隐身（否则 Bukkit.invisible / 药水隐身写进存档、重进永久隐身）
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        MasqueradeManager.restorePreviousInvisibility(
                player, state.previousInvisibility, state.appliedAtMillis);
        player.setInvisible(state.wasInvisible);
        player.getPersistentDataContainer().remove(MasqKeys.MOB_ACTIVE);
        player.getPersistentDataContainer().remove(MasqKeys.MOB_WAS_INVISIBLE);
        removePuppet(state);
        showToAll(player);
        plugin.getLogger().info("[mob] 退出清理：" + player.getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(@NotNull PlayerDeathEvent event) {
        if (isActive(event.getEntity())) {
            end(event.getEntity(), "死亡");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(@NotNull PlayerChangedWorldEvent event) {
        if (isActive(event.getPlayer())) {
            end(event.getPlayer(), "切世界");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onStartSpectating(@NotNull PlayerStartSpectatingEntityEvent event) {
        MobState state = active.get(event.getPlayer().getUniqueId());
        if (state == null || event.getNewSpectatorTarget() == null) {
            return;
        }
        if (!event.getNewSpectatorTarget().getUniqueId().equals(state.puppetId)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onKick(@NotNull PlayerKickEvent event) {
        if (event.getCause() == PlayerKickEvent.Cause.SELF_INTERACTION && isActive(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    private void stopTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private static final class MobState {
        private final UUID puppetId;
        private final String worldName;
        private final EntityType type;
        private final long untilMillis;
        private final long appliedAtMillis;
        private final @Nullable PotionEffect previousInvisibility;
        private final float speed;
        private final boolean hasMeleeGoal;
        private final boolean flying;
        private final boolean teleportDrive;
        private final boolean wasInvisible;
        private float yaw;
        private boolean jumpHeld;
        private boolean sneakHeld;

        private MobState(UUID puppetId, String worldName, EntityType type, long untilMillis,
                         long appliedAtMillis, @Nullable PotionEffect previousInvisibility,
                         float speed, boolean hasMeleeGoal, boolean flying, boolean teleportDrive,
                         boolean wasInvisible, float yaw) {
            this.puppetId = puppetId;
            this.worldName = worldName;
            this.type = type;
            this.untilMillis = untilMillis;
            this.appliedAtMillis = appliedAtMillis;
            this.previousInvisibility = previousInvisibility;
            this.speed = speed;
            this.hasMeleeGoal = hasMeleeGoal;
            this.flying = flying;
            this.teleportDrive = teleportDrive;
            this.wasInvisible = wasInvisible;
            this.yaw = yaw;
        }
    }
}
