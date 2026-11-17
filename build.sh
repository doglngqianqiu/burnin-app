#!/bin/bash
# 手工 APK 构建：aapt 打包资源 + javac + d8 + zipalign + apksigner
# 依赖见 scripts/fetch-deps.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${SDK_DIR:-$ROOT/sdk}"
APP="$ROOT/app"
BUILD="$ROOT/build"
OUT="${OUT_DIR:-$ROOT/dist}"

if [ -x /usr/lib/jvm/java-17-openjdk-arm64/bin/javac ]; then
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
  export PATH="$JAVA_HOME/bin:$PATH"
fi

for f in "$SDK/android.jar" "$SDK/r8.jar" "$SDK/shizuku/api-classes.jar"; do
  if [ ! -f "$f" ]; then
    echo "缺少 $f，先运行 scripts/fetch-deps.sh" >&2
    exit 1
  fi
done

CP="$SDK/android.jar:$SDK/shizuku/api-classes.jar:$SDK/shizuku/provider-classes.jar:$SDK/shizuku/aidl/classes.jar:$SDK/shizuku/shared/classes.jar"

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/gen" "$BUILD/dex" "$OUT"

echo ">>> R.java"
aapt package -m -J "$BUILD/gen" -M "$APP/AndroidManifest.xml" -S "$APP/res" \
  -I "$SDK/android.jar" --min-sdk-version 26 --target-sdk-version 31 --auto-add-overlay

echo ">>> javac"
find "$APP/src" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
javac -source 8 -target 8 -nowarn -encoding UTF-8 \
  -bootclasspath "$SDK/android.jar" -classpath "$CP" \
  -d "$BUILD/classes" @"$BUILD/sources.txt" 2>&1 | grep -v "^warning:" || true

if [ ! -f "$BUILD/classes/com/dsh/burnin/MainActivity.class" ]; then
  echo "编译失败" >&2
  exit 1
fi

echo ">>> d8"
java -cp "$SDK/r8.jar" com.android.tools.r8.D8 \
  --min-api 26 --lib "$SDK/android.jar" --output "$BUILD/dex" \
  $(find "$BUILD/classes" -name '*.class') \
  "$SDK/shizuku/api-classes.jar" "$SDK/shizuku/provider-classes.jar" \
  "$SDK/shizuku/aidl/classes.jar" "$SDK/shizuku/shared/classes.jar"

echo ">>> package"
aapt package -f -M "$APP/AndroidManifest.xml" -S "$APP/res" \
  -I "$SDK/android.jar" -F "$BUILD/unsigned.apk" \
  --min-sdk-version 26 --target-sdk-version 31 \
  --version-code 2 --version-name 1.1

(cd "$BUILD/dex" && aapt add "$BUILD/unsigned.apk" classes.dex >/dev/null)

echo ">>> zipalign"
zipalign -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo ">>> sign"
if [ ! -f "$ROOT/debug.keystore" ]; then
  keytool -genkeypair -v -keystore "$ROOT/debug.keystore" -storepass android \
    -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
apksigner sign --ks "$ROOT/debug.keystore" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --out "$BUILD/burnin.apk" "$BUILD/aligned.apk"
apksigner verify --print-certs "$BUILD/burnin.apk" | head -3

cp "$BUILD/burnin.apk" "$OUT/burnin.apk"
ls -lh "$OUT/burnin.apk"
echo "BUILD OK"
