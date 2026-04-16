#!/bin/bash
APP_HOME="$(cd "$(dirname "$0")/.." && pwd)"
PID_FILE="$APP_HOME/logs/teamtalk.pid"

if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    if kill "$PID" 2>/dev/null; then
        echo "TeamTalk stopped (PID: $PID)"
    else
        echo "Process $PID not found"
    fi
    rm -f "$PID_FILE"
else
    if pkill -f "com.virjar.tk.ApplicationKt"; then
        echo "TeamTalk stopped"
    else
        echo "TeamTalk is not running"
    fi
fi
