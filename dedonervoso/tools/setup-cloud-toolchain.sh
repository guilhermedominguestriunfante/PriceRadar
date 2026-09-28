#!/usr/bin/env bash
# Prepares a fresh Ubuntu/Debian container (e.g. a new Claude Code cloud session) to build and test
# Dedo Nervoso without Google's Maven or SDK downloads:
#   - Debian's Android build tools (aapt2, zipalign, apksigner, dx) and JDK 21;
#   - platform jars 34/35/36 from github.com/Sable/android-platforms;
#   - local.properties pointing at them.
# Idempotent. Needs root (apt), the Ubuntu mirrors and GitHub; Gradle then fetches the rest
# from Maven Central.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${DEDO_SDK_DIR:-/opt/android-sdk-local}"
PACKAGES=(openjdk-21-jdk-headless aapt apksigner zipalign dalvik-exchange android-sdk-platform-23 dexdump)

if ! dpkg -s "${PACKAGES[@]}" >/dev/null 2>&1; then
  apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "${PACKAGES[@]}" >/dev/null
fi

mkdir -p "$SDK/build-tools"
ln -sfn /usr/lib/android-sdk/build-tools/debian "$SDK/build-tools/debian"
for v in 34 35 36; do
  jar="$SDK/platforms/android-$v/android.jar"
  if [ ! -s "$jar" ]; then
    mkdir -p "$(dirname "$jar")"
    curl -fsSL --retry 4 -o "$jar.part" "https://raw.githubusercontent.com/Sable/android-platforms/master/android-$v/android.jar"
    mv "$jar.part" "$jar"
  fi
done

grep -qs '^sdk.dir=' "$ROOT/local.properties" || echo "sdk.dir=$SDK" >> "$ROOT/local.properties"
echo "Android toolchain ready in $SDK ($(ls "$SDK/platforms" | tr '\n' ' '))"
