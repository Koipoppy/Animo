#!/usr/bin/env bash
# Builds the signed release APK/AAB and drops them in the project root.
#
# Requires:
#   android/keystore.properties          release signing key (see keystore.properties.example)
#   ANIMO_API_BASE_URL=https://...  deployed backend baked into the app
#
#   ANIMO_API_BASE_URL=https://api.example.com tools/build-release.sh
set -euo pipefail
export MSYS_NO_PATHCONV=1

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID="$ROOT/android"
GRADLE="${GRADLE:-/c/Users/cheny/.gradle/wrapper/dists/gradle-8.14.3-all/10utluxaxniiv4wxiphsi49nj/gradle-8.14.3/bin/gradle}"

[ -f "$ANDROID/keystore.properties" ] || {
  echo "缺少 android/keystore.properties（参考 android/keystore.properties.example）" >&2
  exit 2
}
[ -n "${ANIMO_API_BASE_URL:-}" ] || {
  echo "请设置 ANIMO_API_BASE_URL，例如 https://api.example.com" >&2
  exit 2
}
case "$ANIMO_API_BASE_URL" in
  https://*) ;;
  *) echo "release 包只允许 HTTPS 后端，当前为：$ANIMO_API_BASE_URL" >&2; exit 2 ;;
esac

VERSION="$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' "$ANDROID/app/build.gradle.kts" | head -1)"
[ -n "$VERSION" ] || { echo "读不到 versionName" >&2; exit 2; }

echo "== 构建 release $VERSION → $ANIMO_API_BASE_URL"
(cd "$ANDROID" && "$GRADLE" --no-daemon -q \
  -PANIMO_API_BASE_URL="$ANIMO_API_BASE_URL" \
  assembleRelease bundleRelease)

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
