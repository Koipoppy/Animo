#!/usr/bin/env bash
# Builds the native client and drops the APK in the project root as Animo-<version>.apk.
#
# Convention: the root holds exactly one APK — the newest build. Older Animo-*.apk files are
# removed first so the folder never accumulates stale builds.
#
#   tools/build-android.sh
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
BUILT="$ANDROID/app/build/outputs/apk/debug/app-debug.apk"

VERSION="$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' "$ANDROID/app/build.gradle.kts" | head -1)"
[ -n "$VERSION" ] || { echo "读不到 versionName" >&2; exit 2; }

echo "== 构建 $VERSION"
(cd "$ANDROID" && "$GRADLE" --no-daemon -q assembleDebug)

[ -f "$BUILT" ] || { echo "构建没有产出 APK" >&2; exit 1; }

# One artefact only: clear older builds before copying the new one.
rm -f "$ROOT"/Animo-*.apk
cp "$BUILT" "$ROOT/Animo-$VERSION.apk"

echo "✅ $ROOT/Animo-$VERSION.apk ($(du -h "$BUILT" | cut -f1))"
