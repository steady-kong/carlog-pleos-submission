# Carlog Pleos

기존 Carlog PWA와 별개인 Android Automotive용 초기 MVP. 화면의 모든 차량 신호는 **시뮬레이션(MOCK)** 이다. 차량 관리와 사용자가 지정한 순간의 전후 기록을 결합한다.

## 실행 환경

JDK 17, Android Studio, Android SDK API 34, Gradle 8.7이 필요하다. Android Studio에서 이 디렉터리를 열고 Gradle 동기화 후 API 28 이상 에뮬레이터 또는 기기에 실행한다. `./gradlew :app:testDebugUnitTest :app:assembleDebug`로 검증한다. Gradle Wrapper는 8.7이며 배포 파일의 SHA-256을 확인한다. SDK 경로는 `ANDROID_HOME` 또는 Git에서 제외되는 `local.properties`로 지정한다. 공식 Pleos 에뮬레이터 이미지는 별도 접근이 필요하다.

2026-11-02 JDK 17과 Android SDK 34 환경에서 debug APK 빌드와 JVM 단위 테스트 5개가 성공했다. APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성된다. 일반 Android Automotive API 34 에뮬레이터에서 앱 시작, Mock 이벤트 수집 완료 및 앱 재시작 후 이벤트 목록 유지를 확인했다. Pleos 전용 에뮬레이터와 실차 동작은 아직 검증하지 않았다.

일반 Ubuntu 환경에서 Android Studio의 SDK Manager로 Android SDK Platform 34, Build-Tools와 Platform-Tools를 설치하고 SDK 라이선스에 동의한다. JDK 17과 Gradle 8.7을 준비한 뒤 `JAVA_HOME`과 `ANDROID_HOME`을 각각 JDK와 SDK 경로로 설정하고 `java -version`, `gradle --version`, `"$ANDROID_HOME/platform-tools/adb" version`으로 확인한다. Android Studio의 SDK 설정을 사용한다면 `local.properties`에 SDK 경로가 생성될 수 있으며 이 파일은 Git에서 제외된다. 이후 프로젝트 루트에서 위 검증 명령을 실행한다. 의존성 최초 다운로드에는 Google Maven, Maven Central 및 Gradle Plugin Portal 접근이 필요하다.

Gradle 8.7이 확보되면 프로젝트 루트에서 `gradle wrapper --gradle-version 8.7 --distribution-type bin`으로 wrapper를 생성할 수 있다. 생성된 wrapper는 공식 Gradle 배포본의 무결성을 검증하도록 SHA-256을 설정한 뒤 사용한다. 이 저장소의 Android 컴파일과 단위 테스트는 아직 실행되지 않았다.

## 구현 범위

- Mock 신호 Flow와 시간 기반 60초 링 버퍼, 개별 시각, 발생 전 기록 및 이후 60초 수집. 동시 이벤트 지원. 앱 종료 시 수집이 중단되며 다음 시작 시 interrupted로 표시.
- Room 차량 UUID, 주유/지출/정비 기록, 거리 기반 소모품 주기, 이벤트와 신호 저장 및 조회.
- 대시보드, 신뢰 연비, 기록 입력, 이벤트 분류/메모, 타임라인, 연료 그래프, 좌표와 외부 지도 앱 연결, 텍스트 공유 리포트.
- 기존 PWA 계산 중 주유 연비, 거리 임박 판정, 주행거리 추정을 Kotlin으로 이식. PWA 코드는 변경하지 않음.

## 제한 및 후속 작업

실제 Pleos SDK, 음성 명령, 차량 물리 버튼, 실차 위치, 백그라운드 수집은 연결하지 않았다. SDK 접근·권한·차종별 capability 검증 후 별도 VehicleDataSource를 구현해야 한다. 현재 화면의 주행거리 갱신은 Mock과 수동 기록의 최대값이며 추정 함수는 UI에 적용하지 않는다. 시간/엔진시간 기반 소모품 주기, 엔진시간 계산, 상세 지출 통계, 기록 편집/삭제, 다중 차량, 정비 결과 연결, 지도 내장, JSON 교환 구현은 남았다. 프로세스 종료 시 후반 구간은 복구되지 않으며 시계 변경과 저빈도/늦게 도착하는 신호의 처리도 실차 연동 전에 검증해야 한다. 시각화는 연료 한 항목에 한정된다. `docs/contract.md`의 교환 규격은 설계 초안이다.

## Pleos 공식 문서 확인

[시작하기](https://document.pleos.ai/en/docs/pleos-only/getting-started)는 API 34, 최소 API 28 및 Pleos 에뮬레이터를 안내한다. [SDK 개요](https://document.pleos.ai/en/api-reference/connect-sdk-pleos)는 Vehicle, FusedLocation, SpeechToText 등을 설명하고 차량 API 권한의 사전 승인과 capability 확인을 요구한다. [Vehicle 소개](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/intro)는 Brake, Fuel, DrivingMode 등 도메인을 설명한다. Odometer, Safety, Tire, TurnSignal, 개별 신호·음성 호출의 실제 사용 가능 여부는 이 프로젝트에서 검증하지 않았다. SDK 의존성과 권한은 추측해 추가하지 않았다.
