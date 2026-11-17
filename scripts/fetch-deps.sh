#!/bin/bash
# 下载构建所需的大文件（android.jar / r8.jar）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${SDK_DIR:-$ROOT/sdk}"
mkdir -p "$SDK"

if [ ! -f "$SDK/android.jar" ]; then
  echo ">>> 下载 Android platform-31"
  url="https://dl.google.com/android/repository/platform-31_r01.zip"
  curl -L --fail -o "$SDK/platform-31.zip" "$url"
  unzip -p "$SDK/platform-31.zip" android-12/android.jar > "$SDK/android.jar"
  rm -f "$SDK/platform-31.zip"
fi

if [ ! -f "$SDK/r8.jar" ]; then
  echo ">>> 下载 R8/D8"
  curl -L --fail -o "$SDK/r8.jar" \
    "https://maven.aliyun.com/repository/google/com/android/tools/r8/8.3.37/r8-8.3.37.jar" \
    || curl -L --fail -o "$SDK/r8.jar" \
    "https://maven.google.com/com/android/tools/r8/8.3.37/r8-8.3.37.jar"
fi

echo "依赖就绪：$SDK"
