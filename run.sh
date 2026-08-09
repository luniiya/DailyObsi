#!/bin/sh
# Boots the grid9test AVD (if not already running), builds+installs the
# debug APK, and launches the app. Safe to re-run any time -- skips the
# boot step if a device is already attached.
#
# Usage: ./run.sh

set -e

CMDLINE_SDK=/opt/android-sdk
AVD_SDK=/home/ayaya/Android/Sdk-avd
AVD_NAME=grid9test
APP_ID=dev.ayaya.dailyobsi
JAVA_HOME=/usr/lib/jvm/java-21-openjdk

ADB="$CMDLINE_SDK/platform-tools/adb"

if ! "$ADB" get-state 1>/dev/null 2>&1; then
  echo "==> Booting $AVD_NAME..."
  ANDROID_SDK_ROOT="$AVD_SDK" \
  ANDROID_HOME="$AVD_SDK" \
  ANDROID_AVD_HOME=/home/ayaya/.config/.android/avd \
  "$AVD_SDK/emulator/emulator" -avd "$AVD_NAME" -no-snapshot -no-boot-anim \
    > /tmp/dailyobsi-emulator.log 2>&1 &

  "$ADB" wait-for-device
  echo "==> Waiting for boot to finish..."
  until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    sleep 2
  done
  echo "==> Booted."
else
  echo "==> Emulator already running."
fi

echo "==> Building debug APK..."
JAVA_HOME="$JAVA_HOME" ANDROID_SDK_ROOT="$CMDLINE_SDK" ANDROID_HOME="$CMDLINE_SDK" \
  ./gradlew assembleDebug --console=plain

echo "==> Installing..."
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk

echo "==> Launching..."
"$ADB" shell am force-stop "$APP_ID"
"$ADB" shell am start -n "$APP_ID/.MainActivity"

echo "==> Done."
