package io.github.nekonyan.genshinnostart;

import com.xuncorp.spw.workshop.api.WorkshopApi;
import com.xuncorp.spw.workshop.api.config.ConfigHelper;
import com.xuncorp.spw.workshop.api.config.ConfigManager;
import java.util.function.Consumer;

/**
 * 「界面提示」开关：整个插件唯一的配置项，默认关闭。
 *
 * 关闭时插件完全静默——既不弹吐司，也不往日志里写加载/生效状态，
 * 只保留纯粹的压制逻辑。勾选后才显示「插件加载成功」「已生效」等信息。
 *
 * 宿主的某些版本在配置页保存时不会触发配置变更监听，所以每次要输出前
 * 都会重新从磁盘读一次；监听器只用来在「刚勾选」时立刻补一条当前状态，
 * 让用户不用等下一次状态变化就能看到反馈。两条路径都做了容错，
 * 配置 API 不可用时一律按「关闭」处理，绝不因为读配置而中断压制。
 */
final class UiHints {

    /** 必须与 preference_config.json 中的 key 保持一致。 */
    private static final String KEY = "ui.toast";

    private static volatile ConfigManager manager;
    private static volatile Consumer<ConfigHelper> listener;
    private static volatile boolean enabled = false;

    private UiHints() {
    }

    /** 主类构造与 start() 都会调用，重复注册由 listener 判空兜住。 */
    static void attach() {
        try {
            ConfigManager m = manager();
            if (m == null) return;
            if (listener == null) {
                Consumer<ConfigHelper> l = changed -> {
                    boolean before = enabled;
                    enabled = read(changed);
                    // 从关到开的那一刻补一条当前状态，勾选后立刻有反馈。
                    if (enabled && !before) NoStartPlugin.reportCurrentStatus();
                };
                m.addConfigChangeListener(l);
                listener = l;
            }
            enabled = read(m.getConfig());
        } catch (Throwable ignored) {
            enabled = false;
        }
    }

    /** 停用插件时注销监听，避免反复启用后重复注册。 */
    static void detach() {
        try {
            ConfigManager m = manager;
            Consumer<ConfigHelper> l = listener;
            if (m != null && l != null) m.removeConfigChangeListener(l);
        } catch (Throwable ignored) {
        }
        listener = null;
    }

    /** 每次输出前重新读盘：配置页写入的是磁盘文件，reload() 最可靠。 */
    static boolean enabled() {
        try {
            ConfigManager m = manager();
            if (m == null) return false;
            ConfigHelper c = m.getConfig();
            c.reload();
            enabled = read(c);
        } catch (Throwable ignored) {
            // 读失败沿用上次结果；若从未读过则保持默认关闭。
        }
        return enabled;
    }

    private static ConfigManager manager() {
        ConfigManager m = manager;
        if (m == null) {
            try {
                m = WorkshopApi.manager().createConfigManager();
                manager = m;
            } catch (Throwable ignored) {
                return null;
            }
        }
        return m;
    }

    private static boolean read(ConfigHelper c) {
        try {
            return Boolean.TRUE.equals(c.get(KEY, Boolean.FALSE));
        } catch (Throwable ignored) {
            return false;
        }
    }
}
