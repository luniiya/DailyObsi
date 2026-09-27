#!/bin/sh
# Runs the JVM unit tests (app/src/test/java/dev/ayaya/dailyobsi/, laid out
# to mirror the main source folders). No emulator needed.
#
# Usage: ./test.sh              all tests
#        ./test.sh ProgressBar  only test classes whose name matches

set -e

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ANDROID_HOME=/opt/android-sdk

if [ -n "$1" ]; then
  ./gradlew testDebugUnitTest --tests "*$1*"
else
  ./gradlew testDebugUnitTest
fi
echo "==> Tests passed. Report: app/build/reports/tests/testDebugUnitTest/index.html"
