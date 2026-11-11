#!/usr/bin/env bash
# Run project tests with a bounded, project-local Pleos AVD lifecycle.
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
if [[ "${1:-}" != "--bounded-child" ]]; then
  limit="${CARLOG_PLEOS_MAX_SECONDS:-180}"
  if [[ ! "$limit" =~ ^[0-9]+$ ]] || (( 10#$limit < 1 || 10#$limit > 300 )); then
    printf '%s\n' 'CARLOG_PLEOS_MAX_SECONDS는 1~300초여야 합니다.' >&2
    exit 2
  fi
  if (( $# == 0 )); then
    printf '%s\n' '사용법: scripts/pleos-emulator-test.sh :app:connectedDebugAndroidTest [Gradle 인수...]' >&2
    exit 2
  fi
  # timeout owns a separate process group and bounds both the AVD and test.
  exec timeout --signal=TERM --kill-after=10s "${limit}s" "$0" --bounded-child "$@"
fi
shift
export JAVA_HOME="$project_dir/.toolchains/jdk-17"
export ANDROID_HOME="$project_dir/.toolchains/android"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$project_dir/.android-user-home"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
serial=emulator-5556
adb="$ANDROID_HOME/platform-tools/adb"
emulator="$ANDROID_HOME/emulator/emulator"
if [[ ! -x "$adb" || ! -x "$emulator" ]]; then
  printf '%s\n' '프로젝트 전용 ADB 또는 에뮬레이터가 없습니다.' >&2
  exit 1
fi
ram_mb="${CARLOG_PLEOS_RAM_MB:-3072}"
cores="${CARLOG_PLEOS_CORES:-2}"
if [[ ! "$ram_mb" =~ ^[0-9]+$ || ! "$cores" =~ ^[0-9]+$ ]] || (( 10#$ram_mb < 2048 || 10#$ram_mb > 4096 || 10#$cores < 1 || 10#$cores > 4 )); then
  printf '%s\n' '에뮬레이터 RAM은 2048~4096 MB, CPU는 1~4개로 지정하세요.' >&2
  exit 2
fi
if "$adb" devices | awk 'NR > 1 {print $1}' | grep -Fxq "$serial"; then
  printf '%s\n' 'emulator-5556이 이미 사용 중입니다. 기존 프로세스는 건드리지 않습니다.' >&2
  exit 1
fi
# Compile recognized instrumentation targets before starting the costly AVD.
# Keep project properties (including isolated SDK probe flags); unknown Gradle
# arguments fall back to the original single invocation for compatibility.
prebuild_tasks=()
prebuild_properties=()
prebuild_supported=true
for argument in "$@"; do
  if [[ "$argument" =~ ^:(app|pleos-vehicle|pleos-location|pleos-speech):connectedDebugAndroidTest$ ]]; then
    module="${BASH_REMATCH[1]}"
    prebuild_tasks+=(":$module:assembleDebug" ":$module:assembleDebugAndroidTest")
  elif [[ "$argument" == -P* ]]; then
    prebuild_properties+=("$argument")
  else
    prebuild_supported=false
  fi
done
if [[ "$prebuild_supported" == true && ${#prebuild_tasks[@]} -gt 0 ]]; then
  "$project_dir/scripts/gradle.sh" --no-daemon "${prebuild_tasks[@]}" "${prebuild_properties[@]}"
fi
# Another emulator can claim the port during prebuild; check again before launch.
if "$adb" devices | awk 'NR > 1 {print $1}' | grep -Fxq "$serial"; then
  printf '%s\n' '빌드 중 emulator-5556이 사용 중이 되었습니다. 기존 프로세스는 건드리지 않습니다.' >&2
  exit 1
fi
mkdir -p "$ANDROID_USER_HOME"
"$emulator" -avd carlog_pleos_2_1 -read-only -no-window -no-audio -no-snapshot -gpu swiftshader_indirect -memory "$ram_mb" -cores "$cores" -port 5556 > "$ANDROID_USER_HOME/pleos-emulator.log" 2>&1 &
avd_pid=$!
cleanup() {
  # Only the process launched by this script is targeted; never kill other AVDs.
  kill -TERM "$avd_pid" 2>/dev/null || true
  wait "$avd_pid" 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
ready=false
for ((attempt=0; attempt<90; attempt++)); do
  if ! kill -0 "$avd_pid" 2>/dev/null; then
    printf '%s\n' 'Pleos 에뮬레이터가 부팅 전에 종료됐습니다.' >&2
    exit 1
  fi
  if "$adb" -s "$serial" shell dumpsys user 2>/dev/null | grep -Eq 'Started users state:.*10=RUNNING_UNLOCKED'; then
    ready=true
    break
  fi
  sleep 1
done
if [[ "$ready" != true ]]; then
  printf '%s\n' 'Pleos 에뮬레이터 운전자 프로필이 준비되지 않았습니다.' >&2
  exit 1
fi
# The profile can unlock before Pleos finishes launching its boot UI. Wait for
# that service and a brief quiet period, otherwise it can cover the test Activity.
ui_ready=false
for ((attempt=0; attempt<30; attempt++)); do
  if "$adb" -s "$serial" shell pidof ai.pleos.service.ui >/dev/null 2>&1; then
    ui_ready=true
    break
  fi
  if ! kill -0 "$avd_pid" 2>/dev/null; then
    printf '%s\n' 'Pleos 에뮬레이터가 시작 중 종료됐습니다.' >&2
    exit 1
  fi
  sleep 1
done
if [[ "$ui_ready" != true ]]; then
  printf '%s\n' 'Pleos UI 서비스가 준비되지 않았습니다.' >&2
  exit 1
fi
sleep 5
# Avoid leaving a reusable Gradle daemon running after this bounded test.
ANDROID_SERIAL="$serial" "$project_dir/scripts/gradle.sh" --no-daemon "$@"
