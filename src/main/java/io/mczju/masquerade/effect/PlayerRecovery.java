package io.mczju.masquerade.effect;

import io.mczju.masquerade.MasqKeys;
import io.mczju.masquerade.MasqueradePlugin;
import io.mczju.masquerade.config.Messages;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

/**
 * 登录兜底：清掉**上一次进程被 kill -9 / 崩溃**留下的生物附身隐身 flag。
 *
 * <p>生物附身会 {@code player.setInvisible(true)}，这个 flag 会写进玩家存档；
 * 如果进程没来得及跑清理，重启后玩家会永久隐身。这里只认**本插件自己写的 PDC 标记**
 * （{@link MasqKeys#MOB_ACTIVE}），不会碰别的插件的 vanish。</p>
 *
 * <p>崩溃前如果是被别的插件 vanish 的，标记里存了原 flag（0/1），这里按原值还原。</p>
 */
public final class PlayerRecovery implements Listener {

    private final MasqueradePlugin plugin;
    private final Messages messages;

    public PlayerRecovery(@NotNull MasqueradePlugin plugin, @NotNull Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        if (!pdc.has(MasqKeys.MOB_ACTIVE, PersistentDataType.BYTE)) {
            return;
        }
        boolean wasInvisible = flag(pdc, MasqKeys.MOB_WAS_INVISIBLE);
        pdc.remove(MasqKeys.MOB_ACTIVE);
        pdc.remove(MasqKeys.MOB_WAS_INVISIBLE);
        // 易容术自带的 3 分钟隐身也清掉（跨重启无法还原喝之前的隐身药水，与方块形态同口径）
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        player.setInvisible(wasInvisible);
        messages.send(player, "mob-rejoin-ended");
        plugin.getLogger().info("登录兜底：清除残留的生物附身标记（" + player.getName() + "）");
    }

    private static boolean flag(@NotNull PersistentDataContainer pdc, @NotNull NamespacedKey key) {
        Byte value = pdc.get(key, PersistentDataType.BYTE);
        return value != null && value == 1;
    }
}
