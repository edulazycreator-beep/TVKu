#!/usr/bin/env bash
# Runs instrumented tests on the CI emulator and collects evidence (logcat,
# screenshots) even when tests fail. Exit code reflects the test result.
set -uo pipefail
status=0
./gradlew :core:data:connectedDebugAndroidTest :app:connectedDebugAndroidTest --stacktrace --continue || status=$?

mkdir -p device-evidence
adb logcat -d > device-evidence/logcat.txt || true
echo "----- relevant logcat -----"
grep -E "TVKu|TestRunner|AndroidRuntime|FATAL|chromium" device-evidence/logcat.txt | tail -n 400 || true
mkdir -p device-evidence/smoke
adb pull /data/local/tmp/tvku-smoke/. device-evidence/smoke/ || true
ls -la device-evidence device-evidence/smoke 2>/dev/null || true
exit $status
