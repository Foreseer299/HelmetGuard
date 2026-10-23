#!/usr/bin/env bash
# Collect screenshots before android-emulator-runner shuts the emulator down.
set +e
mkdir -p ui-previews
{
    echo "window_animation_scale=$(adb shell settings get global window_animation_scale)"
    echo "transition_animation_scale=$(adb shell settings get global transition_animation_scale)"
    echo "animator_duration_scale=$(adb shell settings get global animator_duration_scale)"
    echo "test_class=${UI_TEST_CLASS:-all}"
} > ui-previews/motion-environment.txt
if [ -n "${UI_TEST_CLASS:-}" ]; then
    ./gradlew connectedDebugAndroidTest --stacktrace "-Pandroid.testInstrumentationRunnerArguments.class=$UI_TEST_CLASS"
else
    ./gradlew connectedDebugAndroidTest --stacktrace
fi
test_status=$?
adb pull /sdcard/Pictures/HelmetGuard/. ui-previews/ || true
exit "$test_status"
