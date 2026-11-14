package io.github.nekonyan.genshinnostart;

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint;
import org.pf4j.Extension;

/**
 * 播放扩展点：第二个「必然被实例化」的入口。
 *
 * 1.1.0 把它当成主心跳，但热加载时宿主缓存的扩展列表里没有新插件，回调一次都没来。
 * 现在压制完全由静态单例守护线程负责，这里只做两件顺手的事：
 *  1. 构造器里也调一次 ensure() —— 万一宿主只实例化扩展类、没实例化主类，
 *     守护线程照样会起来；
 *  2. 宿主确实分发回调时，再唤醒一次守护线程。
 *
 * 不接管歌词：onBeforeLoadLyrics 一律返回 null，对播放行为零影响。
 */
@Extension
public final class NoStartExtension implements PlaybackExtensionPoint {

    public NoStartExtension() {
        NoStartPlugin.ensure();
    }

    @Override
    public void onPositionUpdated(long position) {
        NoStartPlugin plugin = NoStartPlugin.active();
        if (plugin != null) plugin.beat();
    }

    @Override
    public String onBeforeLoadLyrics(MediaItem mediaItem) {
        NoStartPlugin plugin = NoStartPlugin.active();
        if (plugin != null) plugin.beat();
        return null;
    }
}
