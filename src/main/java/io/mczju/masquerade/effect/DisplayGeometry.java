package io.mczju.masquerade.effect;

import org.jetbrains.annotations.NotNull;

/**
 * 展示实体的几何计算（纯函数，可离线单测）。
 *
 * <p>★ 为什么需要 {@code -0.5} 补偿（2026-10-22 实机 bug）：{@code BlockDisplay} 的方块模型
 * 占**模型空间 {@code 0..1}**，而实体位置是模型原点 ⇒ 方块实际出现在「实体位置 → +1 格」，
 * 相对玩家**偏了半格**（截图里方块在玩家的斜前方）。要让方块**以玩家为中心**，必须把模型
 * 平移 {@code -0.5}（X/Z）。</p>
 *
 * <p>★ 为什么不直接用实体 yaw 转方块：实体 yaw 会连**平移**一起旋转（转轴是实体原点），
 * 那样 {@code -0.5} 的补偿本身会随朝向乱转 ⇒ 越走越歪。这里改用
 * {@code Transformation.leftRotation} 让方块**绕自己的中心**旋转，并用
 * {@code T = 期望中心 - R·(scale·模型中心)} 抵消，实体 yaw 恒为 0。</p>
 */
public final class DisplayGeometry {

    /** 方块模型在模型空间里的中心（{@code 0..1} 的中间）。 */
    public static final float BLOCK_CENTER = 0.5F;

    private DisplayGeometry() {
    }

    /** 四元数用的弧度：与 Minecraft 的 yaw 方向相反（MC yaw 90 = 朝西）。 */
    public static float yawRadians(float yawDegrees) {
        return (float) Math.toRadians(-yawDegrees);
    }

    /**
     * 把水平偏移按展示 yaw 旋转（门的上半是 +Y，不需要；床的头是水平方向，需要）。
     *
     * @return {@code [x, z]}，y 不变
     */
    public static float @NotNull [] rotateY(float yawDegrees, float x, float z) {
        double radians = yawRadians(yawDegrees);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        return new float[]{x * cos + z * sin, -x * sin + z * cos};
    }

    /**
     * 计算 {@code Transformation.translation}（实体局部空间）。
     *
     * @param yawDegrees 方块要转到的朝向（玩家 yaw；不转就传 0）
     * @param scale      展示实体缩放
     * @param tx/ty/tz   配置里的额外偏移（相对「方块居中的脚底」）
     * @return {@code [x, y, z]}：让方块**水平居中于玩家、底面贴脚底**的平移量
     */
    public static float @NotNull [] translation(
            float yawDegrees, float scale, float tx, float ty, float tz) {
        float half = BLOCK_CENTER * scale;
        double radians = yawRadians(yawDegrees);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        // R_y(radians) · (half, half, half)
        float rotatedX = half * cos + half * sin;
        float rotatedZ = -half * sin + half * cos;
        // 期望中心 = (tx, 0.5*scale + ty, tz)；减去旋转后的模型中心
        return new float[]{tx - rotatedX, ty, tz - rotatedZ};
    }
}
