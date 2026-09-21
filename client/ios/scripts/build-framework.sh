#!/bin/sh
set -eu
if [ "${OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED:-NO}" = "YES" ]; then
    exit 0
fi
cd "$SRCROOT/../.."
./gradlew :client:ios:generateXcodeConfiguration :client:ios:embedAndSignAppleFrameworkForXcode
# Xcode 增量构建不跟踪经 OTHER_LDFLAGS 链接的静态框架：Kotlin 源码变化后不删除
# 旧链接产物，应用会静默携带过期 Kotlin 代码。本阶段先于链接执行，删除产物以
# 强制本轮用最新框架重链（Swift 编译本身仍是增量的）。
rm -f "$TARGET_BUILD_DIR/$FULL_PRODUCT_NAME/$PRODUCT_NAME.debug.dylib" \
    "$TARGET_BUILD_DIR/$FULL_PRODUCT_NAME/$PRODUCT_NAME"
