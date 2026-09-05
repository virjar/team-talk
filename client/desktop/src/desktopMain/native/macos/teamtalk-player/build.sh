#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
desktop_dir="$(cd "$script_dir/../../../../.." && pwd)"
output_root="${NATIVE_LIBS_OUTPUT_DIR:-$desktop_dir/src/desktopMain/resources/composemediaplayer/native}"
swift_source="$script_dir/NativeVideoPlayer.swift"
jni_source="$script_dir/jni_bridge.c"
minimum_macos="14.0"

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "ERROR: the TeamTalk macOS native player must be built on macOS" >&2
    exit 1
fi

java_home="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home)}"
sdk_path="$(xcrun --sdk macosx --show-sdk-path)"
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/teamtalk-native-player.XXXXXX")"
trap 'rm -rf "$work_dir"' EXIT

# Keep the complete source identity inside a real Mach-O section. Runtime startup compares the
# cached binary against the manifest SHA before ComposeMediaPlayer initializes; do not append bytes
# after linking, because trailing data invalidates Apple's strict code-signing checks.
source_identity="$({
    shasum -a 256 "$swift_source" | awk '{print $1}'
    shasum -a 256 "$jni_source" | awk '{print $1}'
    shasum -a 256 "$script_dir/build.sh" | awk '{print $1}'
} | shasum -a 256 | awk '{print $1}')"
stamp_file="$work_dir/teamtalk-source-stamp"
printf 'TEAMTALK_NATIVE_SOURCE_SHA256=%s\n' "$source_identity" > "$stamp_file"

build_arch() {
    local arch="$1"
    local resource_arch="$2"
    local target="${arch}-apple-macosx${minimum_macos}"
    local arch_output="$output_root/$resource_arch"
    local bridge_object="$work_dir/jni_bridge_${arch}.o"

    mkdir -p "$arch_output"
    clang \
        -c \
        -arch "$arch" \
        -isysroot "$sdk_path" \
        -mmacosx-version-min="$minimum_macos" \
        -I"$java_home/include" \
        -I"$java_home/include/darwin" \
        "$jni_source" \
        -o "$bridge_object"

    swiftc \
        -swift-version 5 \
        -O \
        -whole-module-optimization \
        -emit-library \
        -module-name TeamTalkNativeVideoPlayer \
        -target "$target" \
        -sdk "$sdk_path" \
        "$swift_source" \
        "$bridge_object" \
        -Xlinker -install_name \
        -Xlinker @rpath/libNativeVideoPlayer.dylib \
        -Xlinker -sectcreate \
        -Xlinker __TEXT \
        -Xlinker __ttstamp \
        -Xlinker "$stamp_file" \
        -o "$arch_output/libNativeVideoPlayer.dylib"

    local dylib="$arch_output/libNativeVideoPlayer.dylib"
    codesign --force --sign - --timestamp=none "$dylib"
    codesign --verify --strict --verbose=2 "$dylib"
    local stripped_dylib="$work_dir/${resource_arch}.stripped.dylib"
    cp "$dylib" "$stripped_dylib"
    codesign --remove-signature "$stripped_dylib"
    shasum -a 256 "$stripped_dylib" | awk '{print $1}' > "$work_dir/${resource_arch}.stripped.sha256"
    lipo -archs "$dylib" | grep -Fx "$arch" >/dev/null
    nm -gU "$dylib" | grep -q ' _JNI_OnLoad$'
}

echo "Building TeamTalk local macOS video backend into $output_root"
build_arch arm64 darwin-aarch64
build_arch x86_64 darwin-x86-64

manifest="$output_root/teamtalk-local-player.properties"
{
    echo "format=2"
    echo "macos.minimum=$minimum_macos"
    echo "source.sha256=$source_identity"
    echo "swift.sha256=$(shasum -a 256 "$swift_source" | awk '{print $1}')"
    echo "jni.sha256=$(shasum -a 256 "$jni_source" | awk '{print $1}')"
    echo "build.sha256=$(shasum -a 256 "$script_dir/build.sh" | awk '{print $1}')"
    echo "darwin-aarch64.stripped.sha256=$(cat "$work_dir/darwin-aarch64.stripped.sha256")"
    echo "darwin-aarch64.sha256=$(shasum -a 256 "$output_root/darwin-aarch64/libNativeVideoPlayer.dylib" | awk '{print $1}')"
    echo "darwin-x86-64.stripped.sha256=$(cat "$work_dir/darwin-x86-64.stripped.sha256")"
    echo "darwin-x86-64.sha256=$(shasum -a 256 "$output_root/darwin-x86-64/libNativeVideoPlayer.dylib" | awk '{print $1}')"
} > "$manifest"

echo "Built native resources:"
shasum -a 256 \
    "$output_root/darwin-aarch64/libNativeVideoPlayer.dylib" \
    "$output_root/darwin-x86-64/libNativeVideoPlayer.dylib"
