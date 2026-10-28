package io.mczju.masquerade.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Set;

/**
 * 生物黑名单的**纯字符串规则**（可离线单测）。
 *
 * <p>配置写法：`items.mob-blacklist: [VEX, warden, minecraft:zombie]`。
 * 为了让服主怎么写都不容易写错，匹配前统一规范化：去首尾空白、转小写、去掉 `minecraft:` 前缀。
 * 实际比较的是 {@code EntityType#getKey().getKey()}（如 `vex` / `zombie_villager`）。</p>
 *
 * <p>本类只做字符串，不碰 `EntityType`/注册表，所以能在普通 JUnit 里断言；
 * 「这个名字是否真的是 EntityType」的校验在 {@code MasqConfig#load} 里做（运行时 WARN + 忽略）。</p>
 */
public final class MobBlacklist {

    private MobBlacklist() {
    }

    /** 配置写法 → 规范键：去空白、小写、去掉 `minecraft:` 前缀；空值 → 空串。 */
    public static @NotNull String normalize(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("minecraft:")) {
            value = value.substring("minecraft:".length());
        }
        return value;
    }

    /** {@code entityKey}（如 `VEX` / `minecraft:vex`）是否在已规范化的黑名单里。 */
    public static boolean contains(@NotNull Set<String> normalized, @Nullable String entityKey) {
        if (entityKey == null) {
            return false;
        }
        String key = normalize(entityKey);
        return !key.isEmpty() && normalized.contains(key);
    }
}
