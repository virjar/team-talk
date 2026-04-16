#!/bin/bash
set -euo pipefail

# =============================================================================
# TeamTalk 一键部署脚本
#
# 用法:
#   ./deploy.sh [user@]HOST [选项]
#
# 选项:
#   --path DIR             部署路径 (默认: /opt/teamtalk)
#   --port PORT            SSH 端口 (默认: 22)
#   --ssl-cert PEM_FILE    SSL 证书文件 (.pem/.crt)，配合 --ssl-key 启用 HTTPS
#   --ssl-key KEY_FILE     SSL 私钥文件 (.key)
#   --ssl-port PORT        HTTPS 端口 (默认: 443，仅与 --ssl-cert 一起使用)
#   --upload-packages      同时上传本地构建的客户端安装包
#   -h, --help             显示帮助
#
# 功能:
#   - 首次部署：构建 → 上传 → Docker 基础设施 → 配置(含SSL) → 启动 → 健康检查
#   - 升级部署：构建 → 备份 → 上传 → 重启 → 健康检查
#
# 前置要求:
#   - 本地: JDK 17+, rsync, openssl (启用 SSL 时)
#   - 服务器: Docker + Docker Compose, JDK 17+
#   - SSH 免密登录或 sshpass
#
# SSL 说明:
#   提供 --ssl-cert 和 --ssl-key 后，脚本会自动:
#   1. 将 PEM 证书转换为 PKCS12 格式
#   2. 上传到服务器 conf/ssl/ 目录
#   3. 配置 Ktor 启用 HTTPS
#   不提供 SSL 参数则默认使用 HTTP (端口 8080)
#
# 示例:
#   # HTTP 部署
#   ./deploy.sh 192.168.1.100
#
#   # HTTPS 部署
#   ./deploy.sh 192.168.1.100 --ssl-cert server.pem --ssl-key server.key
#
#   # 指定部署路径
#   ./deploy.sh 192.168.1.100 --path /opt/my-teamtalk
# =============================================================================

# --- 颜色 ---
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# --- 默认值 ---
DEPLOY_PATH="/opt/teamtalk"
SSH_USER="root"
SSH_PORT=22
HOST=""
PROJECT_DIR=""
DIST_DIR=""
UPLOAD_PACKAGES=false
SSL_CERT=""
SSL_KEY=""
SSL_PORT="443"

# --- 工具函数 ---
info()  { echo -e "${BLUE}[INFO]${NC} $*"; }
ok()    { echo -e "${GREEN}[OK]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
fail()  { echo -e "${RED}[FAIL]${NC} $*"; exit 1; }

# --- 帮助 ---
usage() {
    cat <<EOF
TeamTalk 一键部署脚本

用法:
  $0 [user@]HOST [选项]

参数:
  [user@]HOST            目标服务器地址 (默认 user=root)

选项:
  --path DIR             部署路径 (默认: /opt/teamtalk)
  --port PORT            SSH 端口 (默认: 22)
  --ssl-cert PEM_FILE    SSL 证书文件，配合 --ssl-key 启用 HTTPS
  --ssl-key KEY_FILE     SSL 私钥文件
  --ssl-port PORT        HTTPS 端口 (默认: 443)
  --upload-packages      同时上传本地构建的客户端安装包
  -h, --help             显示帮助

示例:
  $0 192.168.1.100                                          # HTTP 部署
  $0 192.168.1.100 --ssl-cert cert.pem --ssl-key cert.key   # HTTPS 部署
  $0 192.168.1.100 --path /opt/my-teamtalk                  # 指定路径

首次部署将:
  1. 本地构建 Server 产物
  2. 在服务器上通过 Docker 部署 PostgreSQL + MinIO
  3. 生成随机密码和 JWT 密钥
  4. 配置 SSL (如提供了证书)
  5. 注册 systemd 服务并启动
  6. 健康检查

再次执行将自动进入升级模式（保留数据和配置）。
升级时如需更新 SSL 证书，重新提供 --ssl-cert/--ssl-key 即可。
EOF
    exit 0
}

# --- 参数解析 ---
parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            -h|--help)     usage ;;
            --path)        DEPLOY_PATH="$2"; shift 2 ;;
            --port)        SSH_PORT="$2"; shift 2 ;;
            --ssl-cert)    SSL_CERT="$2"; shift 2 ;;
            --ssl-key)     SSL_KEY="$2"; shift 2 ;;
            --ssl-port)    SSL_PORT="$2"; shift 2 ;;
            --upload-packages) UPLOAD_PACKAGES=true; shift ;;
            *@*)
                SSH_USER="${1%%@*}"
                HOST="${1#*@}"
                shift
                ;;
            *) HOST="$1"; shift ;;
        esac
    done

    if [[ -z "$HOST" ]]; then
        fail "请指定目标服务器地址。用法: $0 [user@]HOST [选项]"
    fi

    if [[ -n "$SSL_CERT" && -z "$SSL_KEY" ]] || [[ -z "$SSL_CERT" && -n "$SSL_KEY" ]]; then
        fail "启用 SSL 需要同时提供 --ssl-cert 和 --ssl-key"
    fi
    if [[ -n "$SSL_CERT" && ! -f "$SSL_CERT" ]]; then
        fail "SSL 证书文件不存在: $SSL_CERT"
    fi
    if [[ -n "$SSL_KEY" && ! -f "$SSL_KEY" ]]; then
        fail "SSL 私钥文件不存在: $SSL_KEY"
    fi
}

# --- SSH 命令封装 ---
SSH="ssh -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new -p $SSH_PORT"
SCP="scp -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new -P $SSH_PORT"
RSYNC_SSH="ssh -o ConnectTimeout=10 -o StrictHostKeyChecking=accept-new -p $SSH_PORT"

remote() {
    $SSH "${SSH_USER}@${HOST}" "$@"
}

# --- 检测 SSH 连接 ---
check_ssh() {
    info "检测 SSH 连接 ${SSH_USER}@${HOST} ..."
    if $SSH -o BatchMode=yes -o ConnectTimeout=5 "${SSH_USER}@${HOST}" "echo ok" >/dev/null 2>&1; then
        ok "SSH 免密登录成功"
        return 0
    fi
    if command -v sshpass &>/dev/null; then
        info "使用 sshpass 进行密码认证，请输入密码:"
        SSH="sshpass -e $SSH"
        SCP="sshpass -e $SCP"
        RSYNC_SSH="sshpass -e $RSYNC_SSH"
        export SSHPASS
        read -rsp "密码: " SSHPASS
        echo
        if remote "echo ok" >/dev/null 2>&1; then
            ok "SSH 密码登录成功"
            return 0
        fi
    fi
    fail "SSH 连接失败。请配置免密登录或安装 sshpass"
}

# --- 检测远程环境 ---
check_remote_env() {
    info "检测远程服务器环境..."
    local os_info
    os_info=$(remote "cat /etc/os-release 2>/dev/null | head -1 || uname -a")
    info "系统: $os_info"
    if ! remote "command -v docker &>/dev/null"; then
        fail "远程服务器未安装 Docker。请先安装: curl -fsSL https://get.docker.com | sh"
    fi
    if ! remote "docker compose version &>/dev/null || docker-compose version &>/dev/null"; then
        fail "远程服务器未安装 Docker Compose。Docker Compose V2 已内置于 Docker，请更新 Docker。"
    fi
    ok "Docker + Docker Compose 已就绪"
    if remote "command -v java &>/dev/null"; then
        info "Java: $(remote 'java -version 2>&1 | head -1')"
    else
        fail "远程服务器未安装 JDK 17+。请安装: apt install openjdk-17-jre"
    fi
}

# --- 本地构建 ---
build_local() {
    PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
    DIST_DIR="$PROJECT_DIR/server/build/install/teamtalk-server"
    if [[ ! -d "$DIST_DIR" ]]; then
        info "本地构建 Server 产物..."
        (cd "$PROJECT_DIR" && ./gradlew :server:buildServerDist --quiet)
    else
        info "使用已构建的 Server 产物"
    fi
    if [[ ! -d "$DIST_DIR" ]]; then
        fail "构建产物不存在: $DIST_DIR"
    fi
    ok "构建产物就绪: $(du -sh "$DIST_DIR" | cut -f1)"
}

# --- 生成随机密码 ---
gen_password() {
    openssl rand -base64 24 | tr -d '/+=' | head -c 32
}

# --- SSL 证书处理 ---
setup_ssl() {
    if [[ -z "$SSL_CERT" ]]; then
        info "未提供 SSL 证书，使用 HTTP 模式"
        return
    fi

    info "配置 SSL 证书..."
    local ssl_dir="$DEPLOY_PATH/conf/ssl"
    local p12_password
    p12_password=$(gen_password)

    # 本地转换 PEM -> PKCS12
    local tmp_p12
    tmp_p12=$(mktemp /tmp/teamtalk-ssl-XXXXXX.p12)
    openssl pkcs12 -export \
        -in "$SSL_CERT" \
        -inkey "$SSL_KEY" \
        -out "$tmp_p12" \
        -name mykey \
        -passout "pass:$p12_password" 2>/dev/null

    # 上传到服务器
    remote "mkdir -p $ssl_dir"
    $SCP "$tmp_p12" "${SSH_USER}@${HOST}:$ssl_dir/teamtalk.p12"
    rm -f "$tmp_p12"
    remote "chmod 600 $ssl_dir/teamtalk.p12"

    # 写入 SSL 配置到 env.sh（追加）
    remote "cat >> $DEPLOY_PATH/env.sh <<'SSLEOF'

# SSL 配置
KTOR_PORT=
KTOR_SSL_PORT=$SSL_PORT
SSL_KEYSTORE=$ssl_dir/teamtalk.p12
SSL_KEYSTORE_PASSWORD=$p12_password
SSL_PRIVATE_KEY_PASSWORD=$p12_password
SSLEOF"

    ok "SSL 证书已配置 (端口 $SSL_PORT)"
}

# --- 首次部署 ---
deploy_new() {
    info "========== 首次部署 =========="

    remote "mkdir -p $DEPLOY_PATH/{data/pgdata,data/minio,data/rocksdb,data/lucene-index,data/logs,conf,static/downloads}"

    # 生成密码
    local db_password jwt_secret minio_access_key minio_secret_key
    db_password=$(gen_password)
    jwt_secret=$(gen_password)$(gen_password)
    minio_access_key="teamtalk-$(gen_password | head -c 8)"
    minio_secret_key=$(gen_password)

    # 上传 Server 产物
    info "上传 Server 产物..."
    rsync -avz --delete \
        --exclude='data' --exclude='logs' --exclude='env.sh' \
        --exclude='docker-compose.yml' --exclude='conf/ssl' \
        -e "$RSYNC_SSH" \
        "$DIST_DIR/" "${SSH_USER}@${HOST}:$DEPLOY_PATH/"
    ok "Server 产物上传完成"

    # 生成 env.sh
    info "生成配置文件..."
    remote "cat > $DEPLOY_PATH/env.sh <<'ENVEOF'
# TeamTalk 环境配置 — 自动生成 $(date '+%Y-%m-%d %H:%M:%S')
# 权限 600，请勿提交到版本控制
# 修改后执行: systemctl restart teamtalk

DATABASE_JDBC_URL=\"jdbc:postgresql://127.0.0.1:5432/teamtalk\"
DATABASE_USER=\"teamtalk\"
DATABASE_PASSWORD=\"${db_password}\"
JWT_SECRET=\"${jwt_secret}\"
MINIO_ENDPOINT=\"http://127.0.0.1:9000\"
MINIO_ACCESS_KEY=\"${minio_access_key}\"
MINIO_SECRET_KEY=\"${minio_secret_key}\"
MINIO_BUCKET=\"teamtalk\"
TK_LOG_DIR=\"$DEPLOY_PATH/data/logs\"
ENVEOF
chmod 600 $DEPLOY_PATH/env.sh"
    ok "env.sh 已生成 (权限 600)"

    # SSL 证书
    setup_ssl

    # 生成 docker-compose.yml
    info "配置 Docker 基础设施..."
    remote "cat > $DEPLOY_PATH/docker-compose.yml <<'DCEOF'
services:
  postgres:
    image: postgres:16-alpine
    restart: always
    healthcheck:
      test: [\"CMD-SHELL\", \"pg_isready -U teamtalk\"]
      interval: 5s
      timeout: 3s
      retries: 10
    ports:
      - \"127.0.0.1:5432:5432\"
    environment:
      POSTGRES_USER: teamtalk
      POSTGRES_PASSWORD: \${DB_PASSWORD}
      POSTGRES_DB: teamtalk
    volumes:
      - $DEPLOY_PATH/data/pgdata:/var/lib/postgresql/data

  minio:
    image: minio/minio
    command: server /data --console-address \":9001\"
    restart: always
    healthcheck:
      test: [\"CMD\", \"mc\", \"ready\", \"local\"]
      interval: 10s
      timeout: 5s
      retries: 5
    ports:
      - \"127.0.0.1:9000:9000\"
      - \"0.0.0.0:9001:9001\"
    environment:
      MINIO_ROOT_USER: \${MINIO_ACCESS_KEY}
      MINIO_ROOT_PASSWORD: \${MINIO_SECRET_KEY}
    volumes:
      - $DEPLOY_PATH/data/minio:/data
DCEOF"

    # 启动 Docker 基础设施
    info "启动 PostgreSQL + MinIO ..."
    remote "cd $DEPLOY_PATH && \
        source env.sh && \
        export DB_PASSWORD=\"\$DATABASE_PASSWORD\" && \
        export MINIO_ACCESS_KEY=\"\$MINIO_ACCESS_KEY\" && \
        export MINIO_SECRET_KEY=\"\$MINIO_SECRET_KEY\" && \
        docker compose up -d"

    # 等待 PostgreSQL 就绪
    info "等待 PostgreSQL 启动..."
    local retries=0
    while [[ $retries -lt 30 ]]; do
        if remote "docker exec ${DEPLOY_PATH##*/}-postgres-1 pg_isready -U teamtalk &>/dev/null || \
                   docker exec teamtalk-postgres-1 pg_isready -U teamtalk &>/dev/null" 2>/dev/null; then
            ok "PostgreSQL 已就绪"; break
        fi
        retries=$((retries + 1)); sleep 2
    done
    [[ $retries -eq 30 ]] && fail "PostgreSQL 启动超时"

    # 等待 MinIO 就绪
    info "等待 MinIO 启动..."
    retries=0
    while [[ $retries -lt 15 ]]; do
        if remote "curl -sf http://127.0.0.1:9000/minio/health/live &>/dev/null"; then
            ok "MinIO 已就绪"; break
        fi
        retries=$((retries + 1)); sleep 2
    done
    [[ $retries -eq 15 ]] && warn "MinIO 启动超时"

    # 创建 MinIO bucket
    info "初始化 MinIO bucket..."
    remote "source $DEPLOY_PATH/env.sh && \
        docker run --rm --network host --entrypoint='' minio/mc \
        sh -c \"mc alias set local http://127.0.0.1:9000 \$MINIO_ACCESS_KEY \$MINIO_SECRET_KEY && \
               mc mb --ignore-existing local/teamtalk\"" 2>/dev/null || true

    # 注册 systemd 服务
    register_systemd

    # 启动 TeamTalk
    info "启动 TeamTalk Server..."
    remote "systemctl daemon-reload && systemctl enable teamtalk && systemctl start teamtalk"

    # 输出部署信息
    echo ""
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}       TeamTalk 首次部署完成!${NC}"
    echo -e "${GREEN}========================================${NC}"
    echo ""
    echo "  部署路径:       $DEPLOY_PATH"
    echo "  数据库密码:     $db_password"
    echo "  JWT 密钥:       ${jwt_secret:0:20}..."
    echo "  MinIO Access:   $minio_access_key"
    echo "  MinIO Secret:   $minio_secret_key"
    if [[ -n "$SSL_CERT" ]]; then
        echo "  SSL:            已启用 (端口 $SSL_PORT)"
    else
        echo "  SSL:            未启用 (HTTP 端口 8080)"
    fi
    echo ""
    echo -e "  ${YELLOW}请妥善保存以上信息！${NC} 配置文件: $DEPLOY_PATH/env.sh"
    echo ""
}

# --- 升级部署 ---
deploy_upgrade() {
    info "========== 升级部署 =========="

    if remote "systemctl is-active --quiet teamtalk 2>/dev/null"; then
        info "停止 TeamTalk Server ..."
        remote "systemctl stop teamtalk" || true
    else
        remote "test -f $DEPLOY_PATH/bin/teamtalk-stop.sh && $DEPLOY_PATH/bin/teamtalk-stop.sh || true"
    fi

    info "备份当前版本..."
    remote "rm -rf ${DEPLOY_PATH}.bak && cp -r $DEPLOY_PATH ${DEPLOY_PATH}.bak" || warn "备份失败"
    ok "已备份到 ${DEPLOY_PATH}.bak"

    info "上传新版本..."
    rsync -avz \
        --exclude='data' --exclude='logs' --exclude='env.sh' \
        --exclude='docker-compose.yml' --exclude='.pid' --exclude='conf/ssl' \
        -e "$RSYNC_SSH" \
        "$DIST_DIR/" "${SSH_USER}@${HOST}:$DEPLOY_PATH/"
    ok "新版本上传完成"

    # 升级时如提供了新的 SSL 证书，更新之
    if [[ -n "$SSL_CERT" ]]; then
        info "更新 SSL 证书..."
        # 先从 env.sh 移除旧的 SSL 配置
        remote "sed -i '/^# SSL/,/^$/d' $DEPLOY_PATH/env.sh"
        # 重新追加新的 SSL 配置
        setup_ssl
    fi

    info "启动 TeamTalk Server..."
    if remote "test -f /etc/systemd/system/teamtalk.service"; then
        remote "systemctl start teamtalk"
    else
        register_systemd
        remote "systemctl daemon-reload && systemctl enable teamtalk && systemctl start teamtalk"
    fi

    echo ""
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}       TeamTalk 升级完成!${NC}"
    echo -e "${GREEN}========================================${NC}"
    echo ""
    echo "  备份位置: ${DEPLOY_PATH}.bak"
    echo ""
}

# --- 注册 systemd 服务 ---
register_systemd() {
    info "注册 systemd 服务..."
    remote "cat > /etc/systemd/system/teamtalk.service <<'SVCEOF'
[Unit]
Description=TeamTalk Server
After=network.target docker.service
Requires=docker.service

[Service]
Type=forking
WorkingDirectory=$DEPLOY_PATH
EnvironmentFile=$DEPLOY_PATH/env.sh
ExecStartPre=/bin/bash -c 'source $DEPLOY_PATH/env.sh && cd $DEPLOY_PATH && export DB_PASSWORD=\"\${DATABASE_PASSWORD}\" && export MINIO_ACCESS_KEY=\"\${MINIO_ACCESS_KEY}\" && export MINIO_SECRET_KEY=\"\${MINIO_SECRET_KEY}\" && docker compose up -d'
ExecStart=$DEPLOY_PATH/bin/teamtalk.sh
ExecStop=$DEPLOY_PATH/bin/teamtalk-stop.sh
PIDFile=$DEPLOY_PATH/logs/teamtalk.pid
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
SVCEOF"
    ok "systemd 服务已注册"
}

# --- 健康检查 ---
health_check() {
    info "========== 健康检查 =========="

    # 检测服务器使用的协议（根据 env.sh 中是否有 SSL 配置）
    local use_https=false
    if remote "grep -q 'KTOR_SSL_PORT' $DEPLOY_PATH/env.sh 2>/dev/null"; then
        use_https=true
    fi

    local health_url_prefix
    if [[ "$use_https" == "true" ]]; then
        health_url_prefix="https://127.0.0.1:443"
    else
        health_url_prefix="http://127.0.0.1:8080"
    fi

    local all_ok=true

    # 等待 TeamTalk Server 启动
    info "等待 TeamTalk Server 启动..."
    local retries=0
    while [[ $retries -lt 15 ]]; do
        if remote "curl -skf $health_url_prefix/ping &>/dev/null"; then
            ok "TeamTalk Server 已就绪 ($health_url_prefix)"
            break
        fi
        retries=$((retries + 1)); sleep 2
    done
    if [[ $retries -eq 15 ]]; then
        warn "TeamTalk Server 未响应"; all_ok=false
    fi

    # TCP 5100
    if remote "nc -zv 127.0.0.1 5100 &>/dev/null"; then
        ok "TCP 5100 已就绪"
    else
        warn "TCP 5100 未响应"; all_ok=false
    fi

    # PostgreSQL
    if remote "docker exec ${DEPLOY_PATH##*/}-postgres-1 pg_isready -U teamtalk &>/dev/null || \
               docker exec teamtalk-postgres-1 pg_isready -U teamtalk &>/dev/null" 2>/dev/null; then
        ok "PostgreSQL — 健康"
    else
        warn "PostgreSQL — 未就绪"; all_ok=false
    fi

    # MinIO
    if remote "curl -sf http://127.0.0.1:9000/minio/health/live &>/dev/null"; then
        ok "MinIO — 健康"
    else
        warn "MinIO — 未就绪"; all_ok=false
    fi

    # 外网探测
    echo ""
    info "外网端口探测..."

    local public_url
    if [[ "$use_https" == "true" ]]; then
        public_url="https://$HOST"
        # HTTPS 443
        if curl -sf --connect-timeout 5 -k "https://$HOST/ping" >/dev/null 2>&1; then
            ok "443 (HTTPS) — 可达"
        else
            warn "443 (HTTPS) — 不可达，请检查安全组/防火墙"
            all_ok=false
        fi
    else
        public_url="http://$HOST:8080"
        if nc -zv -w3 "$HOST" 8080 &>/dev/null 2>&1; then
            ok "8080 (HTTP) — 可达"
        else
            warn "8080 (HTTP) — 不可达，请检查安全组/防火墙"
            all_ok=false
        fi
    fi

    # TCP 5100
    if nc -zv -w3 "$HOST" 5100 &>/dev/null 2>&1; then
        ok "5100 (TCP) — 可达"
    else
        warn "5100 (TCP) — 不可达，请检查安全组/防火墙"
        all_ok=false
    fi

    # 部署报告
    echo ""
    echo -e "${BLUE}=== TeamTalk 部署报告 ===${NC}"
    echo ""
    echo "  首页:         $public_url/"
    echo "  API:          $public_url/api/v1/"
    echo "  TCP:          $HOST:5100"
    echo "  MinIO:        http://$HOST:9001 (仅内网)"
    echo ""
    echo "  Desktop 客户端连接:"
    echo "    ./gradlew :desktop:run -PSERVER_BASE_URL=$public_url -PTCP_HOST=$HOST"
    echo ""
    echo "  管理命令:"
    echo "    ssh ${SSH_USER}@${HOST} 'systemctl status teamtalk'"
    echo "    ssh ${SSH_USER}@${HOST} 'systemctl restart teamtalk'"
    echo ""
    if [[ "$all_ok" != "true" ]]; then
        echo -e "  ${YELLOW}部分检查未通过，请根据上方提示排查。${NC}"
        echo ""
    fi
}

# --- 上传客户端安装包 ---
upload_packages() {
    info "========== 上传客户端安装包 =========="
    local downloads_dir="$DEPLOY_PATH/static/downloads"
    remote "mkdir -p $downloads_dir"
    local uploaded=0

    local desktop_dir="$PROJECT_DIR/desktop/build/compose/binaries/main"
    if [[ -d "$desktop_dir" ]]; then
        for f in $(find "$desktop_dir" -type f \( -name "*.deb" -o -name "*.msi" -o -name "*.dmg" \) 2>/dev/null); do
            info "上传 $(basename "$f") ..."
            $SCP "$f" "${SSH_USER}@${HOST}:$downloads_dir/"
            ok "$(basename "$f") 已上传"
            uploaded=$((uploaded + 1))
        done
    fi

    local apk_pattern="$PROJECT_DIR/android/build/outputs/apk/release/*-release.apk"
    for f in $apk_pattern; do
        if [[ -f "$f" ]]; then
            info "上传 TeamTalk-android.apk ..."
            $SCP "$f" "${SSH_USER}@${HOST}:$downloads_dir/TeamTalk-android.apk"
            ok "Android APK 已上传"
            uploaded=$((uploaded + 1))
        fi
    done

    if [[ $uploaded -eq 0 ]]; then
        warn "未找到本地构建产物。请先运行: ./build-release.sh"
    else
        ok "共上传 $uploaded 个文件"
    fi
}

# =============================================================================
# 主流程
# =============================================================================
main() {
    parse_args "$@"

    echo ""
    echo -e "${BLUE}╔══════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║       TeamTalk 一键部署脚本          ║${NC}"
    echo -e "${BLUE}╚══════════════════════════════════════╝${NC}"
    echo ""
    echo "  目标: ${SSH_USER}@${HOST} (SSH 端口 $SSH_PORT)"
    echo "  路径: $DEPLOY_PATH"
    if [[ -n "$SSL_CERT" ]]; then
        echo "  SSL:  已启用 (端口 $SSL_PORT)"
    else
        echo "  SSL:  未启用 (HTTP)"
    fi
    echo ""

    check_ssh
    check_remote_env
    build_local

    if remote "test -d $DEPLOY_PATH/bin && test -f $DEPLOY_PATH/env.sh"; then
        info "检测到已有部署，进入升级模式"
        deploy_upgrade
    else
        info "未检测到已有部署，进入首次部署模式"
        deploy_new
    fi

    health_check

    if [[ "$UPLOAD_PACKAGES" == "true" ]]; then
        upload_packages
    fi
}

main "$@"
