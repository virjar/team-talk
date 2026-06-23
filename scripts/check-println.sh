#!/usr/bin/env bash
# 检查 server/src/main 下是否有 println 调用（违反代码规范）。
# server 必须用 SLF4J，禁止 println。
# System.err.println 是允许的（用于 logback 初始化前的启动信息）。
# 在 CI 或 pre-commit 中调用，发现 println 则返回非零退出码。
set -euo pipefail

# 只匹配 println（stdout），不匹配 System.err.println
VIOLATIONS=$(grep -rn 'println' server/src/main/ --include='*.kt' \
    | grep -v 'System\.err\.println' \
    | grep -v '// CHECK-PRINTLN-EXEMPT' \
    || true)

if [ -n "$VIOLATIONS" ]; then
    echo "❌ 发现 server 代码中使用 println（违反代码规范，必须用 SLF4J）："
    echo "$VIOLATIONS"
    echo ""
    echo "修复方法：用 org.slf4j.Logger 替代 println"
    echo "（如果是在 logback 初始化前必须输出的启动信息，用 System.err.println）"
    exit 1
fi

echo "✅ server 代码无 println 违规"
