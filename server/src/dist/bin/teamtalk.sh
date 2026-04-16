#!/bin/bash
APP_HOME="$(cd "$(dirname "$0")/.." && pwd)"

mkdir -p "$APP_HOME/logs"
mkdir -p "$APP_HOME/data"

# 加载环境变量（systemd EnvironmentFile 或手动部署）
if [ -f "$APP_HOME/env.sh" ]; then
    set -a
    source "$APP_HOME/env.sh"
    set +a
fi

# 直接设置 JAVA_OPTS（Gradle 生成的 bin/server 脚本会读取）
export JAVA_OPTS="-Dconfig.file=$APP_HOME/conf/application.conf -Dlogback.configurationFile=$APP_HOME/conf/logback.xml"

exec "$APP_HOME/bin/server" "$@"
