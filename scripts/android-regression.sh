#!/usr/bin/env bash
# Collect screenshots before android-emulator-runner shuts the emulator down.
set +e
./gradlew connectedDebugAndroidTest --stacktrace
test_status=$?
mkdir -p ui-previews
for preview in incident-detail incident-countdown home-light home-dark; do
    if ! adb exec-out run-as com.helmet.guard.debug cat "files/${preview}.png" > "ui-previews/${preview}.png"; then
        rm -f "ui-previews/${preview}.png"
    fi
done
exit "$test_status"
