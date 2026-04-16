#!/bin/bash
APP_HOME="$(cd "$(dirname "$0")/.." && pwd)"

mkdir -p "$APP_HOME/logs"
mkdir -p "$APP_HOME/data"

# 通过 JAVA_OPTS 传递 JVM 系统属性（必须放在主类之前才能被 System.getProperty 识别）
export JAVA_OPTS="-Dconfig.file=$APP_HOME/conf/application.conf -Dlogback.configurationFile=$APP_HOME/conf/logback.xml"

nohup "$APP_HOME/bin/server" \
    "$@" > /dev/null 2>&1 &
echo $! > "$APP_HOME/logs/teamtalk.pid"
echo "TeamTalk started (PID: $!)"
