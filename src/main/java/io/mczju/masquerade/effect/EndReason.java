package io.mczju.masquerade.effect;

/**
 * 易容结束的原因（只用于日志与「要不要给玩家发消息」）。
 */
public enum EndReason {
    /** 到点。 */
    TIMEOUT("到点"),
    /** 隐身被移除（喝牛奶/其它插件清效果）。 */
    INVISIBILITY_LOST("隐身被移除"),
    /** 死亡。 */
    DEATH("死亡"),
    /** 退出游戏。 */
    QUIT("退出"),
    /** 关服 / 插件禁用。 */
    SHUTDOWN("关服"),
    /** /masq clear。 */
    COMMAND("指令清除"),
    /** 潜行 + 右键（组合键）。 */
    COMBO("组合键"),
    /** 又喝了一瓶（先结束旧的再开新的）。 */
    REPLACED("重新易容");

    private final String label;

    EndReason(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
