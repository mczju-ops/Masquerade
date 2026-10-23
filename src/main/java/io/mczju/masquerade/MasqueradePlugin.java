package io.mczju.masquerade;

import io.mczju.masquerade.command.MasqCommand;
import io.mczju.masquerade.config.MasqConfig;
import io.mczju.masquerade.config.StartupReport;
import io.mczju.masquerade.effect.MasqueradeManager;
import io.mczju.masquerade.effect.MobMasqueradeManager;
import io.mczju.masquerade.effect.PlayerRecovery;
import io.mczju.masquerade.item.MasqItems;
import io.mczju.mczjuitemcreator.api.ItemCreatorApi;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;

/**
 * Masquerade 入口：配置 → ItemCreator 接入 → 物品层 → 取样/效果 → 指令。
 *
 * <p>关服/禁用时的还原在 {@link MasqueradeManager#shutdown()}（铁律 14）。</p>
 */
public final class MasqueradePlugin extends JavaPlugin {

    private MasqConfig config;
    private MasqItems items;
    private MasqueradeManager masquerade;
    private MobMasqueradeManager mobMasquerade;
    private MasqCommand command;

    @Override
    public void onEnable() {
        saveIfMissing("config.yml");
        saveIfMissing("items/morphpotion.yml");

        this.config = MasqConfig.load(getConfig(), getLogger());

        ItemCreatorApi api = Bukkit.getServicesManager().load(ItemCreatorApi.class);
        if (api == null) {
            getLogger().severe("未找到 MCZJUItemCreator（ItemCreatorApi）——本插件必须依赖它运行，已禁用。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.items = new MasqItems(this, config, api);
        this.items.reload();

        this.masquerade = new MasqueradeManager(this, config, items);
        this.masquerade.start();
        getServer().getPluginManager().registerEvents(masquerade, this);

        this.mobMasquerade = new MobMasqueradeManager(this, config, items, masquerade);
        this.mobMasquerade.start();
        getServer().getPluginManager().registerEvents(mobMasquerade, this);
        // 方块 ⇄ 生物互斥：方块形态开始前先结束生物形态（反向在 MobMasqueradeManager.apply）
        this.masquerade.mobManager(mobMasquerade);
        // 崩溃残留兜底（生物附身的 setInvisible flag），只认本插件 PDC 标记
        getServer().getPluginManager().registerEvents(
                new PlayerRecovery(this, config.messages()), this);
        String snapshotCheck = io.mczju.masquerade.nms.MasqNms.selfCheck();
        if (snapshotCheck == null) {
            getLogger().info("生物快照自检通过（TagParser + CraftEntitySnapshot）。");
        } else {
            getLogger().warning("生物快照自检失败（生物易容不可用）：" + snapshotCheck);
        }

        this.command = new MasqCommand(this, config, items, masquerade, mobMasquerade);
        PluginCommand masq = getCommand("masq");
        if (masq == null) {
            getLogger().severe("plugin.yml 里没有 masq 指令，无法注册执行器。");
        } else {
            masq.setExecutor(command);
            masq.setTabCompleter(command);
            // /help 的指令描述/用法也走配置（plugin.yml 只是出厂默认）
            masq.setDescription(config.messages().raw("command-description"));
            masq.setUsage(config.messages().raw("command-usage"));
        }

        getLogger().info(StartupReport.line(
                getPluginMeta().getVersion(),
                items.convertibleMaterials().size(),
                items.potionAvailable(),
                config.effect().durationSeconds(),
                config.effect().scale(),
                config.items().sampleCooldownSeconds(),
                config.items().cooldownSeconds()));
    }

    @Override
    public void onDisable() {
        if (mobMasquerade != null) {
            mobMasquerade.shutdown();
        }
        if (masquerade != null) {
            masquerade.shutdown();
        }
        getLogger().info("Masquerade 已关闭。");
    }

    public @NotNull MasqConfig config() {
        return config;
    }

    public @Nullable MasqItems items() {
        return items;
    }

    public @Nullable MasqueradeManager masquerade() {
        return masquerade;
    }

    /** 只在文件不存在时写（{@code saveResource(name, false)} 对已存在文件会刷 WARN）。 */
    private void saveIfMissing(@NotNull String name) {
        File target = new File(getDataFolder(), name);
        if (target.isFile()) {
            return;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            getLogger().warning("无法创建目录 " + parent);
            return;
        }
        saveResource(name, false);
    }
}
