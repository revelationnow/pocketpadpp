#!/usr/bin/env bash
# Runs the on-device tests against a connected emulator and collects what they produce:
# screenshots taken along the way, the test report and the relevant log lines.
# Used by CI; also works locally with an emulator running.
set -u
out=ui-test-output
rm -rf "$out"
mkdir -p "$out"

adb shell rm -rf /sdcard/Download/pocketpad
adb shell mkdir -p /sdcard/Download/pocketpad
# Emulators have a hardware keyboard attached; show the soft keyboard anyway so screenshots are realistic.
adb shell settings put secure show_ime_with_hard_keyboard 1
# A freshly booted emulator may still be on its lock screen or showing a system notice.
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null
adb logcat -c

# The run is cut off before the CI job's own time limit, so that a test that hangs still leaves its
# screenshots and log behind to show where.
status=0
timeout --foreground --signal=INT "${UI_TEST_MINUTES:-60}m" \
    ./gradlew --no-daemon -Pandroid.injected.build.abi=x86_64 connectedDebugAndroidTest || status=$?
[ "$status" -eq 124 ] && echo "The tests did not finish in time."

adb pull /sdcard/Download/pocketpad "$out/screens" >/dev/null 2>&1 || true
adb logcat -d -v time PocketpadTest:V PocketpadSci:V AndroidRuntime:E ActivityManager:E TestRunner:I '*:S' > "$out/logcat.txt" 2>/dev/null || true
cp -r app/build/reports/androidTests/connected "$out/report" 2>/dev/null || true
cp -r app/build/outputs/androidTest-results/connected "$out/results" 2>/dev/null || true
ls "$out/screens" 2>/dev/null | wc -l | xargs echo "screenshots:"
exit $status
