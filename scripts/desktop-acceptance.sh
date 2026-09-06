#!/bin/zsh
# 只重启指定测试端口上的 Desktop 实例，保留其他安装和验收进程。
# 停止前同时核对 /ping 的 PID/令牌、端口监听者与 Java 主类；身份不明确时拒绝操作。
#
# 用法：
#   scripts/desktop-acceptance.sh             # 停止目标实例、启动并确认新令牌
#   scripts/desktop-acceptance.sh kill        # 只停止目标实例
#   TEAMTALK_DESKTOP_TEST_PORT=18081 scripts/desktop-acceptance.sh
#   TK_DESKTOP_START_TIMEOUT=180 scripts/desktop-acceptance.sh

set -euo pipefail

REPO_ROOT="${0:A:h:h}"
PORT="${TEAMTALK_DESKTOP_TEST_PORT:-18080}"
MAIN_CLASS="com.virjar.tk.desktop.TeamTalkMain"
TOKEN="$(uuidgen | tr -d '-' | cut -c1-12)"
START_TIMEOUT="${TK_DESKTOP_START_TIMEOUT:-120}"

if ! python3 -c 'import sys; p=sys.argv[1]; sys.exit(not (p.isascii() and p.isdigit() and 1 <= int(p) <= 65535))' "${PORT}"; then
  echo "[desktop-acceptance] ERROR: TEAMTALK_DESKTOP_TEST_PORT must be in 1..65535" >&2
  exit 2
fi
for required in curl lsof ps python3; do
  command -v "${required}" >/dev/null || { echo "Missing command: ${required}" >&2; exit 2; }
done
LOG_FILE="${TMPDIR:-/tmp}/tk-desktop-acceptance-${PORT}.log"

ping_identity() {
  curl --fail -s --max-time 2 "http://127.0.0.1:${PORT}/ping" 2>/dev/null \
    | python3 -c 'import json,sys
try:
    data=json.load(sys.stdin); pid=data.get("pid"); token=data.get("instanceToken")
    if data.get("status") != "ok" or type(pid) is not int or pid <= 1:
        raise ValueError("invalid pid")
    if not isinstance(token,str) or not token or any(ord(c) < 32 or ord(c) == 127 for c in token):
        raise ValueError("invalid token")
    print(str(pid)+"\t"+token)
except Exception:
    sys.exit(1)' 2>/dev/null
}

ping_token() {
  local identity
  identity="$(ping_identity)" || return 1
  print -r -- "${identity#*$'\t'}"
}

port_owner_pids() {
  lsof -nP -iTCP:${PORT} -sTCP:LISTEN -t 2>/dev/null | sort -u || true
}

is_teamtalk_process() {
  ps -p "$1" -o args= 2>/dev/null | python3 -c 'import shlex,sys
try:
    args=shlex.split(sys.stdin.read())
    valid=bool(args) and args[0].rsplit("/",1)[-1] == "java" and sys.argv[1] in args
    tokens=[a.split("=",1)[1] for a in args if a.startswith("-Dtk.desktop.instance.token=")]
    sys.exit(not valid or (bool(tokens) and tokens != [sys.argv[2]]))
except Exception:
    sys.exit(1)' "${MAIN_CLASS}" "$2"
}

stop_instance() {
  local owners identity target_pid target_token started current_started i
  owners="$(port_owner_pids)"
  [[ -z "${owners}" ]] && return 0
  identity="$(ping_identity)" || {
    echo "[desktop-acceptance] ERROR: port ${PORT} is occupied but /ping does not identify a Desktop instance; nothing stopped" >&2
    return 1
  }
  target_pid="${identity%%$'\t'*}"
  target_token="${identity#*$'\t'}"
  if [[ "${owners}" != "${target_pid}" ]] || ! is_teamtalk_process "${target_pid}" "${target_token}"; then
    echo "[desktop-acceptance] ERROR: port owner, /ping PID/token and TeamTalk process do not match; nothing stopped" >&2
    return 1
  fi
  started="$(ps -p "${target_pid}" -o lstart= 2>/dev/null)"
  # 紧邻停止操作再次确认，防止把端口刚换来的其他实例当成上一步的目标。
  if [[ -z "${started}" || "$(port_owner_pids)" != "${target_pid}" || "$(ping_identity)" != "${identity}" ]]; then
    echo "[desktop-acceptance] ERROR: target instance changed during identification; nothing stopped" >&2
    return 1
  fi
  echo "[desktop-acceptance] stopping PID ${target_pid}, instance ${target_token}, port ${PORT}"
  kill -TERM "${target_pid}" 2>/dev/null || true
  for i in {1..10}; do
    current_started="$(ps -p "${target_pid}" -o lstart= 2>/dev/null || true)"
    [[ "${current_started}" != "${started}" ]] && break
    sleep 1
  done
  current_started="$(ps -p "${target_pid}" -o lstart= 2>/dev/null || true)"
  if [[ "${current_started}" == "${started}" ]]; then
    # 优雅退出超时后只强制结束同一 PID、启动时间和已确认主类的进程，绝不按主类批量清理。
    if ! is_teamtalk_process "${target_pid}" "${target_token}"; then
      echo "[desktop-acceptance] ERROR: target process identity changed; refusing forced stop" >&2
      return 1
    fi
    kill -KILL "${target_pid}" 2>/dev/null || true
  fi
  for i in {1..10}; do
    [[ -z "$(port_owner_pids)" ]] && return 0
    sleep 1
  done
  echo "[desktop-acceptance] ERROR: port ${PORT} is still occupied; no other process will be stopped" >&2
  return 1
}

diagnose() {
  echo "[desktop-acceptance] DIAGNOSIS:" >&2
  echo "  current /ping token : $(ping_token || true)" >&2
  echo "  expected token      : ${TOKEN}" >&2
  echo "  port ${PORT} owners   : $(port_owner_pids)" >&2
  echo "  run log tail:" >&2
  tail -20 "${LOG_FILE}" >&2 || true
}

case "${1:-restart}" in
  kill)
    stop_instance
    echo "[desktop-acceptance] selected port cleanup done"
    exit 0
    ;;
  restart|"") ;;
  *)
    echo "usage: $0 [kill]" >&2
    exit 2
    ;;
esac

OLD_TOKEN="$(ping_token || true)"
echo "[desktop-acceptance] old instance token on ${PORT}: ${OLD_TOKEN:-<none>}"
stop_instance

echo "[desktop-acceptance] starting :client:desktop:run with token ${TOKEN}, port ${PORT}"
( cd "${REPO_ROOT}" && ./gradlew :client:desktop:run -Ptk.desktop.instanceToken="${TOKEN}" \
    -Dtk.desktop.test.port="${PORT}" > "${LOG_FILE}" 2>&1 & )

echo -n "[desktop-acceptance] waiting for instance token"
waited=0
while (( waited < START_TIMEOUT )); do
  CURRENT="$(ping_token || true)"
  if [[ "${CURRENT}" == "${TOKEN}" ]]; then
    echo ""
    echo "[desktop-acceptance] READY: instance ${TOKEN} owns 127.0.0.1:${PORT}"
    exit 0
  fi
  if [[ -n "${CURRENT}" && "${CURRENT}" == "${OLD_TOKEN}" ]]; then
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
