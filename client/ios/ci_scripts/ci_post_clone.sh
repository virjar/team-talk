#!/bin/sh
# Xcode Cloud 克隆后运行：准备 Gradle 所需的 JDK 21 与 iOS 构建开关。
# 全部通过文件落地（~/.gradle/gradle.properties、local.properties），不依赖脚本间环境继承，
# 也不需要任何部署秘密——APNs 密钥只在服务端 env.sh，客户端构建无秘密。
set -eu

REPO_ROOT="${CI_PRIMARY_REPOSITORY_PATH:?Xcode Cloud repository path missing}"

# 可选的私有部署与本地签名材料注入：由 Xcode Cloud 受保护环境变量按 base64 提供，
# 不改动受 Git 跟踪的公版配置，避免私有部署参数进入公共仓库。
materialize_base64_file() {
    encoded_value="$1"
    target_path="$2"
    file_mode="$3"
    if [ -n "$encoded_value" ] && [ ! -e "$target_path" ]; then
        mkdir -p "$(dirname "$target_path")"
        printf '%s' "$encoded_value" | base64 -d > "$target_path"
        chmod "$file_mode" "$target_path"
        echo "ci_post_clone: materialized $(basename "$target_path")"
    fi
}

materialize_base64_file "${TEAMTALK_DEPLOYMENT_LOCAL_KT_BASE64:-}" "$REPO_ROOT/buildSrc/deployment-local/Deployment.kt" 600
materialize_base64_file "${TEAMTALK_TCP_TLS_CERT_PEM_BASE64:-}" "$REPO_ROOT/buildSrc/deployment-local/tcp-tls/certificate.pem" 644
materialize_base64_file "${TEAMTALK_IOS_SIGNING_XCCONFIG_BASE64:-}" "$REPO_ROOT/client/ios/Config/Signing.local.xcconfig" 600

# buildSrc 使用 JDK 21 API（如 HttpRequest.BodyPublishers.concat），低版本 JVM 编译失败。
JDK21="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
if [ -z "$JDK21" ] && command -v brew >/dev/null 2>&1; then
    brew install --quiet openjdk@21 || true
    for candidate in \
        /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
        /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home; do
        if [ -d "$candidate" ]; then JDK21="$candidate"; break; fi
    done
fi
if [ -n "$JDK21" ]; then
    mkdir -p "$HOME/.gradle"
    echo "org.gradle.java.home=$JDK21" >> "$HOME/.gradle/gradle.properties"
    echo "ci_post_clone: JDK 21 at $JDK21"
else
    echo "ci_post_clone: no JDK 21 located, relying on preinstalled/default JVM"
fi

# Release 归档的 Kotlin/Native 全程序优化（DevirtualizationAnalysis）超过默认 3g daemon 堆。
# 追加到项目 gradle.properties 末尾：同文件后出现的键覆盖前行，优先级确定。
echo "org.gradle.jvmargs=-Xmx6g -XX:MaxMetaspaceSize=1g" >> "$REPO_ROOT/gradle.properties"
echo "ci_post_clone: Gradle daemon heap raised to 6g for Release Kotlin/Native link"
