package io.mczju.masquerade.config;

import org.jetbrains.annotations.NotNull;

/**
 * 启动日志的**纯函数**构造器。
 *
 * <p>★ 为什么不直接写在 {@code onEnable} 里：2026-10-22 实机日志里出过一次
 * {@code IllegalFormatConversionException: d != java.lang.String} —— 原因是</p>
 *
 * <pre>{@code
 * getLogger().info("第一段 %s %d" + "第二段 %d %d".formatted(...));   // 错！
 * }</pre>
 *
 * <p>Java 的**方法调用优先级高于 {@code +}**，所以 {@code .formatted} 只作用于第二段，
 * 参数错位后 {@code %d} 收到 {@code String} ⇒ {@code onEnable} 抛异常 ⇒ 插件被 Paper 禁用。
 * 这里用**单条字面量**（不拼接）彻底消灭这一类错误，并由 {@code StartupReportTest} 钉住。</p>
 */
public final class StartupReport {

    private StartupReport() {
    }

    public static @NotNull String line(
            @NotNull String version,
            int blockCount,
            boolean potionReady,
            int durationSeconds,
            double scale,
            int sampleCooldownSeconds,
            int masqueradeCooldownSeconds
    ) {
        // ★ 单条字面量：绝不写成 "..." + "...".formatted(...)
        return "Masquerade 已启用（v%s）｜非空气方块 %d 种｜易容药水 %s｜时长 %d 秒｜scale %.2f｜采样冷却 %d 秒｜易容术冷却 %d 秒"
                .formatted(version, blockCount, potionReady ? "就绪" : "不可用",
                        durationSeconds, scale, sampleCooldownSeconds, masqueradeCooldownSeconds);
    }
}
