package io.mczju.masquerade;

import org.bukkit.NamespacedKey;

/**
 * 本插件的全部 PDC 键。
 *
 * <p>命名空间固定 {@value #NAMESPACE}（= 插件名 {@code Masquerade} 的小写形式）。
 * 需求里的「特殊 pdc 标签 masquerade」指的就是这个命名空间：物品只要带
 * {@code masquerade:type} 就属于本插件。</p>
 *
 * <p>★ 键名与命名空间是「唯一判据」（{@code AGENTS.md} 铁律 10 第 3 问）——
 * 识别物品 / 识别玩家状态**只认 PDC**，不认材质、名称或邻近关系。</p>
 */
public final class MasqKeys {

    /** PDC 命名空间（= 插件名小写）。 */
    public static final String NAMESPACE = "masquerade";

    /** 物品：{@code morphpotion}（原料）或 {@code masquerade}（易容术）。 */
    public static final NamespacedKey TYPE = new NamespacedKey(NAMESPACE, "type");
    /** 物品：易容成的方块（{@code Material} 名），仅方块易容术有。 */
    public static final NamespacedKey BLOCK = new NamespacedKey(NAMESPACE, "block");
    /** 物品：生物快照（SNBT，来自 {@code EntitySnapshot#getAsString}），仅生物易容术有。 */
    public static final NamespacedKey MOB = new NamespacedKey(NAMESPACE, "mob");
    /** 物品：生物类型（{@code EntityType} 名），与 {@link #MOB} 成对出现。 */
    public static final NamespacedKey MOB_TYPE = new NamespacedKey(NAMESPACE, "mob-type");
    /** 傀儡实体：归属标记（只读核对；本插件靠内存 UUID，不扫世界）。 */
    public static final NamespacedKey MOB_PUPPET = new NamespacedKey(NAMESPACE, "mob-puppet");
    /** 玩家：效果截止时间（epoch millis）—— 重启兜底的归属证据。 */
    public static final NamespacedKey UNTIL = new NamespacedKey(NAMESPACE, "until");
    /** 玩家：喝之前的 {@code Attribute.SCALE} 基础值 —— 退出/重启后还原尺寸。 */
    public static final NamespacedKey SCALE = new NamespacedKey(NAMESPACE, "scale");
    /** 展示实体：归属标记（只读核对；本插件不扫世界，靠内存 UUID）。 */
    public static final NamespacedKey DISPLAY = new NamespacedKey(NAMESPACE, "display");
    /**
     * 玩家：生物附身正在进行（退出/崩溃后的登录兜底证据）。
     *
     * <p>生物附身会 {@code setInvisible(true)}，这个 flag 会写进玩家存档；没有这个标记，
     * 进程被 kill -9 后玩家会永久隐身。登录时只清**带本标记**的隐身，绝不碰别的插件的 vanish。</p>
     */
    public static final NamespacedKey MOB_ACTIVE = new NamespacedKey(NAMESPACE, "mob-active");
    /** 玩家：生物附身之前的 Bukkit 隐身 flag（0/1）—— 崩溃后按原值还原，不误伤 vanish。 */
    public static final NamespacedKey MOB_WAS_INVISIBLE = new NamespacedKey(NAMESPACE, "mob-was-invisible");

    private MasqKeys() {
    }
}
