package io.github.nekonyan.genshinnostart;

import com.xuncorp.spw.workshop.api.WorkshopApi;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在宿主 JVM 里找到《原神，启动！》真实的 Class 对象与实例。
 *
 * 为什么需要这个：PF4J 给每个插件一个独立的 PluginClassLoader，彼此是「兄弟」，
 * 从自己出发沿 getParent() 向上永远到不了兄弟 loader；SPW 又不会给插件设置
 * Plugin.wrapper（实测为 null），也没有任何线程把对方 loader 设为 TCCL。
 * 实测 12+ 个 PluginClassLoader，只有对方自己那个能 Class.forName 到 GenshinPlugin。
 *
 * 于是改用对象图搜索：官方 WorkshopApi 的 ui/manager/library/hookRegistrar/playback
 * 全是宿主持有的服务对象，从它们出发做有限深度的字段遍历，能把所有插件的
 * ClassLoader 和实例都带出来。实测深度 2 命中 loader、深度 3 命中插件实例。
 *
 * v1.4.1：不再一命中 loader 就返回，而是继续把对方的实例也捞出来。
 * 因为守护线程会把静态 current 置空，只靠读静态字段永远拿不到实例，
 * 进而无法锁住 PlaybackTrigger。
 *
 * 纯反射、只读、不写文件、不造进程。
 */
final class TargetFinder {

    static final String TARGET_CLASS = "io.github.gaboron.spwgenshin.GenshinPlugin";
    private static final String TARGET_PACKAGE = "io.github.gaboron.spwgenshin.";

    /** 搜索结果：对方主类的 Class 对象 + 实例（可能为 null）。 */
    static final class Result {
        Class<?> type;
        Object instance;
        String via;
        int nodes;
        int depth;

        @Override public String toString() {
            return "via=" + via + " nodes=" + nodes + " depth=" + depth
                    + " instance=" + (instance != null);
        }
    }

    private TargetFinder() {
    }

    static Result find() {
        List<Object> roots = roots();
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        List<Object> layer = roots;
        int nodes = 0;
        Class<?> type = null;
        Object instance = null;
        String via = null;
        int hitDepth = -1;
        int hitNodes = 0;

        outer:
        for (int depth = 0; depth < 4 && !layer.isEmpty() && nodes < 6000; depth++) {
            List<Object> next = new ArrayList<>();
            for (Object o : layer) {
                if (o == null || seen.put(o, Boolean.TRUE) != null) continue;
                nodes++;
                String name = o.getClass().getName();

                if (type == null) {
                    Class<?> hit = null;
                    String how = null;
                    if (o instanceof Class<?> cc) {
                        hit = tryLoader(cc.getClassLoader());
                        how = "Class.loader@" + depth;
                        if (hit == null) continue; // Class 对象不展开，避免滚进整个 JDK 类型宇宙
                    } else if (o instanceof ClassLoader cl) {
                        hit = tryLoader(cl);
                        how = "PluginClassLoader@" + depth;
                    } else if (name.startsWith(TARGET_PACKAGE)) {
                        hit = tryLoader(o.getClass().getClassLoader());
                        how = "packageObj@" + depth;
                    }
                    if (hit != null) {
                        type = hit;
                        via = how;
                        hitDepth = depth;
                        hitNodes = nodes;
                    }
                }
                if (name.equals(TARGET_CLASS) && !(o instanceof Class<?>)) {
                    instance = o;
                    if (type == null) {
                        type = o.getClass();
                        via = "instance@" + depth;
                        hitDepth = depth;
                        hitNodes = nodes;
                    }
                }
                if (type != null && instance != null) break outer;

                // Class 对象不展开字段，避免滚进整个 JDK 类型宇宙（也会撞模块访问限制）。
                if (o instanceof Class<?>) continue;
                if (o instanceof String || o instanceof Number
                        || o instanceof Boolean || o instanceof Character) continue;

                for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers())) continue;
                        try {
                            f.setAccessible(true);
                            Object v = f.get(o);
                            if (v == null) continue;
                            if (v instanceof Object[] arr) {
                                for (Object x : arr) if (x != null) next.add(x);
                            } else if (v instanceof Map<?, ?> mp) {
                                int k = 0;
                                for (Map.Entry<?, ?> e : mp.entrySet()) {
                                    if (e.getKey() != null) next.add(e.getKey());
                                    if (e.getValue() != null) next.add(e.getValue());
                                    if (++k > 150) break;
                                }
                            } else if (v instanceof Iterable<?> it) {
                                int k = 0;
                                for (Object x : it) {
                                    if (x != null) next.add(x);
                                    if (++k > 150) break;
                                }
                            } else {
                                next.add(v);
                            }
                        } catch (Throwable ignored) {
                            // JDK 内部类的字段可能拒绝访问，跳过即可。
                        }
                    }
                }
            }
            layer = next;
        }

        if (type == null) return null;
        Result r = new Result();
        r.type = type;
        r.instance = instance != null ? instance : readStatic(type, "current");
        r.via = via;
        r.nodes = hitNodes > 0 ? hitNodes : nodes;
        r.depth = hitDepth >= 0 ? hitDepth : 3;
        return r;
    }

    /** 搜索起点全部来自官方 API 与自身上下文，不依赖任何宿主私有类名。 */
    private static List<Object> roots() {
        List<Object> roots = new ArrayList<>();
        try {
            for (Method m : WorkshopApi.class.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0) continue;
                try {
                    m.setAccessible(true);
                    Object v = m.invoke(null);
                    if (v != null) roots.add(v);
                } catch (Throwable ignored) {
                    // 个别方法可能要求 UI 就绪。
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Field f = WorkshopApi.class.getDeclaredField("Companion");
            f.setAccessible(true);
            Object companion = f.get(null);
            if (companion != null) {
                roots.add(companion);
                for (Field cf : companion.getClass().getDeclaredFields()) {
                    try {
                        cf.setAccessible(true);
                        Object v = Modifier.isStatic(cf.getModifiers())
                                ? cf.get(null) : cf.get(companion);
                        if (v != null) roots.add(v);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        NoStartPlugin self = NoStartPlugin.active();
        if (self != null) {
            roots.add(self);
            for (Class<?> c = self.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(self);
                        if (v != null) roots.add(v);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        roots.addAll(Thread.getAllStackTraces().keySet());
        return roots;
    }

    private static Class<?> tryLoader(ClassLoader cl) {
        if (cl == null) return null;
        try {
            return Class.forName(TARGET_CLASS, false, cl);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object readStatic(Class<?> type, String name) {
        try {
            Field f = type.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
