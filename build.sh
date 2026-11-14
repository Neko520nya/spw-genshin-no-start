#!/usr/bin/env bash
# 《原神，不启动！》构建脚本
# 只需要 JDK 25 自带的 javac / jar，不需要 Gradle，编译期依赖是 provided（运行时由 SPW 提供）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
WORK="$ROOT/build"
# 本机开发时依赖放在工作区共用的 ../.build-tools；单独 clone 本仓库时放在自己的 .build-tools。
if [ -d "$ROOT/../.build-tools" ]; then
  TOOLS="$(cd "$ROOT/../.build-tools" && pwd)"
else
  TOOLS="$ROOT/.build-tools"
fi
JDK="${JDK25_HOME:-/c/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot}"
JAVAC="$JDK/bin/javac.exe"
JAR="$JDK/bin/jar.exe"
API="$TOOLS/spw-api.jar"
PF4J="$TOOLS/pf4j.jar"
OUT="$ROOT/spw-genshin-no-start-1.5.0.spmod"

for f in "$JAVAC" "$JAR" "$API" "$PF4J"; do
  if [ ! -f "$f" ]; then echo "缺少：$f" >&2; exit 1; fi
done

# Git Bash 的 /c/... 路径 Windows 版 javac / jar 不认，统一转成 C:/... 再传参。
winpath() { cygpath -m "$1"; }

# 只清理本次生成的文件，保留 diagnose/ 等其它内容，也不做递归删目录。
mkdir -p "$WORK/classes/META-INF" "$WORK/spmod/classes"
find "$WORK/classes" "$WORK/spmod" -type f -delete 2>/dev/null || true
rm -f "$WORK/plugin.jar" "$WORK/MANIFEST.MF" 2>/dev/null || true

CLASSES="$(winpath "$WORK/classes")"
SRC_ARGS=()
while IFS= read -r -d '' f; do
  SRC_ARGS+=("$(winpath "$f")")
done < <(find "$ROOT/src/main/java" -name '*.java' -print0)

# 官方 API 0.1.0-dev23 的字节码是 Java 25（major 69），因此这里也用 25。
"$JAVAC" -J-Duser.language=en -J-Duser.country=US --release 25 -encoding UTF-8 \
  -cp "$(winpath "$API");$(winpath "$PF4J")" -d "$CLASSES" "${SRC_ARGS[@]}"
# 扩展点索引放 META-INF，配置界面定义放类路径根目录。
cp "$ROOT/src/main/resources/META-INF/extensions.idx" "$WORK/classes/META-INF/"
cp "$ROOT/src/main/resources/preference_config.json" "$WORK/classes/"

cat > "$WORK/MANIFEST.MF" <<'EOF'
Manifest-Version: 1.0
Plugin-Class: io.github.nekonyan.genshinnostart.NoStartPlugin
Plugin-Id: spw-genshin-no-start
Plugin-Version: 1.5.0
Plugin-Provider: Neko520nya
Plugin-Name: 原神，不启动！
Plugin-Description: 修复[原神，启动！]模组时播放HOYO-MiX时会自动启动国服原神，未安装则打开启动器或云原神的bug
Plugin-Has-Config: true
Plugin-Open-Source-Url: https://github.com/Neko520nya/spw-genshin-no-start
EOF

# 先打成标准 jar，由 JDK 负责 MANIFEST.MF 的编码与长行折行。
"$JAR" --create --file "$(winpath "$WORK/plugin.jar")" \
  --manifest "$(winpath "$WORK/MANIFEST.MF")" -C "$CLASSES" .

# .spmod 的实际格式是把 jar 的内容整体放在 classes/ 下面，而不是嵌套一个 jar。
(cd "$WORK/spmod/classes" && "$JAR" --extract --file "$(winpath "$WORK/plugin.jar")")
(cd "$WORK/spmod" && "$JAR" --create --file "$(winpath "$OUT")" --no-manifest .)

echo "已生成：$OUT"
"$JAR" --list --file "$(winpath "$OUT")"
