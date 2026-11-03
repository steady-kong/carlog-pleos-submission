#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
export JAVA_HOME="$project_dir/.toolchains/jdk-17"
export ANDROID_HOME="$project_dir/.toolchains/android"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$project_dir/.gradle-user-home"
export ANDROID_USER_HOME="$project_dir/.android-user-home"
if [[ ! -x "$JAVA_HOME/bin/java" || ! -f "$ANDROID_HOME/platforms/android-34/android.jar" || ! -x "$ANDROID_HOME/build-tools/34.0.0/aapt2" ]]; then
  printf '%s\n' '프로젝트 전용 JDK 17 또는 Android SDK 34 / Build-Tools 34.0.0이 없습니다. README의 환경 준비 절차를 확인하세요.' >&2
  exit 1
fi
if ! "$JAVA_HOME/bin/java" -version 2>&1 | head -n 1 | grep -Eq 'version "17([.+-]|\")'; then
  printf '%s\n' '프로젝트 전용 JDK가 Java 17이 아닙니다.' >&2
  exit 1
fi
# AGP prioritizes sdk.dir over environment variables. Never silently use or rewrite another SDK.
if [[ -f "$project_dir/local.properties" ]] && grep -Eq '^[[:space:]]*sdk\.dir[[:space:]]*[:=]' "$project_dir/local.properties"; then
  printf '%s\n' 'local.properties의 sdk.dir이 환경변수보다 우선합니다. 기존 파일을 보존하기 위해 중단합니다. 프로젝트 전용 SDK를 사용하려면 해당 설정을 직접 확인하세요.' >&2
  exit 1
fi
exec "$project_dir/gradlew" -Dorg.gradle.java.installations.auto-detect=false -Dorg.gradle.java.installations.paths="$JAVA_HOME" "$@"
