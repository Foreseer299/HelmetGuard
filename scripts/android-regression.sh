#!/usr/bin/env bash
# Collect screenshots before android-emulator-runner shuts the emulator down.
set +e
./gradlew connectedDebugAndroidTest --stacktrace
test_status=$?
mkdir -p ui-previews
adb pull /sdcard/Pictures/HelmetGuard/. ui-previews/ || true
exit "$test_status"
