#!/bin/bash
set -euo pipefail

# =============================================================================
# TeamTalk Release 构建脚本
#
# 用法:
#   ./build-release.sh [OPTIONS]
#
# 选项:
#   --server-url URL      服务端 HTTP 地址（固化到客户端包中）
#   --tcp-host HOST       服务端 TCP 地址
#   --tcp-port PORT       服务端 TCP 端口（默认 5100）
#   --version VERSION     版本号（默认 1.0.0）
#   --desktop-only        只构建 Desktop
#   --android-only        只构建 Android
#   --upload HOST         构建后上传到指定服务器
# =============================================================================

RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m'

info()  { echo -e "${BLUE}[INFO]${NC} $*"; }
ok()    { echo -e "${GREEN}[OK]${NC} $*"; }
fail()  { echo -e "${RED}[FAIL]${NC} $*"; exit 1; }

SERVER_URL=""
TCP_HOST=""
TCP_PORT="5100"
VERSION="1.0.0"
DESKTOP_ONLY=false
ANDROID_ONLY=false
UPLOAD_HOST=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --server-url) SERVER_URL="$2"; shift 2 ;;
        --tcp-host) TCP_HOST="$2"; shift 2 ;;
        --tcp-port) TCP_PORT="$2"; shift 2 ;;
        --version) VERSION="$2"; shift 2 ;;
        --desktop-only) DESKTOP_ONLY=true; shift ;;
        --android-only) ANDROID_ONLY=true; shift ;;
        --upload) UPLOAD_HOST="$2"; shift 2 ;;
        -h|--help)
            echo "用法: $0 --server-url URL --tcp-host HOST [--tcp-port PORT] [--version VERSION] [--desktop-only|--android-only] [--upload HOST]"
            exit 0
            ;;
        *) echo "未知选项: $1"; exit 1 ;;
    esac
done

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

echo ""
echo -e "${BLUE}╔══════════════════════════════════════╗${NC}"
echo -e "${BLUE}║     TeamTalk Release Build           ║${NC}"
echo -e "${BLUE}╚══════════════════════════════════════╝${NC}"
echo ""
echo "  Version:    $VERSION"
echo "  Server URL: ${SERVER_URL:-<not set>}"
echo "  TCP Host:   ${TCP_HOST:-<not set>}"
echo "  TCP Port:   $TCP_PORT"
echo ""

GRADLE_ARGS="-PpackageVersion=$VERSION"
if [[ -n "$SERVER_URL" ]]; then
    GRADLE_ARGS="$GRADLE_ARGS -PSERVER_BASE_URL=$SERVER_URL -Pdeploy.url=$SERVER_URL"
fi
if [[ -n "$TCP_HOST" ]]; then
    GRADLE_ARGS="$GRADLE_ARGS -PTCP_HOST=$TCP_HOST -Pdeploy.tcpHost=$TCP_HOST"
fi
GRADLE_ARGS="$GRADLE_ARGS -PTCP_PORT=$TCP_PORT"

# ===== Server =====
info "Building Server distribution..."
./gradlew :server:buildServerDist --quiet
ok "Server distribution built"

# ===== Desktop =====
if [[ "$ANDROID_ONLY" != "true" ]]; then
    info "Building Desktop packages..."
    ./gradlew :desktop:packageDistributionForCurrentOS $GRADLE_ARGS --quiet

    echo ""
    info "Desktop packages:"
    find desktop/build/compose/binaries/main/ -type f \( -name "*.deb" -o -name "*.msi" -o -name "*.dmg" \) 2>/dev/null | while read f; do
        echo "  $(ls -lh "$f" | awk '{print $5, $NF}')"
    done || true
    ok "Desktop packages built"
fi

# ===== Android =====
if [[ "$DESKTOP_ONLY" != "true" ]]; then
    info "Building Android APK..."
    ./gradlew :android:assembleRelease $GRADLE_ARGS --quiet

    echo ""
    info "Android APK:"
    find android/build/outputs/apk/release/ -name "*.apk" -type f 2>/dev/null | while read f; do
        echo "  $(ls -lh "$f" | awk '{print $5, $NF}')"
    done || true
    ok "Android APK built"
fi

# ===== Upload =====
if [[ -n "$UPLOAD_HOST" ]]; then
    info "Uploading packages to $UPLOAD_HOST..."
    DEPLOY_PATH="/opt/teamtalk"
    ssh "root@$UPLOAD_HOST" "mkdir -p $DEPLOY_PATH/static/downloads"

    # Desktop packages
    find desktop/build/compose/binaries/main/ -type f \( -name "*.deb" -o -name "*.msi" -o -name "*.dmg" \) 2>/dev/null | while read f; do
        info "  Uploading $(basename "$f") ..."
        scp "$f" "root@$UPLOAD_HOST:$DEPLOY_PATH/static/downloads/"
    done || true

    # Android APK
    local_apk=$(find android/build/outputs/apk/release/ -name "*-release.apk" -type f 2>/dev/null | head -1)
    if [[ -n "$local_apk" ]]; then
        info "  Uploading TeamTalk-android.apk ..."
        scp "$local_apk" "root@$UPLOAD_HOST:$DEPLOY_PATH/static/downloads/TeamTalk-android.apk"
    fi

    ok "All packages uploaded to $UPLOAD_HOST"
    echo ""
    echo "  Download page: https://$UPLOAD_HOST/"
fi

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}       Build Complete!                  ${NC}"
echo -e "${GREEN}========================================${NC}"
