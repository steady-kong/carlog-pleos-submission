#!/usr/bin/env bash
# Start a bounded, visible project-local Pleos AVD for a manual demo.
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
if (( $# != 0 )); then printf '%s\n' '사용법: scripts/pleos-demo.sh' >&2; exit 2; fi
limit="${CARLOG_PLEOS_MAX_SECONDS:-300}"
if [[ ! "$limit" =~ ^[0-9]{1,3}$ ]] || (( 10#$limit < 1 || 10#$limit > 300 )); then
  printf '%s\n' 'CARLOG_PLEOS_MAX_SECONDS는 1~300초여야 합니다.' >&2; exit 2
fi

demo_main() {
set -euo pipefail
ulimit -c 0
headless="${CARLOG_PLEOS_HEADLESS:-0}"
if [[ "$headless" != 0 && "$headless" != 1 ]]; then
  printf '%s\n' 'CARLOG_PLEOS_HEADLESS는 0 또는 1이어야 합니다.' >&2; exit 2
fi
if [[ "$headless" == 0 && -z "${DISPLAY:-}" && -z "${WAYLAND_DISPLAY:-}" ]]; then
  printf "%s\n" "그래픽 디스플레이가 없습니다. 워크스테이션의 그래픽 터미널에서 실행하세요." >&2
  exit 1
fi
export JAVA_HOME="$project_dir/.toolchains/jdk-17"
export ANDROID_HOME="$project_dir/.toolchains/android"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$project_dir/.android-user-home"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
adb="$ANDROID_HOME/platform-tools/adb"
emulator="$ANDROID_HOME/emulator/emulator"
serial=emulator-5556
window_args=()
if [[ "$headless" == 1 ]]; then window_args=(-no-window); fi
ram_mb="${CARLOG_PLEOS_RAM_MB:-3072}"
cores="${CARLOG_PLEOS_CORES:-2}"
if [[ ! "$ram_mb" =~ ^[0-9]{1,4}$ || ! "$cores" =~ ^[0-9]$ ]] || (( 10#$ram_mb < 2048 || 10#$ram_mb > 4096 || 10#$cores < 1 || 10#$cores > 4 )); then
  printf '%s\n' '에뮬레이터 RAM은 2048~4096 MB, CPU는 1~4개로 지정하세요.' >&2; exit 2
fi
if [[ ! -x "$adb" || ! -x "$emulator" || ! -f "$ANDROID_AVD_HOME/carlog_pleos_2_1.ini" ]]; then
  printf '%s\n' '프로젝트 전용 ADB, 에뮬레이터 또는 carlog_pleos_2_1 AVD가 없습니다.' >&2; exit 1
fi
# Check both console and ADB ports, and the named AVD on any port. Do not
# remove lock files or stop an existing emulator. Repeat after the build.
check_available() {
  if "$adb" devices | awk 'NR > 1 {print $1}' | grep -Fxq "$serial"; then
    printf '%s\n' 'emulator-5556이 이미 사용 중입니다.' >&2; return 1
  fi
  python3 - "$ANDROID_AVD_HOME/carlog_pleos_2_1.avd" <<'PY'
import os, socket, subprocess, sys
from pathlib import Path
avd = Path(sys.argv[1])
for lock in avd.glob('*.lock'):
    # The emulator can leave these ordinary files after a clean shutdown.
    # A process with it open may still own a lock; never remove or modify it.
    if lock.name in {'multiinstance.lock', 'snapshot.lock.lock'} and not lock.is_symlink() and lock.is_file():
        try:
            result = subprocess.run(['fuser', '-s', str(lock)], check=False)
        except OSError:
            sys.exit('AVD 점유 확인에 fuser가 필요합니다.')
        if result.returncode == 0:
            sys.exit('AVD 잠금 파일을 사용 중인 프로세스가 있습니다.')
        if result.returncode != 1:
            sys.exit('AVD 잠금 파일의 점유 여부를 확인할 수 없습니다.')
    else:
        sys.exit('AVD 잠금 항목이 있습니다. 기존 AVD를 확인하세요.')
for entry in Path('/proc').iterdir():
    if not entry.name.isdigit():
        continue
    try:
        args = (entry / 'cmdline').read_bytes().split(b'\0')
    except (OSError, PermissionError):
        continue
    if b'-avd' in args and b'carlog_pleos_2_1' in args:
        sys.exit('carlog_pleos_2_1 AVD가 이미 실행 중입니다.')
for port in (5556, 5557):
    with socket.socket() as sock:
        try:
            sock.bind(('127.0.0.1', port))
        except OSError:
            sys.exit(f'포트 {port}가 이미 사용 중입니다.')
PY
}
check_available
"$project_dir/scripts/gradle.sh" --no-daemon :app:assembleDebug
check_available
mkdir -p "$ANDROID_USER_HOME"
log_file="$(mktemp "$ANDROID_USER_HOME/pleos-demo.XXXXXX.log")"
"$emulator" -avd carlog_pleos_2_1 -read-only "${window_args[@]}" -no-audio -no-snapshot -gpu swiftshader_indirect -memory "$ram_mb" -cores "$cores" -port 5556 >"$log_file" 2>&1 &
avd_pid=$!
cleanup() {
  kill -TERM "$avd_pid" 2>/dev/null || true
  wait "$avd_pid" 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
ready=false
for ((attempt=0; attempt<90; attempt++)); do
  if ! kill -0 "$avd_pid" 2>/dev/null; then printf '에뮬레이터가 종료됐습니다. 로그: %s\n' "$log_file" >&2; exit 1; fi
  if "$adb" -s "$serial" shell dumpsys user 2>/dev/null | grep -Eq 'Started users state:.*10=RUNNING_UNLOCKED'; then ready=true; break; fi
  sleep 1
done
if [[ "$ready" != true ]]; then printf '운전자 프로필이 준비되지 않았습니다. 로그: %s\n' "$log_file" >&2; exit 1; fi
ready=false
for ((attempt=0; attempt<30; attempt++)); do
  if ! kill -0 "$avd_pid" 2>/dev/null; then printf '에뮬레이터가 종료됐습니다. 로그: %s\n' "$log_file" >&2; exit 1; fi
  if "$adb" -s "$serial" shell pidof ai.pleos.service.ui >/dev/null 2>&1; then ready=true; break; fi
  sleep 1
done
if [[ "$ready" != true ]]; then printf 'Pleos UI 서비스가 준비되지 않았습니다. 로그: %s\n' "$log_file" >&2; exit 1; fi
sleep 5
"$adb" -s "$serial" install --user 10 -r "$project_dir/app/build/outputs/apk/debug/app-debug.apk"
"$adb" -s "$serial" shell am start --user 10 -n kr.carlog.pleos/.MainActivity
printf '시연 중입니다. Ctrl+C로 종료하며 전체 제한 시간 후 자동 종료합니다. 로그: %s\n' "$log_file"
wait "$avd_pid"

}
export project_dir
export -f demo_main
exec timeout --signal=TERM --kill-after=10s "${limit}s" bash -c demo_main
