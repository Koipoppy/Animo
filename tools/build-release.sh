#!/usr/bin/env bash
# Builds the signed release APK/AAB and drops them in the project root.
#
# Requires:
#   android/keystore.properties   release signing key (see keystore.properties.example)
#
# The Lux3D key is entered by the user in the app, so no backend URL is baked in.
set -euo pipefail
export MSYS_NO_PATHCONV=1

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID="$ROOT/android"
GRADLE="${GRADLE:-$(command -v gradle || true)}"
if [ -z "$GRADLE" ]; then
  GRADLE="$(find "${HOME}/.gradle/wrapper/dists" -path "*gradle-8.14.3*/bin/gradle" -type f 2>/dev/null | head -1)"
fi
[ -n "$GRADLE" ] && [ -x "$GRADLE" ] || {
  echo "找不到 Gradle，请设置 GRADLE=/path/to/gradle" >&2
  exit 2
}

[ -f "$ANDROID/keystore.properties" ] || {
  echo "缺少 android/keystore.properties（参考 android/keystore.properties.example）" >&2
  exit 2
}

VERSION="$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' "$ANDROID/app/build.gradle.kts" | head -1)"
[ -n "$VERSION" ] || { echo "读不到 versionName" >&2; exit 2; }

echo "== 构建 release $VERSION"
(cd "$ANDROID" && "$GRADLE" --no-daemon -q assembleRelease bundleRelease)

APK="$ANDROID/app/build/outputs/apk/release/app-release.apk"
AAB="$ANDROID/app/build/outputs/bundle/release/app-release.aab"
[ -f "$APK" ] || { echo "没有产出签名 APK，检查 keystore.properties 与构建日志" >&2; exit 1; }

# One artefact only: clear older builds before copying the new one.
rm -f "$ROOT"/Animo-*.apk "$ROOT"/Animo-*.aab
cp "$APK" "$ROOT/Animo-$VERSION.apk"
echo "✅ $ROOT/Animo-$VERSION.apk ($(du -h "$APK" | cut -f1))"

if [ -f "$AAB" ]; then
  cp "$AAB" "$ROOT/Animo-$VERSION.aab"
  echo "✅ $ROOT/Animo-$VERSION.aab ($(du -h "$AAB" | cut -f1))"
fi
