#!/usr/bin/env bash
# Builds and installs the native app to a connected phone without any tapping.
#
# adb install goes through the system PackageInstaller UI, and vivo's "install verification"
# sits in front of it with a confirmation dialog. This script instead:
#   1. pushes the APK to /data/local/tmp
#   2. runs `pm install`, which normally installs silently
#   3. if an installer dialog still appears, finds its "install / continue / allow" button by
#      reading the UI hierarchy and taps it
#
#   tools/install-android.sh                 # build + install to the only connected device
#   tools/install-android.sh <serial>        # pick a device
#   tools/install-android.sh <serial> --no-build
set -uo pipefail
export MSYS_NO_PATHCONV=1

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID="$ROOT/android"
# The root always holds exactly one build; use it when present.
APK="$(ls "$ROOT"/Animo-*.apk 2>/dev/null | head -1)"
PKG="com.animo.ar"
ACTIVITY="$PKG/.MainActivity"

SERIAL="${1:-}"
if [ "$SERIAL" = "--no-build" ]; then SERIAL=""; BUILD=0; else BUILD=1; fi
if [ "${2:-}" = "--no-build" ]; then BUILD=0; fi

if [ -z "$SERIAL" ]; then
  SERIAL="$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')"
fi
[ -n "$SERIAL" ] || { echo "没有连接的设备" >&2; exit 2; }

GRADLE="${GRADLE:-/c/Users/chenyuchong/.gradle/wrapper/dists/gradle-8.14.3-bin/cv11ve7ro1n3o1j4so8xd9n66/gradle-8.14.3/bin/gradle}"

if [ "$BUILD" = "1" ] || [ -z "$APK" ]; then
  echo "== 构建"
  "$ROOT/tools/build-android.sh" || exit 1
  APK="$(ls "$ROOT"/Animo-*.apk 2>/dev/null | head -1)"
fi
[ -n "$APK" ] || { echo "根目录没有 Animo-*.apk，先跑 tools/build-android.sh" >&2; exit 2; }

echo "== 推送 $SERIAL"
# adb.exe is a Windows binary: the source needs a Windows path, while the device target must stay
# untouched, hence the explicit conversion plus MSYS_NO_PATHCONV.
WIN_APK="$(cygpath -w "$APK")"
adb -s "$SERIAL" push "$WIN_APK" /data/local/tmp/animo.apk >/dev/null || exit 1

install_once() {
  adb -s "$SERIAL" shell pm install -r -t /data/local/tmp/animo.apk
}

# Tap the confirming button of a system installer dialog, if one shows up.
confirm_dialog() {
  local xml="/sdcard/tl_ui.xml" dump
  dump="$(adb -s "$SERIAL" shell uiautomator dump "$xml" 2>/dev/null)"
  [ -n "$dump" ] || return 1
  local target
  target="$(adb -s "$SERIAL" shell cat "$xml" \
    | tr '>' '\n' \
    | grep -E 'text="(继续安装|安装|立即安装|仍要安装|继续|允许|确定|知道了)"' \
    | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' \
    | head -1)"
  [ -n "$target" ] || return 1
  local a b c d
  a="$(echo "$target" | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1/')"
  b="$(echo "$target" | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\2/')"
  c="$(echo "$target" | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\3/')"
  d="$(echo "$target" | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\4/')"
  local x=$(( (a + c) / 2 )) y=$(( (b + d) / 2 ))
  echo "   自动确认安装弹窗 ($x,$y)"
  adb -s "$SERIAL" shell input tap "$x" "$y" >/dev/null
  return 0
}

echo "== 静默安装"
# pm install blocks until the package manager answers, and vivo may hold it behind a
# confirmation dialog. Run it in the background so the dialog watcher can act while it waits.
LOG="$(mktemp)"
install_once >"$LOG" 2>&1 &
INSTALL_PID=$!

TAPPED=0
for _ in $(seq 1 90); do
  kill -0 "$INSTALL_PID" 2>/dev/null || break
  sleep 1
  if confirm_dialog; then TAPPED=1; fi
done
wait "$INSTALL_PID" 2>/dev/null
RESULT="$(cat "$LOG")"
rm -f "$LOG"

if echo "$RESULT" | grep -q "^Success"; then
  echo "   Success (自动确认弹窗: $TAPPED 次)"
else
  echo "   安装失败：" >&2
  echo "$RESULT" | head -5 >&2
  exit 1
fi

adb -s "$SERIAL" shell am force-stop "$PKG" >/dev/null 2>&1
adb -s "$SERIAL" shell am start -n "$ACTIVITY" >/dev/null 2>&1
echo "✅ 已安装并启动（$SERIAL）"
