# 原神，不启动！

> 修复[原神，启动！]模组时播放HOYO-MiX时会自动启动国服原神，未安装则打开启动器或云原神的bug

针对的插件：[GaBoron/Genshin-Start-for-SPW](https://github.com/GaBoron/Genshin-Start-for-SPW)

- 插件 ID：`spw-genshin-no-start`
- 适用平台：Salt Player for Windows（SPW，需要 1.19+ 创意工坊支持）

一个整活向的「反整活补丁」：装上它之后，播放 HOYO-MiX 的歌曲不会再让《原神，启动！》把原神、米哈游启动器或云原神网页拉起来。

它**不创建子进程、不读写文件、不碰注册表、不修改对方的配置文件、不申请任何插件权限**，只在 JVM 里调整对方插件的运行时对象；停用本插件即恢复原状。

## 安装

在Steam创意工坊直接订阅然后启用插件
https://steamcommunity.com/sharedfiles/filedetails/?id=3839726604
或
1. 在 [Releases](https://github.com/Neko520nya/spw-genshin-no-start/releases) 下载 `spw-genshin-no-start-1.5.0.spmod`（仓库根目录也保留了一份）。
2. 打开 SPW → 设置 → 创意工坊 → 模组管理 → 导入模组，选择该 `.spmod`。
3. **启用插件后完整退出并重新启动 SPW**。热加载/仅切歌不会让守护线程正确挂载。
4. 验证：播放任意 HOYO-MiX 曲目并等待 40 秒以上，原神不再被启动。

## 配置

模组配置页只有一个开关：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| 界面提示 | 关闭 | 勾选后才会显示「插件加载成功」「已生效：回调静默 + 触发器锁死 4/4」等提示；关闭时插件完全静默，也不写状态日志。 |

勾选的瞬间会补一条当前生效状态，不需要等下一次状态变化。日志排查时可临时打开，平时建议保持关闭。

## 原理（简版）

对方插件的播放回调依赖 `GenshinPlugin.current` 静态实例分发，拿到实例后才生成启动票据。本插件从官方 `WorkshopApi` 服务对象出发做有限深度的对象图搜索，定位到对方真实的 `PluginClassLoader` 与类，然后每 200ms 重新施加：

- `current = null`：让播放回调静默，根本不产出启动票据（核心一击）；
- 触发器的 `closed = true`、`enabled = false`、`attempted = true`、`pending = false`：即使对方重建实例也不会补发；
- 停用插件时把所有字段与静态实例还原。

不使用 `Plugin.stop()`：它会把 trigger 置空，反而清掉对方 `start()` 的幂等保护。

## 已知限制

- 通过反射访问对方插件的内部字段，命中依赖当前《原神，启动！》的实现细节；对方插件或 SPW 更新后可能失效，届时需要重新适配。
- Workshop API 仍处于试验阶段，本插件未声明任何 `PluginPermission`。
- 仅在 Windows + SPW 1.19 系上实测；不保证覆盖 Linux 与微软商店渠道。
- 纯整活用途，与《原神，启动！》作者及米哈游无任何关联，仓库不包含对方代码或资源。

## 从源码构建

需要 **JDK 25**（官方 API 字节码为 Java 25）以及两个 provided 依赖：

- `spw-api.jar`：从 [Moriafly/spw-workshop-api Releases](https://github.com/Moriafly/spw-workshop-api/releases) 获取；
- `pf4j.jar`：PF4J 对应版本。

把两个 jar 放到仓库的 `.build-tools/`（工作区共用时放到上一级 `.build-tools/`），然后在 Git Bash 中执行：

```bash
./build.sh
```

产物为 `spw-genshin-no-start-1.5.0.spmod`。可用 `JDK25_HOME=/path/to/jdk25 ./build.sh` 指定 JDK。脚本目前按 Windows 下的 `javac.exe` / `jar.exe` 编写，Linux 需自行调整可执行文件路径。

## 许可

MIT，详见 [LICENSE](LICENSE)。
