#!/usr/bin/env bash
# 在已授权 USB 调试设备上依次运行 Spike A 的 targetSdk 35/28 阶梯探针。
#
# 用法：bash run-device-ladder.sh [device-serial]
# 前置：设备可由 adb 访问，且 dist/ 中的 APK 已构建。
# 输出：dist/logs/<tag>.logcat.txt、dist/logs/<tag>.spikea.log 和 device-facts.txt。
#
# Runs the Spike A targetSdk 35/28 ladder probes sequentially on a USB-debug-authorized device.
#
# Usage: bash run-device-ladder.sh [device-serial]
# Prerequisites: the device is reachable through adb and the APKs in dist/ are built.
# Output: dist/logs/<tag>.logcat.txt, dist/logs/<tag>.spikea.log, and device-facts.txt.
set -u

# 避免 MSYS 将传给设备 shell 的 POSIX 路径误改为 Windows 路径。
export MSYS_NO_PATHCONV=1

ADB="${ADB:-/c/Users/da270/AppData/Local/Android/Sdk/platform-tools/adb.exe}"
SERIAL="${1:-AVAY025422002864}"
PKG=com.azurpilot.spikea

ROOT="$(cd "$(dirname "$0")" && pwd)"
DIST="$ROOT/dist"
LOGS="$DIST/logs"
mkdir -p "$LOGS"

if ! "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
  echo "ERROR: device '$SERIAL' not connected/authorized. Run: adb devices -l" >&2
  exit 1
fi

echo "=== device facts -> $LOGS/device-facts.txt ==="
{
  echo "# captured $(date -Iseconds 2>/dev/null || date)"
  "$ADB" -s "$SERIAL" shell "getprop ro.product.model; getprop ro.product.manufacturer; getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.build.version.security_patch; getprop ro.product.cpu.abi; getconf PAGE_SIZE; getenforce"
} | tee "$LOGS/device-facts.txt"

run_variant() {
  local tag="$1" apk="$2"
  echo ""
  echo "=== [$tag] install -r $apk ==="
  # adb.exe 接受 Windows 主机路径；为设备 shell 禁用 MSYS 转换后，安装路径需显式转换。
  "$ADB" -s "$SERIAL" install -r "$(cygpath -m "$apk")" || return 1
  "$ADB" -s "$SERIAL" shell am force-stop "$PKG"
  "$ADB" -s "$SERIAL" logcat -c
  "$ADB" -s "$SERIAL" shell am start -n "$PKG/.MainActivity" >/dev/null || return 1
  local i
  for i in $(seq 1 90); do
    sleep 2
    if "$ADB" -s "$SERIAL" logcat -d -s SpikeA:I 2>/dev/null | grep -q "LADDER DONE"; then
      echo "[$tag] ladder finished after ~$((i * 2))s"
      break
    fi
  done
  "$ADB" -s "$SERIAL" logcat -d -s SpikeA:I > "$LOGS/$tag.logcat.txt"
  "$ADB" -s "$SERIAL" logcat -d -s SpikeA:I AndroidRuntime:E DEBUG:F > "$LOGS/$tag.full.logcat.txt" 2>&1 || true
  "$ADB" -s "$SERIAL" shell run-as "$PKG" cat files/spikea.log > "$LOGS/$tag.spikea.log" 2>"$LOGS/$tag.spikea.log.err" || true
  echo "[$tag] saved:"
  echo "  $LOGS/$tag.logcat.txt ($(wc -l < "$LOGS/$tag.logcat.txt") lines)"
  echo "  $LOGS/$tag.spikea.log ($(wc -l < "$LOGS/$tag.spikea.log") lines)"
  echo "[$tag] RESULT table:"
  grep "^RESULT " "$LOGS/$tag.spikea.log" || echo "  (no RESULT lines found)"
}

run_variant "target35" "$DIST/spikea-target35-debug.apk"
run_variant "target28" "$DIST/spikea-target28-debug.apk"

echo ""
echo "=== both variants done. Compare: dist/logs/target35.spikea.log vs dist/logs/target28.spikea.log ==="
