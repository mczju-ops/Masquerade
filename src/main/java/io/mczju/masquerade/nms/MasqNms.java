package io.mczju.masquerade.nms;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 生物易容的 NMS 桥（26.2 锁定）。
 *
 * <p>只做 Paper API 做不到的 5 件事（全部经过 {@code javap} 核实，见 {@code docs/04} §5）：</p>
 * <ol>
 *   <li>{@code ServerPlayer#setCamera} —— 非观察者挂相机（改观察者被用户否决）；</li>
 *   <li>{@code getGoalSelector}/{@code targetSelector} —— 清空 goals 保留 {@code travel}；</li>
 *   <li>{@code setYRot/setYHeadRot/setYBodyRot/setXRot} —— 转向；</li>
 *   <li>{@code setShiftKeyDown(false)} —— 防止原版「下蹲脱离相机」（下蹲改为下降键）；</li>
 *   <li>{@code TagParser.parseCompoundFully} + {@code CraftEntitySnapshot.create} —— 把物品里的
 *       SNBT 快照还原成实体（精确复制颜色/亚种/装备/角/尺寸/新生物）。</li>
 * </ol>
 *
 * <p>★ 任何一步失败都抛清晰异常，由调用方拒绝启动/结束变形（fail-safe，不半截）。</p>
 */
public final class MasqNms {

    private static final Map<Class<?>, Method> GET_HANDLE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> SET_CAMERA = new ConcurrentHashMap<>();
    private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();
    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();

    /** 缓存反射入口；类只查一次。 */
    private static @Nullable Method parseSnapshotMethod;
    private static @Nullable Method craftSnapshotCreate;
    private static @Nullable Class<?> compoundTagClass;

    private MasqNms() {
    }

    // === 相机 / 跟随 / 转向 / 驱动 ===

    public static @NotNull Object handle(@NotNull Entity entity) throws ReflectiveOperationException {
        Method method = GET_HANDLE.computeIfAbsent(entity.getClass(), MasqNms::findGetHandle);
        return invoke(method, entity);
    }

    /** {@code camera == null} = 还给自己。 */
    public static void setCamera(@NotNull Player player, @Nullable Entity camera) throws ReflectiveOperationException {
        Object handle = handle(player);
        Method method = SET_CAMERA.computeIfAbsent(handle.getClass(), MasqNms::findSetCamera);
        invoke(method, handle, camera == null ? null : handle(camera));
    }

    public static void clearGoals(@NotNull Entity mob) throws ReflectiveOperationException {
        Object handle = handle(mob);
        Object goalSelector = method(handle.getClass(), "getGoalSelector").invoke(handle);
        removeAll(goalSelector);
        clearTargets(mob);
    }

    /** 保存的 goal（优先级 + 原 Goal 对象），用于长 AI 接管后恢复原版行为。 */
    public record GoalEntry(int priority, @NotNull Object goal) {
    }

    /** 清空 goals 之前调用：把原版 goals 存下来（狐狸飞扑/山羊冲撞等技能窗口恢复用）。 */
    public static @NotNull java.util.List<GoalEntry> captureGoals(@NotNull Entity mob) {
        java.util.List<GoalEntry> entries = new java.util.ArrayList<>();
        try {
            Object handle = handle(mob);
            Object goalSelector = method(handle.getClass(), "getGoalSelector").invoke(handle);
            Object available = method(goalSelector.getClass(), "getAvailableGoals").invoke(goalSelector);
            if (available instanceof Iterable<?> goals) {
                for (Object wrapped : goals) {
                    int priority = (int) method(wrapped.getClass(), "getPriority").invoke(wrapped);
                    Object goal = method(wrapped.getClass(), "getGoal").invoke(wrapped);
                    entries.add(new GoalEntry(priority, goal));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 取不到就退化成不交还 AI
        }
        return entries;
    }

    /** 把 {@link #captureGoals} 存下的 goals 重新加回去（长 AI 接管窗口）。 */
    public static void restoreGoals(@NotNull Entity mob, @NotNull java.util.List<GoalEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        try {
            Object handle = handle(mob);
            Object goalSelector = method(handle.getClass(), "getGoalSelector").invoke(handle);
            Class<?> goalClass = Class.forName("net.minecraft.world.entity.ai.goal.Goal");
            Method add = goalSelector.getClass().getMethod("addGoal", int.class, goalClass);
            for (GoalEntry entry : entries) {
                add.invoke(goalSelector, entry.priority(), entry.goal());
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 恢复失败就保持清空状态（不交还 AI）
        }
    }

    /** 当前 goal 数量（排障：0 才算清干净；取不到返回 -1）。 */
    public static int goalCount(@NotNull Entity mob) {
        try {
            Object handle = handle(mob);
            Object goalSelector = method(handle.getClass(), "getGoalSelector").invoke(handle);
            Object available = method(goalSelector.getClass(), "getAvailableGoals").invoke(goalSelector);
            if (available instanceof java.util.Collection<?> goals) {
                return goals.size();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 排障用，取不到不影响主流程
        }
        return -1;
    }

    /**
     * 有 brain 的生物（猪灵/监守者/美西螈/青蛙/嗅探兽/旋风人/快乐恶魂等）：
     * 只清 goal 不够，brain 的 activity 仍会驱动移动。清掉 behaviors + memories。
     *
     * @return 是否真的找到并清了 brain
     */
    public static boolean clearBrain(@NotNull Entity entity) {
        try {
            Object handle = handle(entity);
            Object brain = method(handle.getClass(), "getBrain").invoke(handle);
            if (brain == null) {
                return false;
            }
            method(brain.getClass(), "removeAllBehaviors").invoke(brain);
            method(brain.getClass(), "clearMemories").invoke(brain);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    public static void clearTargets(@NotNull Entity mob) throws ReflectiveOperationException {
        Object handle = handle(mob);
        Object targetSelector = field(handle.getClass(), "targetSelector").get(handle);
        removeAll(targetSelector);
    }

    private static void removeAll(@NotNull Object selector) throws ReflectiveOperationException {
        Method remove = method(selector.getClass(), "removeAllGoals", Predicate.class);
        invoke(remove, selector, (Predicate<Object>) goal -> true);
    }

    /** 生物有没有近战攻击 goal（默认左键动画的判据，必须在 {@link #clearGoals} 之前调用）。 */
    public static boolean hasMeleeGoal(@NotNull Entity mob) {
        try {
            Object handle = handle(mob);
            Object goalSelector = method(handle.getClass(), "getGoalSelector").invoke(handle);
            Object available = method(goalSelector.getClass(), "getAvailableGoals").invoke(goalSelector);
            if (!(available instanceof Iterable<?> goals)) {
                return false;
            }
            for (Object wrapped : goals) {
                Object goal = method(wrapped.getClass(), "getGoal").invoke(wrapped);
                String name = goal.getClass().getName();
                // 2026-10-23 实测：僵尸是 ZombieAttackGoal、劫掠兽/疣猪是各自的 AttackGoal
                // ⇒ 不能只匹配 MeleeAttackGoal，否则僵尸左键根本不会进默认攻击分支
                if (name.contains("AttackGoal") || name.contains("MeleeAttack")) {
                    return true;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 判不出来就按「没有近战 goal」保守处理
        }
        return false;
    }

    public static void turn(@NotNull Entity mob, float yaw) throws ReflectiveOperationException {
        Object handle = handle(mob);
        method(handle.getClass(), "setYRot", float.class).invoke(handle, yaw);
        method(handle.getClass(), "setYHeadRot", float.class).invoke(handle, yaw);
        method(handle.getClass(), "setYBodyRot", float.class).invoke(handle, yaw);
        method(handle.getClass(), "setXRot", float.class).invoke(handle, 0.0f);
    }

    /**
     * 速度驱动的**目标速度向量**（只算不设；调用方先拿到向量，恼鬼再按自己的碰撞箱做一次
     * 「不许钻进实心方块」的预检，其他生物直接 {@code setVelocity}）。
     *
     * <p>{@code down} 为 true 时给一个向下的速度（飞行/游泳生物用下蹲下降）。
     * ★ 不在这里判 {@code isOnGround()}：调用方已经按「地面跳 / 飞行悬浮」决定 {@code jump}；
     *   飞行生物不按空格时 Y 归零 = 悬浮（修复 2026-10-22 蝙蝠/烈焰人/蜜蜂/幻翼不能起飞）。</p>
     *
     * <p>2026-10-29：从 {@code driveVelocity(…)} 拆成纯计算，是为了让恼鬼专属的限制能在
     * 设置速度之前插一脚；<b>其他生物的行为与拆分前完全一致</b>。</p>
     */
    public static @NotNull Vector driveVector(double currentVertical, float yaw, float forward,
                                              boolean jump, boolean down, float speed, boolean flying) {
        double radians = Math.toRadians(yaw);
        double directionX = -Math.sin(radians);
        double directionZ = Math.cos(radians);
        double vertical = currentVertical;
        if (down) {
            vertical = -0.25;
        } else if (jump) {
            vertical = 0.42;
        } else if (flying) {
            vertical = 0.0;
        }
        if (forward == 0.0f) {
            return new Vector(0.0, vertical, 0.0);
        }
        return new Vector(directionX * speed * forward, vertical, directionZ * speed * forward);
    }

    /** 调原版近战攻击（播放生物自己的攻击动画/音效；伤害由 EntityDamageByEntityEvent 取消）。 */
    public static void attack(@NotNull Entity mob, @NotNull Entity target) throws ReflectiveOperationException {
        Object handle = handle(mob);
        Object level = method(handle.getClass(), "level").invoke(handle);
        Class<?> serverLevel = Class.forName("net.minecraft.server.level.ServerLevel");
        Class<?> entityClass = Class.forName("net.minecraft.world.entity.Entity");
        Method attack = handle.getClass().getMethod("doHurtTarget", serverLevel, entityClass);
        attack.invoke(handle, level, handle(target));
    }

    /** 苦力怕膨胀动画（只改 swellDir，不点引信 ⇒ 不会爆炸）。{@code direction=0} 时同时把 swell 归零。 */
    public static void creeperSwell(@NotNull Entity creeper, int direction) throws ReflectiveOperationException {
        Object handle = handle(creeper);
        method(handle.getClass(), "setSwellDir", int.class).invoke(handle, direction);
        if (direction == 0) {
            for (String name : new String[]{"swell", "oldSwell"}) {
                Field field = findField(handle.getClass(), name);
                if (field != null) {
                    field.setAccessible(true);
                    field.setInt(handle, 0);
                }
            }
        }
    }

    /** `/masq item <生物>` 用：最小 NBT 快照（默认成年/无装备，足够生成一个默认生物）。 */
    public static @NotNull String minimalSnapshot(@NotNull EntityType type) {
        return "{id:\"minecraft:" + type.getKey().getKey() + "\"}";
    }

    /** 唤魔者施法动画（FANGS 手势；不触发原版法术，尖牙仍由插件生成）。 */
    public static void evokerCast(@NotNull Entity evoker, boolean casting) {
        try {
            Class<?> spellClass = Class.forName(
                    "net.minecraft.world.entity.monster.illager.SpellcasterIllager$IllagerSpell");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object spell = Enum.valueOf((Class) spellClass, casting ? "FANGS" : "NONE");
            Object handle = handle(evoker);
            Method set = handle.getClass().getMethod("setIsCastingSpell", spellClass);
            set.invoke(handle, spell);
            Field field = findField(handle.getClass(), "spellCastingTickCount");
            if (field != null) {
                field.setAccessible(true);
                field.setInt(handle, casting ? 20 : 0);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 施法动画是观感增强，失败不影响尖牙
        }
    }

    private static @Nullable Field findField(@NotNull Class<?> type, @NotNull String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /** 把服务端 {@code shiftKeyDown} 清零：下蹲只当下降键，不再触发相机脱离。 */
    public static void clearShift(@NotNull Player player) throws ReflectiveOperationException {
        Object handle = handle(player);
        method(handle.getClass(), "setShiftKeyDown", boolean.class).invoke(handle, false);
    }

    // === 快照：SNBT -> 实体 ===

    /**
     * 物品 PDC 里的 SNBT 还原成 {@link EntitySnapshot}；失败抛异常（调用方取消饮用）。
     */
    public static @NotNull EntitySnapshot snapshotFrom(@NotNull String snbt, @NotNull EntityType type)
            throws ReflectiveOperationException {
        Method parse = parseSnapshotMethod();
        Object tag = invoke(parse, null, snbt);
        if (tag == null) {
            throw new IllegalStateException("快照解析为空");
        }
        Method create = craftSnapshotCreate();
        Object snapshot = invoke(create, null, tag, type);
        if (!(snapshot instanceof EntitySnapshot result)) {
            throw new IllegalStateException("快照类型不符合预期：" + (snapshot == null ? "null" : snapshot.getClass()));
        }
        return result;
    }

    public static @NotNull EntitySnapshot snapshotOf(@NotNull Entity entity) {
        return entity.createSnapshot();
    }

    public static @NotNull Entity createFromSnapshot(@NotNull EntitySnapshot snapshot, @NotNull Location location) {
        Entity entity = snapshot.createEntity(location);
        if (entity == null) {
            throw new IllegalStateException("EntitySnapshot#createEntity 返回 null");
        }
        return entity;
    }

    private static @NotNull Method parseSnapshotMethod() {
        Method cached = parseSnapshotMethod;
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> tagParser = Class.forName("net.minecraft.nbt.TagParser");
            Method method = tagParser.getMethod("parseCompoundFully", String.class);
            method.setAccessible(true);
            parseSnapshotMethod = method;
            return method;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("找不到 TagParser#parseCompoundFully(String)", ex);
        }
    }

    private static @NotNull Method craftSnapshotCreate() {
        Method cached = craftSnapshotCreate;
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> craftSnapshot = Class.forName("org.bukkit.craftbukkit.entity.CraftEntitySnapshot");
            Class<?> tagClass = compoundTagClass();
            Method method = craftSnapshot.getMethod("create", tagClass, EntityType.class);
            method.setAccessible(true);
            craftSnapshotCreate = method;
            return method;
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("找不到 CraftEntitySnapshot#create(CompoundTag, EntityType)", ex);
        }
    }

    private static @NotNull Class<?> compoundTagClass() throws ClassNotFoundException {
        Class<?> cached = compoundTagClass;
        if (cached != null) {
            return cached;
        }
        Class<?> type = Class.forName("net.minecraft.nbt.CompoundTag");
        compoundTagClass = type;
        return type;
    }

    /**
     * 启动自检：只验证反射入口存在 + 能解析一个最小 SNBT 并构造快照对象，**不 spawn**。
     * 失败返回错误文本（调用方只 WARN，不因生物易容不可用而禁用整个插件）。
     */
    public static @Nullable String selfCheck() {
        try {
            Method parse = parseSnapshotMethod();
            Object tag = invoke(parse, null, "{id:\"minecraft:zombie\"}");
            if (tag == null) {
                return "parseCompoundFully 返回 null";
            }
            Method create = craftSnapshotCreate();
            Object snapshot = invoke(create, null, tag, EntityType.ZOMBIE);
            if (!(snapshot instanceof EntitySnapshot)) {
                return "CraftEntitySnapshot.create 返回 " + (snapshot == null ? "null" : snapshot.getClass());
            }
            return null;
        } catch (Throwable error) {
            return error.toString();
        }
    }

    // === 查找 ===

    private static @NotNull Method findGetHandle(@NotNull Class<?> type) {
        try {
            return type.getMethod("getHandle");
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException("找不到 " + type.getName() + "#getHandle", ex);
        }
    }

    private static @NotNull Method findSetCamera(@NotNull Class<?> type) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals("setCamera") && method.getParameterCount() == 1) {
                return method;
            }
        }
        throw new IllegalStateException("找不到 " + type.getName() + "#setCamera(Entity)");
    }

    private static @NotNull Method method(@NotNull Class<?> type, @NotNull String name, Class<?>... params) {
        String key = type.getName() + '#' + name + Arrays.toString(params);
        return METHODS.computeIfAbsent(key, ignored -> {
            try {
                return type.getMethod(name, params);
            } catch (NoSuchMethodException ex) {
                throw new IllegalStateException("找不到 " + key, ex);
            }
        });
    }

    private static @NotNull Field field(@NotNull Class<?> type, @NotNull String name) {
        String key = type.getName() + '#' + name;
        return FIELDS.computeIfAbsent(key, ignored -> {
            try {
                return type.getField(name);
            } catch (NoSuchFieldException ex) {
                throw new IllegalStateException("找不到字段 " + key, ex);
            }
        });
    }

    private static Object invoke(@NotNull Method method, @Nullable Object target, Object... args)
            throws ReflectiveOperationException {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause);
        }
    }
}
