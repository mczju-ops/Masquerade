package io.mczju.masquerade.effect;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 「恼鬼（Vex）不许钻进实心方块」的守卫（纯函数 + 一个世界查询，可离线单测）。
 *
 * <p>2026-10-29 用户口径：**只限制恼鬼的大范围远距离穿墙**（防止变身恼鬼后穿过地形、
 * 钻进服务器地下放命令方块的房间），其他生物、其他能力一律不限制。</p>
 *
 * <p>为什么只有恼鬼需要：26.2 全部 {@code net/minecraft/world/entity/**} 里，
 * 可附身的 Mob 中只有 {@code Vex#tick} 会在 {@code super.tick()} 期间把 {@code noPhysics}
 * 置 true（{@code javap} 已核）—— 它是真的穿方块，而且速度驱动的预检只有它需要。</p>
 *
 * <p>判据是恼鬼**自己的**整个碰撞箱（不是单点、不是单格），并按位移逐段采样：
 * 任意一段会进实心方块，本 tick 就停在墙前。<b>不</b>校验玩家本体、不校验其他生物。</p>
 *
 * <p>★ {@code Block#getCollisionShape()} 返回的是**方块本地坐标（0..1）**的
 * {@link org.bukkit.util.VoxelShape}（{@code BlockCollisions} 会再 {@code move(pos)}；
 * {@code javap} 已核）。所以查询世界坐标的箱子时必须先 {@link #local} 平移，否则永远不重叠、
 * 守卫会形同虚设。</p>
 *
 * <p>纯函数部分（{@link #box} / {@link #cells} / {@link #local} / {@link #sweep}）
 * 不碰服务端，可离线单测。</p>
 */
public final class WallGuard {

    /** 恼鬼位移的采样步长（格）：单步可能 0.7 格，必须逐段校验，防「跳格穿墙」。 */
    public static final double SAMPLE_STEP = 0.25;

    private WallGuard() {
    }

    /**
     * 实体碰撞箱（{@code feetY} 是脚底 Y，{@code x/z} 是脚底中心）。
     *
     * @param width  实体宽度（恼鬼约 0.5）
     * @param height 实体高度（恼鬼约 1.0）
     */
    public static @NotNull BoundingBox box(double x, double feetY, double z, double width, double height) {
        double half = Math.max(0.05, width) / 2.0;
        return new BoundingBox(
                x - half, feetY, z - half,
                x + half, feetY + Math.max(0.05, height), z + half);
    }

    /** 方块坐标（闭区间）。纯函数。 */
    public static @NotNull List<Cell> cells(@NotNull BoundingBox box) {
        int minX = (int) Math.floor(box.getMinX());
        int maxX = (int) Math.floor(box.getMaxX());
        int minY = (int) Math.floor(box.getMinY());
        int maxY = (int) Math.floor(box.getMaxY());
        int minZ = (int) Math.floor(box.getMinZ());
        int maxZ = (int) Math.floor(box.getMaxZ());
        List<Cell> out = new ArrayList<>((maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1));
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    out.add(new Cell(x, y, z));
                }
            }
        }
        return out;
    }

    /** 方块坐标（世界坐标格）。 */
    public record Cell(int x, int y, int z) {
    }

    /**
     * {@code from → to} 的路径采样点（不含 from、含 to），每个点 {@code [x, y, z]}。纯函数。
     *
     * <p>采样是防「恼鬼一大步跳过一格墙」的关键：它没有重力、速度就是唯一位移来源，
     * 只校验终点会漏掉中间穿过的方块（斜角切角同理）。</p>
     */
    public static @NotNull List<double[]> sweep(double fromX, double fromY, double fromZ,
                                                double toX, double toY, double toZ, double step) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int count = Math.max(1, (int) Math.ceil(distance / Math.max(0.05, step)));
        List<double[]> points = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            double ratio = (double) i / count;
            points.add(new double[]{fromX + dx * ratio, fromY + dy * ratio, fromZ + dz * ratio});
        }
        return points;
    }

    /**
     * 世界坐标盒 → 方块本地坐标。纯函数。
     *
     * <p>{@code Block#getCollisionShape()} 返回 0..1 的本地形状，必须用平移后的盒子去
     * {@code overlaps}，否则永远不相交。</p>
     */
    public static @NotNull BoundingBox local(@NotNull BoundingBox worldBox, @NotNull Cell cell) {
        return worldBox.shift(-cell.x(), -cell.y(), -cell.z());
    }

    /**
     * 这个箱子是否完全不与方块碰撞体积相交。
     *
     * <p>空气先按 {@code Material#isAir()} 快速跳过；其余（水/草/火把…）用碰撞形状判定，
     * 台阶、楼梯、栅栏等**非满格碰撞体积**按真实形状计算，不会把「站在半砖上」误判成卡墙。</p>
     */
    public static boolean clear(@NotNull World world, @NotNull BoundingBox box) {
        int minY = Math.max(world.getMinHeight(), (int) Math.floor(box.getMinY()));
        int maxY = Math.min(world.getMaxHeight() - 1, (int) Math.floor(box.getMaxY()));
        if (maxY < minY) {
            return true;
        }
        int minX = (int) Math.floor(box.getMinX());
        int maxX = (int) Math.floor(box.getMaxX());
        int minZ = (int) Math.floor(box.getMinZ());
        int maxZ = (int) Math.floor(box.getMaxZ());
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType().isAir()) {
                        continue;
                    }
                    if (block.getCollisionShape().overlaps(local(box, new Cell(x, y, z)))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
