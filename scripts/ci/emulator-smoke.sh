#!/usr/bin/env bash
# Install an APK on a fresh emulator, launch it from the launcher and fail if it crashes.
#
# This is a smoke test of the *minified release* build: R8 can strip classes that only
# reflection or serialization reach (navigation routes, Room, Media3), which compiles and
# passes JVM unit tests but crashes on a device. It is not a functional UI test; the behaviour
# of the screens is covered by the Robolectric tests in app/src/test.
#
#   emulator-smoke.sh <apk> <package>
#
# Needs: ANDROID_HOME with cmdline-tools, hardware acceleration (/dev/kvm). GitHub's Linux
# runners provide KVM once it is made accessible (see the workflow step before this script).
set -euo pipefail

apk="$1"
package="$2"
api="${SMOKE_API_LEVEL:-35}"
image="system-images;android-${api};google_apis;x86_64"
sdk="${ANDROID_HOME:?ANDROID_HOME is not set}"
sdkmanager="$sdk/cmdline-tools/latest/bin/sdkmanager"
avdmanager="$sdk/cmdline-tools/latest/bin/avdmanager"
adb="$sdk/platform-tools/adb"
out="${SMOKE_OUT:-build/emulator-smoke}"
mkdir -p "$out"
# avdmanager and the emulator resolve the AVD directory differently on some machines (GitHub's
# runners included); one explicit directory for both.
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-${RUNNER_TEMP:-$HOME}/avd}"
mkdir -p "$ANDROID_AVD_HOME"

[ -f "$apk" ] || { echo "::error title=Emulator smoke::APK not found: $apk"; exit 1; }
[ -e /dev/kvm ] || { echo "::error title=Emulator smoke::/dev/kvm is not available; the emulator needs hardware acceleration."; exit 1; }

echo "::group::Install emulator and $image"
yes | "$sdkmanager" --licenses > /dev/null || true
"$sdkmanager" "emulator" "platform-tools" "$image" > /dev/null
echo "no" | "$avdmanager" create avd --force --name smoke --package "$image" --device "pixel_6" > /dev/null
[ -f "$ANDROID_AVD_HOME/smoke.ini" ] || { echo "::error title=Emulator smoke::avdmanager did not create $ANDROID_AVD_HOME/smoke.ini"; exit 1; }
echo "::endgroup::"

echo "::group::Boot"
"$sdk/emulator/emulator" -avd smoke -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect \
  -camera-back none -camera-front none > "$out/emulator.log" 2>&1 &
emu_pid=$!
trap 'kill "$emu_pid" 2>/dev/null || true' EXIT
# No unbounded waits: give up after 6 minutes, or as soon as the emulator process exits.
booted=""
for _ in $(seq 1 180); do
  if ! kill -0 "$emu_pid" 2>/dev/null; then
    echo "::error title=Emulator smoke::the emulator exited during boot"; tail -50 "$out/emulator.log"; exit 1
  fi
  if [ "$("$adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then booted=1; break; fi
  sleep 2
done
[ -n "$booted" ] || { echo "::error title=Emulator smoke::the emulator did not finish booting in 6 minutes"; tail -50 "$out/emulator.log"; exit 1; }
"$adb" shell settings put global window_animation_scale 0
"$adb" shell settings put global transition_animation_scale 0
"$adb" shell settings put global animator_duration_scale 0
echo "::endgroup::"

echo "::group::Install and launch"
"$adb" install -r -g "$apk"
"$adb" logcat -c
# Started through its launcher entry, as from the home screen.
"$adb" shell monkey -p "$package" -c android.intent.category.LAUNCHER 1 > /dev/null
echo "::endgroup::"

# Let the first screens compose (splash, then onboarding) and any deferred work start.
sleep 20
"$adb" logcat -d > "$out/logcat.txt"
pid="$("$adb" shell pidof "$package" | tr -d '\r' || true)"

crash="$(grep -nE "FATAL EXCEPTION|ANR in $package|Process: $package" "$out/logcat.txt" || true)"
if [ -n "$crash" ] || [ -z "$pid" ]; then
  echo "::error title=Emulator smoke::$package crashed or is not running after launch. See the emulator-smoke artifact (logcat.txt)."
  # Only the app's own crash block: no other process's log lines.
  grep -A40 -E "FATAL EXCEPTION" "$out/logcat.txt" | head -80 || true
  exit 1
fi
"$adb" exec-out screencap -p > "$out/launch.png" || true
echo "$package is running (pid $pid) 20 s after launch; no crash in logcat."
