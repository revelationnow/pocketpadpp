#!/usr/bin/env bash
# Records the short clips shown in the README, on a connected emulator or phone:
# runs the one test that plays them (readmeRecordings) and pulls what `screenrecord` saved.
# With ffmpeg installed the clips are also turned into the GIFs under docs/recordings/.
# Used by the Recordings workflow; also works locally with an emulator running.
set -u
out=recordings-output
rm -rf "$out"
mkdir -p "$out"

adb shell rm -rf /sdcard/Download/pocketpad
adb shell mkdir -p /sdcard/Download/pocketpad/recordings
adb shell settings put secure show_ime_with_hard_keyboard 1
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null
adb logcat -c

status=0
timeout --foreground --signal=INT 20m ./gradlew --no-daemon -Pandroid.injected.build.abi=x86_64 connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=io.github.revelationnow.pocketpadpp.WalkthroughTest#readmeRecordings \
    -Pandroid.testInstrumentationRunnerArguments.recordings=true || status=$?

adb pull /sdcard/Download/pocketpad/recordings "$out/clips" >/dev/null 2>&1 || true
adb logcat -d -v time PocketpadTest:V AndroidRuntime:E TestRunner:I '*:S' > "$out/logcat.txt" 2>/dev/null || true
cp -r app/build/outputs/androidTest-results/connected "$out/results" 2>/dev/null || true
ls -l "$out/clips" 2>/dev/null

if command -v ffmpeg >/dev/null 2>&1; then
    tools/make_gifs.sh "$out/clips" docs/recordings
fi
exit $status
