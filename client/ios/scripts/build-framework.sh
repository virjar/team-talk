#!/bin/sh
set -eu
if [ "${OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED:-NO}" = "YES" ]; then
    exit 0
fi
cd "$SRCROOT/../.."
./gradlew :client:ios:generateXcodeConfiguration :client:ios:embedAndSignAppleFrameworkForXcode
