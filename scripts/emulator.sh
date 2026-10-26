#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
toolchain_dir="${THORSHELF_TOOLCHAIN_DIR:-/tmp/thorshelf-toolchain}"
export JAVA_HOME="${JAVA_HOME:-$toolchain_dir/jdk}"
export ANDROID_HOME="${ANDROID_HOME:-$toolchain_dir/android-sdk}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$toolchain_dir/android-user-home}"
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$toolchain_dir/avd}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

avd_name="ThorShelf_API_33"
emulator_port="${THORSHELF_EMULATOR_PORT:-5554}"
serial="emulator-$emulator_port"
emulator_bin="$ANDROID_HOME/emulator/emulator"
adb_bin="$ANDROID_HOME/platform-tools/adb"
apk="$project_dir/app/build/outputs/apk/debug/app-debug.apk"
log="$toolchain_dir/emulator.log"

if [[ ! -x "$emulator_bin" || ! -f "$ANDROID_HOME/system-images/android-33/google_apis/x86_64/system.img" ]]; then
    echo 'Install the Android emulator and system-images;android-33;google_apis;x86_64 with sdkmanager first.' >&2
    exit 1
fi
if [[ ! -f "$apk" ]]; then
    echo 'Build the APK first with ./scripts/build.sh.' >&2
    exit 1
fi
mkdir -p "$ANDROID_AVD_HOME" "$ANDROID_USER_HOME"

if [[ ! -f "$ANDROID_AVD_HOME/$avd_name.ini" ]]; then
    printf 'no\n' | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd \
        --name "$avd_name" --package 'system-images;android-33;google_apis;x86_64' --device pixel_5
    python3 - "$ANDROID_AVD_HOME/$avd_name.avd/config.ini" <<'PY'
import pathlib, sys
path = pathlib.Path(sys.argv[1])
values = {}
for line in path.read_text().splitlines():
    if "=" in line:
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
values.update({
    "avd.ini.displayname": "ThorShelf - Android 13",
    "hw.ramSize": "4096",
    "hw.cpu.ncore": "4",
    "disk.dataPartition.size": "6G",
    "hw.keyboard": "yes",
    "showDeviceFrame": "no",
    "hw.lcd.width": "1080",
    "hw.lcd.height": "1920",
    "hw.lcd.density": "320",
    "hw.initialOrientation": "landscape",
    "skin.dynamic": "yes",
})
path.write_text("".join(f"{key}={value}\n" for key, value in values.items()))
PY
fi

if "$adb_bin" devices | awk -v serial="$serial" '$1 == serial {found=1} END {exit !found}'; then
    running_avd="$("$adb_bin" -s "$serial" emu avd name 2>/dev/null | head -n 1 | tr -d '\r')"
    if [[ "$running_avd" != "$avd_name" ]]; then
        echo "Port $emulator_port belongs to another emulator. Set THORSHELF_EMULATOR_PORT to a free even port." >&2
        exit 1
    fi
else
    # A user service survives terminal/task process-group cleanup. It is transient,
    # starts no login service, and goes away when the emulator window is closed.
    if command -v systemd-run >/dev/null 2>&1 && systemctl --user show-environment >/dev/null 2>&1; then
        emulator_unit="thorshelf-emulator-$emulator_port"
        service_env=()
        for name in ANDROID_HOME ANDROID_USER_HOME ANDROID_AVD_HOME DISPLAY WAYLAND_DISPLAY XDG_RUNTIME_DIR XAUTHORITY; do
            if [[ -n "${!name:-}" ]]; then service_env+=("--setenv=$name=${!name}"); fi
        done
        systemd-run --user --collect --unit="$emulator_unit" \
            --property="StandardOutput=append:$log" --property="StandardError=append:$log" \
            "${service_env[@]}" \
            "$emulator_bin" -avd "$avd_name" -port "$emulator_port" \
            -gpu "${THORSHELF_EMULATOR_GPU:-auto}" -no-boot-anim \
            -timezone America/Bogota -no-metrics
    else
        nohup "$emulator_bin" -avd "$avd_name" -port "$emulator_port" \
            -gpu "${THORSHELF_EMULATOR_GPU:-auto}" -no-boot-anim \
            -timezone America/Bogota -no-metrics >"$log" 2>&1 </dev/null &
        emulator_pid=$!
    fi
    printf 'Emulator log: %s\n' "$log"
fi

echo "Waiting for Android on $serial…"
deadline=$((SECONDS + 300))
while [[ "$("$adb_bin" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]]; do
    if [[ -n "${emulator_unit:-}" ]] && ! systemctl --user is-active --quiet "$emulator_unit"; then
        tail -n 35 "$log" >&2
        exit 1
    fi
    if [[ -n "${emulator_pid:-}" ]] && ! kill -0 "$emulator_pid" 2>/dev/null; then
        tail -n 35 "$log" >&2
        exit 1
    fi
    if (( SECONDS >= deadline )); then
        echo "Android has not finished booting. Check $log." >&2
        exit 1
    fi
    sleep 2
done

"$adb_bin" -s "$serial" shell settings put system accelerometer_rotation 0
"$adb_bin" -s "$serial" shell settings put system user_rotation 1
"$adb_bin" -s "$serial" shell wm user-rotation lock 1
"$adb_bin" -s "$serial" shell input keyevent KEYCODE_WAKEUP
"$adb_bin" -s "$serial" shell wm dismiss-keyguard
"$adb_bin" -s "$serial" install -r "$apk"
"$adb_bin" -s "$serial" shell am start -W -n dev.thorshelf.app/.MainActivity
printf '\nThorShelf is open in the Android emulator (%s).\n' "$serial"
