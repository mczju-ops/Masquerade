package io.mczju.masquerade.effect;

import org.bukkit.block.data.BlockData;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 一名玩家当前的易容状态（内存态；持久镜像在玩家 PDC 里）。
 *
 * <p>★ {@link #decide} 是**纯函数**：把「到点 / 隐身被清 / 展示实体掉了」三种判据抽出来，
 * 可以离线穷举（{@code MasqStateTest}）。生产代码只负责照结果执行。</p>
 */
public final class MasqState {

    /** 巡检的处置。 */
    public enum TickAction {
        /** 一切正常。 */
        CONTINUE,
        /** 到点。 */
        END_TIMEOUT,
        /** 隐身被移除（喝牛奶等）。 */
        END_INVISIBILITY_LOST,
        /** 展示实体丢了/跨世界/离玩家太远 ⇒ 重 spawn。 */
        RESPAWN_DISPLAY
    }

    private final UUID playerId;
    private final BlockData block;
    /** 第二格（门的上半 / 床的头）；单格方块为 null。 */
    private final @Nullable BlockData secondBlock;
    /** 第二格相对第一格的基础偏移（门 = +Y；床 = 床朝向；再随展示 yaw 旋转）。 */
    private final @Nullable Vector secondOffset;
    private final long untilMillis;
    private final long appliedAtMillis;
    private final double originalScale;
    private final boolean gaveInvisibility;
    private final @Nullable PotionEffect previousInvisibility;

    private @Nullable UUID displayId;
    private @Nullable UUID secondDisplayId;
    private int displayFailures;
    private float lastAppliedYaw = Float.NaN;

    public MasqState(
            @NotNull UUID playerId,
            @NotNull BlockData block,
            @Nullable BlockData secondBlock,
            @Nullable Vector secondOffset,
            long untilMillis,
            long appliedAtMillis,
            double originalScale,
            boolean gaveInvisibility,
            @Nullable PotionEffect previousInvisibility
    ) {
        this.playerId = playerId;
        this.block = block;
        this.secondBlock = secondBlock;
        this.secondOffset = secondOffset;
        this.untilMillis = untilMillis;
        this.appliedAtMillis = appliedAtMillis;
        this.originalScale = originalScale;
        this.gaveInvisibility = gaveInvisibility;
        this.previousInvisibility = previousInvisibility;
    }

    /**
     * 巡检判据（纯函数）。
     *
     * @param nowMillis                当前时间
     * @param untilMillis              截止时间
     * @param hasInvisibility          玩家现在是否还有隐身效果
     * @param endWhenInvisibilityLost  配置：隐身没了就结束
     * @param displayOk                展示实体是否有效、同世界、且贴着玩家
     */
    public static @NotNull TickAction decide(
            long nowMillis,
            long untilMillis,
            boolean hasInvisibility,
            boolean endWhenInvisibilityLost,
            boolean displayOk
    ) {
        if (nowMillis >= untilMillis) {
            return TickAction.END_TIMEOUT;
        }
        if (endWhenInvisibilityLost && !hasInvisibility) {
            return TickAction.END_INVISIBILITY_LOST;
        }
        if (!displayOk) {
            return TickAction.RESPAWN_DISPLAY;
        }
        return TickAction.CONTINUE;
    }

    public UUID playerId() {
        return playerId;
    }

    public BlockData block() {
        return block;
    }

    public @Nullable BlockData secondBlock() {
        return secondBlock;
    }

    public @Nullable Vector secondOffset() {
        return secondOffset;
    }

    public long untilMillis() {
        return untilMillis;
    }

    public long appliedAtMillis() {
        return appliedAtMillis;
    }

    public double originalScale() {
        return originalScale;
    }

    public boolean gaveInvisibility() {
        return gaveInvisibility;
    }

    public @Nullable PotionEffect previousInvisibility() {
        return previousInvisibility;
    }

    public @Nullable UUID displayId() {
        return displayId;
    }

    public void displayId(@Nullable UUID displayId) {
        this.displayId = displayId;
    }

    public @Nullable UUID secondDisplayId() {
        return secondDisplayId;
    }

    public void secondDisplayId(@Nullable UUID secondDisplayId) {
        this.secondDisplayId = secondDisplayId;
    }

    public int displayFailures() {
        return displayFailures;
    }

    public int incrementDisplayFailures() {
        return ++displayFailures;
    }

    public void resetDisplayFailures() {
        displayFailures = 0;
    }

    /** 上一次写进 {@code Transformation} 的朝向（度）；{@link Float#NaN} = 还没写过。 */
    public float lastAppliedYaw() {
        return lastAppliedYaw;
    }

    public void lastAppliedYaw(float lastAppliedYaw) {
        this.lastAppliedYaw = lastAppliedYaw;
    }
}
