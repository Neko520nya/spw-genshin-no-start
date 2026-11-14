package io.github.nekonyan.genshinnostart;

import com.xuncorp.spw.workshop.api.PluginContext;
import com.xuncorp.spw.workshop.api.SpwPlugin;
import com.xuncorp.spw.workshop.api.WorkshopApi;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;

/**
 * 《原神，不启动！》v1.5.0
 *
 * 专门给《原神，启动！》(spw-genshin-start) 打的「反整活补丁」。
 * 全程只操作 JVM 里的对象：不创建子进程、不读写任何文件、不碰注册表、
 * 不改对方的设置、不申请任何 PluginPermission。停用本插件即恢复原状。
 *
 * 前四版为什么全失效（这次用 attach 到宿主 JVM 的只读诊断逐条证实）：
 *  - 1.0.0 押 PF4J STARTED 状态与 stopPlugin(id)：SPW 不走标准状态机。
 *  - 1.1.0 押宿主 start() 与扩展点回调：热加载时拿不到回调。
 *  - 1.2.0 押 getWrapper().getPluginManager()：实测 Plugin.wrapper == null，
 *    宿主根本没给插件设置 wrapper，A/C 两条通道恒为 false。
 *  - 1.3.0 押「自己 loader 链 + 线程 TCCL 能找到对方类」：实测 12+ 个
 *    PluginClassLoader 互为兄弟，从自己沿 getParent() 向上永远到不了；
 *    也没有任何线程把对方 loader 设为 TCCL。守护线程每轮都只返回
 *    「未找到对方插件 (A=false, C=false)」，一次压制都没执行。
 *
 * v1.4.0 的修法：TargetFinder 从官方 WorkshopApi 的服务对象出发做有限深度的
 * 对象图搜索，拿到对方真实的 PluginClassLoader（实测深度 2 命中），进而拿到
 * 真实的 GenshinPlugin Class 与实例。之后每 200ms 重施五重锁死：
 *   current=null（回调静默）、closed=true、enabled=false、attempted=true、pending=false。
 * 不使用 Plugin.stop()（它会把 trigger 置 null，反而清掉对方 start() 的幂等保护）。
 *
 * v1.5.0：新增配置页，里面只有「界面提示」一个开关，默认关闭；
 * 关闭时插件完全静默，不弹吐司也不写状态日志。
 */
public final class NoStartPlugin extends SpwPlugin {

    private static final Object LOCK = new Object();
    private static volatile Guardian guardian;
    private static volatile NoStartPlugin current;

    public NoStartPlugin(PluginContext context) {
        super(context);
        current = this;
        UiHints.attach();
        ensure();
    }

    /** 主类构造器、扩展类构造器、start() 共用；重复调用只把 paused 放回 false。 */
    static void ensure() {
        synchronized (LOCK) {
            if (guardian == null) {
                guardian = new Guardian();
                Thread thread = new Thread(guardian, "SPW Genshin No Start");
                thread.setDaemon(true);
                thread.setContextClassLoader(NoStartPlugin.class.getClassLoader());
                thread.start();
            }
            guardian.paused = false;
        }
    }

    static NoStartPlugin active() {
        return current;
    }

    @Override
    public void start() {
        current = this;
        UiHints.attach();
        ensure();
        report("v1.5.0 已加载，正在搜索对方插件");
    }

    /** 扩展点的心跳只是顺手唤醒一次，不再是必需路径。 */
    void beat() {
        ensure();
    }

    /** 停用即恢复：暂停守护，把所有锁过的触发器与静态实例还原。 */
    @Override
    public synchronized void stop() {
        if (current == this) current = null;
        Guardian g = guardian;
        if (g != null) g.restore();
        UiHints.detach();
    }

    /** 配置里刚勾选「界面提示」时立刻补一条当前状态，不用等下一次状态变化。 */
    static void reportCurrentStatus() {
        Guardian g = guardian;
        String status = g == null ? null : g.status();
        report(status == null || status.isEmpty() ? "已加载，正在搜索对方插件" : status);
    }

    /**
     * 三个出口都试一遍，任一成功即可看到诊断；全部失败也不影响压制。
     * 是否输出完全由配置开关决定，默认关闭时一个字都不发。
     */
    private static void report(String text) {
        if (!UiHints.enabled()) return;
        String message = "原神，不启动！ · " + text;
        try {
            WorkshopApi.ui().toast(message, WorkshopApi.Ui.ToastType.Success);
        } catch (Throwable ignored) {
        }
        try {
            System.out.println(message);
        } catch (Throwable ignored) {
        }
        try {
            NoStartPlugin self = current;
            if (self != null) {
                for (Class<?> c = self.getClass(); c != null; c = c.getSuperclass()) {
                    try {
                        Field f = c.getDeclaredField("log");
                        f.setAccessible(true);
                        Object logger = f.get(self);
                        if (logger != null) {
                            Method info = logger.getClass().getMethod("info", String.class);
                            info.invoke(logger, message);
                            break;
                        }
                    } catch (NoSuchFieldException ignored) {
                    } catch (Throwable ignored) {
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static final class Guardian implements Runnable {

        private static final long REFIND_INTERVAL_MS = 10_000L;

        private volatile boolean paused;
        private volatile String lastStatus = "";
        private volatile String pendingToast;

        private TargetFinder.Result found;
        private long lastFindAt;
        /** 所有被锁过的触发器，用于停用时的完整还原。 */
        private final IdentityHashMap<Object, Boolean> touched = new IdentityHashMap<>();
        private final IdentityHashMap<Object, Boolean> savedInstances = new IdentityHashMap<>();
        private boolean originalEnabled = true;

        /** 供配置开关打开时补报当前状态。 */
        String status() {
            return lastStatus;
        }

        void restore() {
            paused = true;
            for (Object trigger : touched.keySet()) {
                setBoolean(trigger, "closed", false);
                setBoolean(trigger, "enabled", originalEnabled);
                setBoolean(trigger, "attempted", false);
                setBoolean(trigger, "pending", false);
            }
            touched.clear();
            if (found != null && found.type != null) {
                for (Object instance : savedInstances.keySet()) {
                    setStatic(found.type, "current", instance);
                    break;
                }
            }
            savedInstances.clear();
            found = null;
            lastFindAt = 0;
        }

        @Override
        public void run() {
            while (true) {
                try {
                    if (!paused) {
                        String result = suppress();
                        if (!result.equals(lastStatus)) {
                            lastStatus = result;
                            pendingToast = result;
                        }
                    }
                } catch (Throwable ignored) {
                    // 守护线程绝不能因为一次异常退出。
                }
                String text = pendingToast;
                if (text != null) {
                    report(text);
                    pendingToast = null;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }

        private String suppress() {
            long now = System.currentTimeMillis();
            if (found == null || now - lastFindAt > REFIND_INTERVAL_MS) {
                lastFindAt = now;
                TargetFinder.Result r = TargetFinder.find();
                if (r != null && r.type != null) found = r;
            }
            TargetFinder.Result r = found;
            if (r == null || r.type == null) {
                return "未找到对方类（对象图无命中）";
            }

            // 每轮重新读静态 current：宿主若重建实例，这里能立刻捕捉。
            Object live = getStatic(r.type, "current");
            if (live != null) savedInstances.put(live, Boolean.TRUE);

            // 核心一击：dispatch() 取不到 active 实例，所有播放回调静默，
            // 压根不会产出启动票据。这一项成立就已经达成整活目标。
            boolean muted = setStatic(r.type, "current", null);

            // 收集触发器：优先本轮实例，其次对象图捞到的实例。
            Object instance = live != null ? live : r.instance;
            if (instance != null) {
                Object trigger = getField(instance, "trigger");
                if (trigger != null && touched.put(trigger, Boolean.TRUE) == null) {
                    Field enabled = field(trigger.getClass(), "enabled");
                    if (enabled != null) {
                        try {
                            originalEnabled = enabled.getBoolean(trigger);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }

            // 对所有已见过的触发器每轮重施四锁，不依赖本轮是否拿到实例。
            // v1.4.0 的「1/5」就是因为静态 current 被自己置空后再也拿不到实例，
            // 于是四个字段不再重施；这里改成持续压制，状态文案也不再误导。
            int triggerLocks = 0;
            for (Object t : touched.keySet().toArray()) {
                int n = 0;
                if (closeTrigger(t)) n++;
                if (setBoolean(t, "enabled", false)) n++;
                if (setBoolean(t, "attempted", true)) n++;
                if (setBoolean(t, "pending", false)) n++;
                if (n > triggerLocks) triggerLocks = n;
            }

            if (!muted) return "反射被拒绝：current 写入失败";
            if (touched.isEmpty()) return "已生效：回调静默（未取得触发器）· " + r.via;
            return "已生效：回调静默 + 触发器锁死 " + triggerLocks + "/4 · " + r.via;
        }

        private static Field field(Class<?> type, String name) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static Object getField(Object owner, String name) {
            Field f = field(owner.getClass(), name);
            if (f == null) return null;
            try {
                return f.get(owner);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static Object getStatic(Class<?> type, String name) {
            Field f = field(type, name);
            if (f == null) return null;
            try {
                return f.get(null);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static boolean setStatic(Class<?> type, String name, Object value) {
            Field f = field(type, name);
            if (f == null) return false;
            try {
                f.set(null, value);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        private static boolean setBoolean(Object owner, String name, boolean value) {
            Field f = field(owner.getClass(), name);
            if (f == null) return false;
            try {
                f.setBoolean(owner, value);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        private static boolean closeTrigger(Object trigger) {
            try {
                Method m = trigger.getClass().getDeclaredMethod("close");
                m.setAccessible(true);
                m.invoke(trigger);
            } catch (Throwable ignored) {
                // 下面用字段写入兜底。
            }
            return setBoolean(trigger, "closed", true);
        }
    }
}
