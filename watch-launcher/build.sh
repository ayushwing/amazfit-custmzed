#!/usr/bin/env bash
# Build the probe launcher APK without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Needs: openjdk@17 and android-commandlinetools (platforms;android-22, build-tools;34.0.0).
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-22/android.jar"
OUT=build
KEY="${DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"

rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/dex"

"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -o "$OUT/base.apk"

javac -source 8 -target 8 -Xlint:-options -bootclasspath "$JAR" \
  -d "$OUT/classes" $(find src -name '*.java')

"$BT/d8" --min-api 22 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f "$KEY" ]; then
  mkdir -p "$(dirname "$KEY")"
  keytool -genkeypair -keystore "$KEY" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null
fi
"$BT/apksigner" sign --ks "$KEY" --ks-pass pass:android --key-pass pass:android \
  --min-sdk-version 22 --out "$OUT/pace-launcher.apk" "$OUT/aligned.apk"

echo "built $OUT/pace-launcher.apk ($(wc -c < "$OUT/pace-launcher.apk") bytes)"
