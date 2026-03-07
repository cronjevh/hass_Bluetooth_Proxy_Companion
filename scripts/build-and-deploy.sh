#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB_DEVICE="${ADB_DEVICE:-192.168.0.133:5555}"
ADB_BIN="${ADB_BIN:-$(command -v adb 2>/dev/null || echo '/c/Tools/platform-tools/adb.exe')}"
PACKAGE_PREFIX="org.kvj.habtproxy"
LAUNCH_ACTIVITY="org.kvj.habtproxy/.SettingsActivity"
JAVA_HOME_DEFAULT="/c/Users/cronj/jdk17/jdk-17.0.13+11"
GRADLE_BIN_DEFAULT="/c/Users/cronj/.gradle/wrapper/dists/gradle-8.14.3-bin/cv11ve7ro1n3o1j4so8xd9n66/gradle-8.14.3/bin/gradle.bat"
ANDROID_HOME_DEFAULT="/c/Users/cronj/AppData/Local/Android/Sdk"

JAVA_HOME="${JAVA_HOME:-$JAVA_HOME_DEFAULT}"
GRADLE_BIN="${GRADLE_BIN:-$GRADLE_BIN_DEFAULT}"
ANDROID_HOME="${ANDROID_HOME:-$ANDROID_HOME_DEFAULT}"

RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

echo -e "${BOLD}=== HA Bluetooth Proxy build + deploy ===${NC}"
echo "Repo root   : $REPO_ROOT"
echo "ADB device  : $ADB_DEVICE"
echo "ADB bin     : $ADB_BIN"
echo "Gradle bin  : $GRADLE_BIN"
echo "JAVA_HOME   : $JAVA_HOME"
echo "ANDROID_HOME: $ANDROID_HOME"
echo ""

[ -f "$ADB_BIN" ] || { echo -e "${RED}ERROR: adb not found at $ADB_BIN${NC}"; exit 1; }
[ -d "$JAVA_HOME" ] || { echo -e "${RED}ERROR: JAVA_HOME not found at $JAVA_HOME${NC}"; exit 1; }
[ -f "$GRADLE_BIN" ] || { echo -e "${RED}ERROR: Gradle not found at $GRADLE_BIN${NC}"; exit 1; }
[ -d "$ANDROID_HOME" ] || { echo -e "${RED}ERROR: ANDROID_HOME not found at $ANDROID_HOME${NC}"; exit 1; }

export JAVA_HOME
export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

cd "$REPO_ROOT"

echo -e "${CYAN}[1] Checking device connection...${NC}"
"$ADB_BIN" connect "$ADB_DEVICE" >/dev/null 2>&1 || true
"$ADB_BIN" -s "$ADB_DEVICE" get-state >/dev/null
echo -e "  ${GREEN}Device reachable${NC}"

echo -e "${CYAN}[2] Building defaultDebug APK...${NC}"
BUILD_LOG="$(mktemp)"
set +e
"$GRADLE_BIN" clean assembleDefaultDebug >"$BUILD_LOG" 2>&1
GRADLE_RC=$?
set -e
grep -E "(BUILD SUCCESSFUL|BUILD FAILED|error:|> Task )" "$BUILD_LOG" | tail -20 || true
if [ $GRADLE_RC -ne 0 ]; then
  echo -e "${RED}ERROR: Gradle build failed.${NC}"
  echo "Full log: $BUILD_LOG"
  exit $GRADLE_RC
fi

APK="$(ls app/build/outputs/apk/default/debug/*.apk 2>/dev/null | head -1)"
[ -n "$APK" ] || { echo -e "${RED}ERROR: No APK found in app/build/outputs/apk/default/debug${NC}"; exit 1; }
echo -e "  ${GREEN}Built:${NC} $(basename "$APK")"

echo -e "${CYAN}[3] Removing installed HA Bluetooth Proxy variants...${NC}"
INSTALLED_PACKAGES="$("$ADB_BIN" -s "$ADB_DEVICE" shell pm list packages | tr -d '\r' | grep "^package:${PACKAGE_PREFIX}" || true)"
if [ -n "$INSTALLED_PACKAGES" ]; then
  while IFS= read -r line; do
    PKG="${line#package:}"
    if [ "$PKG" = "$PACKAGE_PREFIX" ]; then
      echo "  Keeping $PKG to preserve app preferences"
      continue
    fi
    echo "  Uninstalling conflicting variant $PKG"
    "$ADB_BIN" -s "$ADB_DEVICE" uninstall "$PKG" >/dev/null || true
  done <<< "$INSTALLED_PACKAGES"
else
  echo "  No installed variants found"
fi

echo -e "${CYAN}[4] Installing debug APK...${NC}"
INSTALL_OUT="$("$ADB_BIN" -s "$ADB_DEVICE" install -r "$APK" 2>&1 || true)"
echo "$INSTALL_OUT"
echo "$INSTALL_OUT" | grep -q "Success" || {
  echo -e "${RED}ERROR: adb install failed.${NC}"
  exit 1
}

echo -e "${CYAN}[5] Starting app...${NC}"
"$ADB_BIN" -s "$ADB_DEVICE" logcat -c >/dev/null 2>&1 || true
"$ADB_BIN" -s "$ADB_DEVICE" shell am start -n "$LAUNCH_ACTIVITY" >/dev/null
sleep 3
"$ADB_BIN" -s "$ADB_DEVICE" shell pidof "$PACKAGE_PREFIX" >/dev/null
echo -e "  ${GREEN}App started${NC}"

echo ""
echo -e "${BOLD}=== Deploy complete ===${NC}"
echo "APK      : $APK"
echo "Package  : $PACKAGE_PREFIX"
echo "Activity : $LAUNCH_ACTIVITY"
