#!/bin/sh
# Logcat helper for DailyObsi, filtered down to what actually matters instead
# of re-typing the same long adb command every time. Usage:
#
#   ./logs.sh            clear the buffer, then tail live (Ctrl-C to stop)
#   ./logs.sh dump        show whatever's in the buffer right now, no clear/tail
#   ./logs.sh crash        one-shot crash/exception-only scan -- what CLAUDE.md's
#                          "check logcat after every install/launch" rule wants
#   ./logs.sh clear        just clear the buffer, print nothing
#
# All modes share the same tag/keyword filter: the app's own package, its
# log tags (DailyObsiWidget warnings; add a tag here when adding new logging), and the system
# AppWidget/Glance components most relevant when debugging widget behavior.

set -e

ADB=/opt/android-sdk/platform-tools/adb
PATTERN='dailyobsi|DailyObsiWidget|AppWidgetServiceImpl|GlanceAppWidget|AppWidgetHostView'
CRASH_PATTERN='dailyobsi.*(fatal|exception|error)|IllegalArgumentException|GlanceAppWidget.*[Ee]rror|AndroidRuntime'

case "$1" in
  crash)
    "$ADB" logcat -d | grep -iE "$CRASH_PATTERN" || echo "(clean -- nothing matched)"
    ;;
  dump)
    "$ADB" logcat -d | grep -iE "$PATTERN"
    ;;
  clear)
    "$ADB" logcat -c
    ;;
  *)
    "$ADB" logcat -c
    "$ADB" logcat -v time | grep -iE --line-buffered "$PATTERN"
    ;;
esac
