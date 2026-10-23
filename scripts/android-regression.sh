#!/usr/bin/env bash
# Collect screenshots before android-emulator-runner shuts the emulator down.
set +e
mkdir -p ui-previews
# emulator-runner configures window/transition animation; explicitly set the
# animator scale too so the Compose motion test covers both 0 and 1, not defaults.
motion_scale=${UI_MOTION_SCALE:-0}
for animation_setting in window_animation_scale transition_animation_scale animator_duration_scale; do
    adb shell settings put global "$animation_setting" "$motion_scale" || exit 1
done
# Configure the whole emulator before instrumented activities are launched; this
# also covers separate Android Dialog windows, unlike composition-local overrides.
adb shell settings put system font_scale "${UI_FONT_SCALE:-1.0}" || exit 1
if [ -n "${UI_DISPLAY_DENSITY:-}" ]; then
    adb shell wm density "$UI_DISPLAY_DENSITY" || exit 1
fi
{
    echo "window_animation_scale=$(adb shell settings get global window_animation_scale)"
    echo "transition_animation_scale=$(adb shell settings get global transition_animation_scale)"
    echo "animator_duration_scale=$(adb shell settings get global animator_duration_scale)"
    echo "test_class=${UI_TEST_CLASS:-all}"
    echo "font_scale=$(adb shell settings get system font_scale)"
    adb shell wm size
    adb shell wm density
} > ui-previews/motion-environment.txt
if [ -n "${UI_TEST_CLASS:-}" ]; then
    ./gradlew connectedDebugAndroidTest --stacktrace "-Pandroid.testInstrumentationRunnerArguments.class=$UI_TEST_CLASS"
else
    ./gradlew connectedDebugAndroidTest --stacktrace -Pandroid.testInstrumentationRunnerArguments.notClass=com.helmet.guard.UiAccessibilityTest
fi
test_status=$?
adb pull /sdcard/Pictures/HelmetGuard/. ui-previews/ || true
exit "$test_status"
