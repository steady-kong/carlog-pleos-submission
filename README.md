# Carlog Pleos

기존 Carlog PWA와 별개인 Android Automotive용 초기 MVP. 화면의 모든 차량 신호는 **시뮬레이션(MOCK)** 이다. 차량 관리와 사용자가 지정한 순간의 전후 기록을 결합한다.

## 프로젝트 전용 빌드 환경

이 워크스테이션에서는 JDK 17과 Android SDK 34를 `.toolchains/`에 별도로 준비했다. 시스템 Java, 기존 Android SDK 및 전역 설정은 변경하지 않는다. Gradle 8.7은 체크섬이 지정된 Wrapper로 실행하며 배포본과 의존성 캐시는 `.gradle-user-home/`에 보관한다. 다음 스크립트는 환경변수를 자신의 프로세스에만 설정한다.

```bash
./scripts/gradle.sh :app:testDebugUnitTest :app:assembleDebug
```

빌드 결과는 `app/build/outputs/apk/debug/app-debug.apk`에 생성된다. 최초 실행 시 Gradle 및 의존성 다운로드를 위한 네트워크가 필요하다. JDK, SDK, 캐시 디렉터리는 Git에 포함되지 않으며 다른 워크스테이션에는 별도로 준비해야 한다. `.toolchains/jdk-17`에 JDK 17, `.toolchains/android`에 Android SDK Platform 34와 Build-Tools 34.0.0 및 동의한 SDK 라이선스가 필요하다. SDK의 `cmdline-tools`, `platform-tools`도 이 작업공간에 별도 설치되어 있다. 프로젝트에 `local.properties`의 `sdk.dir`이 설정되어 있으면 Android Gradle Plugin이 환경변수보다 우선하므로, 스크립트는 기존 설정을 덮어쓰지 않고 중단한다.

JDK와 Android SDK를 새로 설치할 때는 별도 디렉터리를 지정하고 해당 SDK 라이선스를 검토·동의해야 한다. SDK Manager 또는 Android Studio의 기존 SDK를 변경하지 않는다. 공식 Pleos 에뮬레이터 이미지는 별도 접근이 필요하다.

2026-11-02 JDK 17과 Android SDK 34 환경에서 debug APK 빌드와 JVM 단위 테스트 5개가 성공했다. 일반 Android Automotive API 34 에뮬레이터에서 앱 시작, Mock 이벤트 수집 완료 및 앱 재시작 후 이벤트 목록 유지를 확인했다. Pleos 전용 에뮬레이터와 실차 동작은 아직 검증하지 않았다.

## 구현 범위

- Mock 신호 Flow와 시간 기반 60초 링 버퍼, 개별 시각, 발생 전 기록 및 이후 60초 수집. 동시 이벤트 지원. 앱 종료 시 수집이 중단되며 다음 시작 시 interrupted로 표시.
- Room 차량 UUID, 주유/지출/정비 기록, 거리 기반 소모품 주기, 이벤트와 신호 저장 및 조회.
- 대시보드, 신뢰 연비, 주유/정비/일반 지출과 분류별·월별 통계, 이벤트 분류/메모, 타임라인, 실제 시각 기준 연료 그래프, 발생 시점 좌표와 외부 지도 앱 연결, 정비 참고 리포트 및 v1 이벤트 JSON 텍스트 공유.
- 기존 PWA 계산 중 주유 연비, 거리 임박 판정, 주행거리 추정을 Kotlin으로 이식. PWA 코드는 변경하지 않음.

## 제한 및 후속 작업

실제 Pleos SDK, 음성 명령, 차량 물리 버튼, 실차 위치, 백그라운드 수집은 연결하지 않았다. SDK 접근·권한·차종별 capability 검증 후 별도 VehicleDataSource를 구현해야 한다. 현재 화면의 주행거리 갱신은 Mock과 수동 기록의 최대값이며 추정 함수는 UI에 적용하지 않는다. 시간/엔진시간 기반 소모품 주기, 엔진시간 계산, 기록 편집/삭제, 다중 차량, 정비 결과 연결, 지도 내장, JSON 가져오기 및 파일 저장은 남았다. 일반 지출의 카테고리 저장은 Room 1→2 마이그레이션을 사용한다. 프로세스 종료 시 후반 구간은 복구되지 않으며 시계 변경과 저빈도/늦게 도착하는 신호의 처리도 실차 연동 전에 검증해야 한다. 시각화는 연료 한 항목에 한정된다. `docs/contract.md`의 교환 규격은 v1이며 웹 가져오기는 구현되지 않았다.

## Pleos 공식 문서 확인

[시작하기](https://document.pleos.ai/en/docs/pleos-only/getting-started)는 API 34, 최소 API 28 및 Pleos 에뮬레이터를 안내한다. [SDK 개요](https://document.pleos.ai/en/api-reference/connect-sdk-pleos)는 Vehicle, FusedLocation, SpeechToText 등을 설명하고 차량 API 권한의 사전 승인과 capability 확인을 요구한다. [Vehicle 소개](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/intro)는 Brake, Fuel, DrivingMode 등 도메인을 설명한다. Odometer, Safety, Tire, TurnSignal, 개별 신호·음성 호출의 실제 사용 가능 여부는 이 프로젝트에서 검증하지 않았다. SDK 의존성과 권한은 추측해 추가하지 않았다.
