#!/bin/bash
# TeamTalk 服务端启动脚本。
#
# 由 server 模块 installDist 打包到 bin/，随部署同步到 /opt/teamtalk/bin/。
# 职责：加载 conf/env.sh（数据库密码、SSL 证书等敏感配置）、
#       设置 JAVA_OPTS（指向 application.conf 与 logback.xml）、exec Gradle 生成的 bin/server。
#
# 为什么需要它（而非直接调 bin/server）：
# systemd 的 EnvironmentFile 只注入变量，但 bin/server 需要通过 JAVA_OPTS 传入
# -Dconfig.file / -Dlogback.configurationFile；本脚本桥接两者。

APP_HOME="$(cd "$(dirname "$0")/.." && pwd)"

mkdir -p "$APP_HOME/logs"
mkdir -p "$APP_HOME/data"

# 加载环境变量（优先 conf/env.sh，兼容旧版 env.sh）
if [ -f "$APP_HOME/conf/env.sh" ]; then
    set -a
    source "$APP_HOME/conf/env.sh"
    set +a
elif [ -f "$APP_HOME/env.sh" ]; then
    set -a
    source "$APP_HOME/env.sh"
    set +a
fi

# 直接设置 JAVA_OPTS（Gradle 生成的 bin/server 脚本会读取）
export JAVA_OPTS="-Dconfig.file=$APP_HOME/conf/application.conf -Dlogback.configurationFile=$APP_HOME/conf/logback.xml"

exec "$APP_HOME/bin/server" "$@"
