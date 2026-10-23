package io.mczju.masquerade.effect;

/**
 * 「青蛙吃掉小史莱姆/小岩浆怪」的**归属守卫**（纯函数，可离线单测）。
 *
 * <p>2026-10-23 上线前安全复核发现：原来的实现只要左键瞄到**任意** 1 级史莱姆/岩浆怪就
 * {@code remove()} —— 玩家养的命名史莱姆、别的插件生成的史莱姆、被拴绳牵着的史莱姆
 * 都会被永久删掉。这是「误删实体」，不是本插件自己的东西。</p>
 *
 * <p>判据：只吃**没有任何归属痕迹**的小型史莱姆/岩浆怪：
 * 没有自定义名、没有被拴绳、没有别人的 PDC、没有乘客/载具。任一条不满足就只播音效、不删。</p>
 */
public final class PreyGuard {

    private PreyGuard() {
    }

    /**
     * @param sizeOne       是 1 级（最小）史莱姆/岩浆怪
     * @param customNamed   有自定义名（玩家宠物/命名牌）
     * @param leashed       被拴绳牵着
     * @param otherPluginTagged PDC 非空（可能是别的插件的实体；本插件的傀儡也算）
     * @param carrying      骑着别的实体或是载具
     * @param hasPassengers 载着乘客
     */
    public static boolean mayConsume(boolean sizeOne, boolean customNamed, boolean leashed,
                                     boolean otherPluginTagged, boolean carrying, boolean hasPassengers) {
        return sizeOne && !customNamed && !leashed && !otherPluginTagged && !carrying && !hasPassengers;
    }
}
