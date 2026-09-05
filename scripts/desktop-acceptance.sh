#!/bin/zsh
# Desktop 验收实例的确定性强杀与重启（CORE 基建）。
#
# 背景：`pkill -f "com.virjar.tk.desktop"` 匹配不到任何进程——真实主类是
# com.virjar.tk.desktop.TeamTalkMain（desktop/build.gradle.kts 的 mainClass）。残留实例同时持有
# FileLock（新实例弹 "Another instance is already running"）和 18080 端口（验收工具
# 继续对话僵尸实例，把旧代码误当成新代码）。
#
# 本脚本流程：
#   1. 记录当前 /ping 的实例令牌（若有）；
#   2. 按正确主类模式与 18080 端口占用者强杀所有 TeamTalk Desktop 进程；
#   3. 等待端口释放与 FileLock 释放；
#   4. 以显式随机令牌启动 ./gradlew :client:desktop:run；
#   5. 轮询 /ping 直到 instanceToken 等于本次令牌（超时则打印诊断并退出 1）。
#
# 用法：
#   scripts/desktop-acceptance.sh            # 杀旧实例 + 启动 + 等待新实例就绪
#   scripts/desktop-acceptance.sh kill       # 只清理（不启动）
#   环境变量 TK_DESKTOP_START_TIMEOUT 秒数（默认 120）

set -euo pipefail

REPO_ROOT="${0:A:h:h}"
PORT=18080
MAIN_CLASS_PATTERN="com.virjar.tk.desktop.TeamTalkMain"
TOKEN="$(uuidgen | tr -d '-' | cut -c1-12)"
START_TIMEOUT="${TK_DESKTOP_START_TIMEOUT:-120}"
LOG_FILE="${TMPDIR:-/tmp}/tk-desktop-acceptance-run.log"

ping_token() {
  curl -s --max-time 2 "http://127.0.0.1:${PORT}/ping" 2>/dev/null \
    | python3 -c 'import json,sys
try:
    print(json.load(sys.stdin).get("instanceToken", ""))
except Exception:
    print("")' 2>/dev/null || echo ""
}

port_owner_pids() {
  lsof -nP -iTCP:${PORT} -sTCP:LISTEN -t 2>/dev/null | sort -u || true
}

main_class_pids() {
  pgrep -f "${MAIN_CLASS_PATTERN}" 2>/dev/null || true
}

kill_all() {
  local pids
  pids="$( (port_owner_pids; main_class_pids) | sort -u | tr '\n' ' ' )"
  if [[ -n "${pids// /}" ]]; then
    echo "[desktop-acceptance] killing PIDs: ${pids}"
    # zsh 不做词分割，必须 ${=pids} 展开成多个参数，否则整个串被当成单个 PID。
    kill -9 ${=pids} 2>/dev/null || true
    sleep 1
  fi
  # 等端口释放（最多 10s）
  local i=0
  while (( i < 10 )); do
    [[ -z "$(port_owner_pids)" ]] && break
    sleep 1
    (( i += 1 ))
  done
  if [[ -n "$(port_owner_pids)" ]]; then
    echo "[desktop-acceptance] ERROR: port ${PORT} still held by $(port_owner_pids)" >&2
    return 1
  fi
}

diagnose() {
  echo "[desktop-acceptance] DIAGNOSIS:" >&2
  echo "  current /ping token : $(ping_token)" >&2
  echo "  expected token      : ${TOKEN}" >&2
  echo "  port ${PORT} owners   : $(port_owner_pids)" >&2
  echo "  main-class processes: $(main_class_pids | tr '\n' ' ')" >&2
  echo "  run log tail:" >&2
  tail -20 "${LOG_FILE}" >&2 || true
}

case "${1:-restart}" in
  kill)
    kill_all
    echo "[desktop-acceptance] cleanup done"
    exit 0
    ;;
  restart|"")
    ;;
  *)
    echo "usage: $0 [kill]" >&2
    exit 2
    ;;
esac

OLD_TOKEN="$(ping_token)"
echo "[desktop-acceptance] old instance token: ${OLD_TOKEN:-<none>}"
kill_all

echo "[desktop-acceptance] starting :client:desktop:run with token ${TOKEN}"
( cd "${REPO_ROOT}" && ./gradlew :client:desktop:run -Ptk.desktop.instanceToken="${TOKEN}" \
    > "${LOG_FILE}" 2>&1 & )

echo -n "[desktop-acceptance] waiting for instance token"
waited=0
while (( waited < START_TIMEOUT )); do
  CURRENT="$(ping_token)"
  if [[ "${CURRENT}" == "${TOKEN}" ]]; then
    echo ""
    echo "[desktop-acceptance] READY: instance ${TOKEN} owns 127.0.0.1:${PORT}"
    exit 0
  fi
  # 明确的僵尸实例：令牌是旧的、端口有人
  if [[ -n "${CURRENT}" && "${CURRENT}" == "${OLD_TOKEN}" && "${OLD_TOKEN}" != "" ]]; then
    echo ""
    echo "[desktop-acceptance] STALE INSTANCE: /ping still reports old token ${OLD_TOKEN}" >&2
    diagnose
    exit 3
  fi
  echo -n "."
  sleep 2
  (( waited += 2 ))
done

echo ""
echo "[desktop-acceptance] TIMEOUT after ${START_TIMEOUT}s" >&2
diagnose
exit 1
