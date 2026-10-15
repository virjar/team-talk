#!/bin/sh
set -eu
if [ "${OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED:-NO}" = "YES" ]; then
    exit 0
fi
cd "$SRCROOT/../.."
# Xcode 从 GUI 启动不继承 shell 的 JAVA_HOME，多 JDK 主机会落到过低的系统默认
# （buildSrc 需要 JDK 21 的 API）。先用系统注册的 JDK 21 兜底，仍找不到时交由 PATH 解析。
if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    [ -n "$JAVA_HOME" ] && export JAVA_HOME
fi
./gradlew :client:ios:generateXcodeConfiguration :client:ios:embedAndSignAppleFrameworkForXcode
# Xcode 增量构建不跟踪经 OTHER_LDFLAGS 链接的静态框架：Kotlin 源码变化后不删除
# 旧链接产物，应用会静默携带过期 Kotlin 代码。本阶段先于链接执行，删除产物以
# 强制本轮用最新框架重链（Swift 编译本身仍是增量的）。
rm -f "$TARGET_BUILD_DIR/$FULL_PRODUCT_NAME/$PRODUCT_NAME.debug.dylib" \
    "$TARGET_BUILD_DIR/$FULL_PRODUCT_NAME/$PRODUCT_NAME"
