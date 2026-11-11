# Carlog Pleos

기존 Carlog PWA와 별개인 Android Automotive용 초기 MVP. 화면의 모든 차량 신호는 **시뮬레이션(MOCK)** 이다. 차량 관리와 사용자가 지정한 순간의 전후 기록을 결합한다.

변경 이력과 기록 기준은 [CHANGELOG.md](CHANGELOG.md)를 참고한다. 기능 추가·수정·삭제, 버그 수정, 호환성이나 데이터베이스 변경, 검증 결과를 `[Unreleased]` 아래의 `Added`, `Changed`, `Fixed`, `Removed`, `Verification` 항목에 기록한다. 각 작업을 마칠 때 코드와 변경 이력을 함께 갱신하고, 정식 배포 때만 버전과 날짜를 확정한다.

요구사항별 구현·검증 범위와 남은 과제는 [MVP 확인 현황](docs/mvp-status.md)에 정리되어 있다.

## 전용 에뮬레이터 시연 실행 (시험 단계)

워크스테이션의 그래픽 터미널에서 `scripts/pleos-demo.sh`를 실행한다. 전용 AVD를 읽기 전용으로 시작하므로 종료 후 시연 중 입력한 데이터는 보존되지 않는다. 기본 제한은 빌드와 부팅을 포함해 300초이며 Ctrl+C로 일찍 종료할 수 있다. CPU 2개와 RAM 3072MB를 사용하고, 다른 AVD나 포트가 점유 중이면 중단한다. 제한 시간은 최장 300초로, 준비와 전후 각 60초 수집에 필요한 시간을 고려한다.

창 없는 검증 모드(`CARLOG_PLEOS_HEADLESS=1 scripts/pleos-demo.sh`)에서 앱 설치와 실행을 확인했다. 워크스테이션에서 사용자가 그래픽 창으로 이전 구성의 Carlog 화면이 표시되는 것을 확인했다. 최신 화면 구성의 전체 수동 시연과 실제 시연 기기는 미검증이다. 이 스크립트는 실행 중인 화면을 보여 주는 용도이며 별도의 완성된 녹화 자료는 제공하지 않는다.

정차 시연에서 `이벤트 화면`의 `이벤트 기록`으로 수집을 시작하고, 최근 이벤트를 선택해 요약·정비 참고 리포트를 확인한다. 원시 신호와 그래프는 상세의 `상세 데이터 보기`에서 펼친다. `차량 관리 화면`의 `차량 관리 기록 추가`에서 주유·정비·소모품을 입력한다. Mock 수집 제어와 JSON·백업 도구는 각 화면의 테스트 도구에서 접근한다.

## 프로젝트 전용 빌드 환경

이 워크스테이션에서는 JDK 17과 Android SDK 34를 `.toolchains/`에 별도로 준비했다. 시스템 Java, 기존 Android SDK 및 전역 설정은 변경하지 않는다. Gradle 8.7은 체크섬이 지정된 Wrapper로 실행하며 배포본과 의존성 캐시는 `.gradle-user-home/`에 보관한다. 다음 스크립트는 환경변수를 자신의 프로세스에만 설정한다.

```bash
./scripts/gradle.sh :app:testDebugUnitTest :app:assembleDebug
```

빌드 결과는 `app/build/outputs/apk/debug/app-debug.apk`에 생성된다. 최초 실행 시 Gradle 및 의존성 다운로드를 위한 네트워크가 필요하다. JDK, SDK, 캐시 디렉터리는 Git에 포함되지 않으며 다른 워크스테이션에는 별도로 준비해야 한다. `.toolchains/jdk-17`에 JDK 17, `.toolchains/android`에 Android SDK Platform 34와 Build-Tools 34.0.0 및 동의한 SDK 라이선스가 필요하다. SDK의 `cmdline-tools`, `platform-tools`도 이 작업공간에 별도 설치되어 있다. 프로젝트에 `local.properties`의 `sdk.dir`이 설정되어 있으면 Android Gradle Plugin이 환경변수보다 우선하므로, 스크립트는 기존 설정을 덮어쓰지 않고 중단한다. APK 빌드와 별도로 시연 기기의 설치·실행 절차는 검증되지 않았다. 시연 전 준비와 리허설은 [체크리스트](docs/demo.md#시연-전-준비리허설-체크리스트)를 참고한다.

별도 Pleos 실험 모듈까지 단위 시험·빌드하려면 다음 명령을 사용한다. 공식 Maven 의존성에 접근해야 하며 실험 모듈은 기본 Mock 앱에 연결되지 않는다.

```bash
./scripts/gradle.sh --no-daemon :app:testDebugUnitTest :app:assembleDebug \
  :pleos-vehicle:testDebugUnitTest :pleos-vehicle:assembleDebug \
  :pleos-location:testDebugUnitTest :pleos-location:assembleDebug \
  :pleos-speech:testDebugUnitTest :pleos-speech:assembleDebug
```

웹 교환 스키마와 예시를 독립 검증하려면 `python3 scripts/check-json-contracts.py`를 실행한다. 이 선택형 검사는 기존 Python의 `jsonschema` 패키지가 필요하며 패키지를 자동 설치하지 않는다. 앱의 Kotlin 테스트는 예시를 가져와 같은 의미로 다시 내보내는지도 확인한다.

JDK와 Android SDK를 새로 설치할 때는 별도 디렉터리를 지정하고 해당 SDK 라이선스를 검토·동의해야 한다. SDK Manager 또는 Android Studio의 기존 SDK를 변경하지 않는다. 공식 Pleos 에뮬레이터 이미지는 별도 접근이 필요하다.

Pleos 에뮬레이터가 필요한 시험은 `scripts/pleos-emulator-test.sh :app:connectedDebugAndroidTest`로 실행한다. 스크립트가 직접 시작한 전용 AVD만 시험 종료 또는 중단 신호 수신 시 정리하고, 기본 180초(최대 300초) 시간 제한을 적용한다. `CARLOG_PLEOS_MAX_SECONDS`로 1~300초를 선택할 수 있다. 신호가 전달되지 않는 작업 중단은 즉시 감지할 수 없으므로 시간 제한이 최후의 정리 수단이며, 실행 중인 다른 `emulator-5556`은 건드리지 않고 중단한다.

일반적인 `:모듈:connectedDebugAndroidTest`와 `-P` 옵션 조합은 AVD 부팅 전에 시험 APK를 먼저 빌드한다. 다른 Gradle 인수가 있으면 호환성을 위해 기존 방식으로 실행하며, 시간 제한은 사전 빌드를 포함한다.

전용 시험은 Gradle의 `--no-daemon`을 사용한다. 현재 JVM 설정에서는 빌드가 일회용 프로세스를 띄우며, 정상 종료와 75초 제한 시간 종료 시험에서 AVD와 Gradle 프로세스가 남지 않은 것을 확인했다. 강제 종료 등 모든 실패 형태에서의 정리를 보장하는 것은 아니다. 다른 ADB 서버나 다른 프로젝트의 Gradle 프로세스는 종료하지 않는다.

전용 시험의 기본값은 가상 CPU 2개와 게스트 RAM 3072MB다. `CARLOG_PLEOS_CORES`(1~4), `CARLOG_PLEOS_RAM_MB`(2048~4096)로 실행별 조정할 수 있으며 AVD 설정 파일은 변경하지 않는다. 게스트 RAM 외에 렌더링 등의 호스트 메모리와 CPU가 필요하므로 이 값이 전체 사용량 상한은 아니다. 2 CPU·3GB 설정은 일부 화면 시험에서만 검증했다.

JDK 17과 Android SDK 34 환경에서 debug APK와 JVM 단위 테스트를 검증했다. 일반 Android Automotive 및 Pleos 전용 에뮬레이터에서 Mock 앱을 실행하고, 별도 SDK 실험 모듈에서 주행거리·배터리 충전율 등 요청의 콜백을 확인했다. 연료 등 일부 요청은 미지원 오류를 반환했으며 실차 동작은 아직 검증하지 않았다. 시험별 조건과 결과는 [변경 이력](CHANGELOG.md)과 [SDK 확인 기록](docs/pleos-sdk.md)에 있다.

## 구현 범위

- 이벤트 화면의 임시 공유 파일 정리는 확인 후 앱의 공유 캐시만 삭제한다. 기존 이벤트와 직접 저장한 파일은 유지되며, 수신 앱이 아직 임시 파일을 읽고 있으면 공유가 실패할 수 있다.
- 이벤트 화면의 `이벤트 기록` 버튼은 분류가 `일반 이벤트`인 이벤트를 원터치로 기록한다. 기록 후 상세 화면에서 분류와 메모를 입력·수정할 수 있다. `음성 기록`은 정해진 명령어가 아니라 자유 발화를 메모로 받아 인식 후 차량 사용 상태를 확인한 시점에 이벤트 저장을 요청한다. 상태 확인이 지연되면 인식 완료보다 기록 시각이 늦어진다. 설치된 Android 음성 인식 앱이 필요하고 해당 앱이 음성을 네트워크로 전송할 수 있다. Pleos SpeechToText나 주행 중 음성 실행은 아직 지원·검증하지 않았다. [Android 음성 인식 Intent](https://developer.android.com/reference/android/speech/RecognizerIntent), [차량 앱 주행 제한](https://developer.android.com/training/cars/parked/automotive-os)을 참고한다.
- Mock 신호 Flow와 시간 기반 메모리 링 버퍼, 개별 시각, 발생 전후 각 60초 기록. 저장 시작 지연 시 앞선 구간을 보존하기 위해 메모리 버퍼는 저장 지연과 허용되는 미래 시각 오차를 고려해 최대 122초를 유지한다. 동시 이벤트 지원. 같은 앱 프로세스에서는 한 화면만 수집을 소유하며, 다른 화면은 기존 화면이 종료된 뒤 수집을 다시 시작할 수 있다. 이 제한은 별도 프로세스 사이의 조정을 보장하지 않는다. 메모리 버퍼는 최대 10만 신호를 보관하며 한도 도달 시 수집과 진행 중 이벤트를 중단하고 알린다. 화면이 가려져도 앱 프로세스가 유지되면 Mock 수집이 계속될 수 있으며, 프로세스 종료로 미완료된 이벤트는 다음 시작 시 interrupted로 표시한다. 사용자가 Mock 수집을 중단하면 진행 중인 이벤트는 interrupted로 저장되고, 다시 시작하면 이전 버퍼는 사용하지 않는다. Mock 신호는 10초 넘게 갱신되지 않으면 마지막 값으로 표시한다. 교체 판정에 시뮬레이션 주행거리 적용을 선택한 경우 마지막 값도 판정에 남고 화면에 표시된다. 장시간 백그라운드 수집은 보장하지 않는다.
- 실시간 신호가 기기 시각보다 2초를 초과해 앞서면 버퍼를 보호하기 위해 수집과 진행 중인 이벤트를 중단한다. 이 허용치는 앱 정책이며 실차 SDK의 시간 기준은 아직 검증하지 않았다.
- 대시보드에 시뮬레이션 연료와 EV 배터리 충전율을 별도로 표시하고 각각 마지막 신호 시각·수집 상태를 표시한다. 두 값은 시험용이며 늦게 도착한 과거 신호가 최신값을 덮어쓰지 않는다.
- Room 차량 UUID와 차량 이름·등록 주행거리 수정, 독립 주행거리 및 주유/지출/정비 기록의 입력·편집·소프트 삭제, 거리·기간·엔진오일 가동시간 기반 소모품 주기의 수정·소프트 삭제와 교체 기준 갱신, 이벤트와 신호 저장 및 조회.
- 지출 통계의 기간 선택과 월별 합계. 주행거리는 앱에 입력한 독립 주행거리 및 주유·정비·지출 기록의 계기판 값으로 계산한다. PWA 기록 가져오기는 아직 지원하지 않는다.
- 유효한 입력 주행거리의 기간이 14일 이상이면 PWA 계산 규칙에 따른 추정 주행거리를 별도로 표시한다. 추정값은 실측이 아니며 사용자가 선택한 경우에만 교체 판정 후보에 포함된다. 입력·선택한 추정 및 시뮬레이션 값 중 가장 큰 거리로 판정하고 차량 기록 자체는 변경하지 않는다.
- 현재 차량의 활성 주행거리·주유·정비·지출 기록과 소모품 주기를 별도 버전의 JSON 파일로 저장하고, 차량 UUID와 정보가 동일한 경우 미리보기 후 가져오기. 충돌하거나 삭제된 기록을 덮어쓰지 않는다. 삭제 기록과 이벤트는 포함하지 않으며 PWA 백업 복원이나 전체 백업 형식이 아니다. [데이터 계약](docs/contract.md)을 참고한다.
- 일반 지출은 주행거리를 비워 저장할 수 있다. 미입력 기록의 금액은 지출에 포함하고 주행거리 계산에서는 제외한다.
- 대시보드, 신뢰 연비, 주유/정비/일반 지출과 분류별·월별 통계, 원터치 일반 이벤트 기록과 기록 후 분류·메모 편집, 타임라인, 신호별 시계열 그래프, 발생 시점 좌표와 외부 지도 앱 연결, 이벤트에 연결한 정비 결과, 정비 참고 리포트 및 v1·v2 이벤트 JSON 공유·파일 저장·가져오기. Mock 수집 제어와 이벤트 JSON 가져오기는 이벤트 화면의 `테스트 도구 펼치기` 안에 있다.
- 이벤트 상세와 리포트의 전후 신호 수, 실제 기록 시각 범위 및 시각 종류. 리포트에는 발생 전 마지막 값과 발생 후 마지막 관측값을 신호별로 표시한다. 기존 기록과 Mock의 시각 종류는 미확인으로 표시한다. 범위 표시는 연속 수집이나 모든 신호의 수집을 보장하지 않음.
- Room과 이벤트 JSON v2는 신호 시각을 측정·수신·미확인으로 구분한다. 시각 종류가 모두 미확인이면 기존 v1으로, 명시된 종류가 있으면 v2로 내보낸다. v1만 지원하는 수신자는 v2 지원이 필요하다. 실제 SDK는 아직 앱에 연결하지 않았다.
- 정비 참고 리포트를 앱 안에서 먼저 확인하고 공유하거나 문서 선택기로 텍스트 파일을 저장할 수 있다. 내용은 미리보기를 연 시점의 기록이다. Pleos 이미지의 실제 파일 선택기 표시는 아직 검증하지 않았다.
- 선택한 시계열의 발생 전·시점과 발생 후 가까운 표본을 각각 최대 5개씩 UTC 밀리초 시각과 상대 시간으로 표시. 수집 종료 후 JSON 내보내기 제한을 통과하면 전체 표본이 포함된다.
- 기존 PWA 계산 중 주유 연비, 거리 임박 판정, 주행거리 추정을 Kotlin으로 이식. PWA 코드는 변경하지 않음. 연비 계산은 PWA에서 직접 추출한 [네 가지 검증 자료](docs/examples/pwa-fuel-fixtures.json)와 비교하며, `node scripts/extract-pwa-fuel-fixtures.cjs`로 표준 출력에 다시 추출할 수 있다. 스크립트의 PWA 경로와 TypeScript 위치는 `CARLOG_PWA_ROOT`, `CARLOG_TYPESCRIPT_PATH`로 지정할 수 있다.
- 소모품 상태의 거리·월말·윤년·서머타임·가동시간 등 12개 사례는 기존 PWA의 `maintenance.ts`를 UTC와 뉴욕 시간대로 실행해 추출한 결과와 비교한다. [검증 자료](docs/examples/pwa-maintenance-golden.json)와 [뉴욕 시간대 자료](docs/examples/pwa-maintenance-new-york-golden.json)에는 원본 파일의 SHA-256과 시간대가 포함된다. 필요하면 `TZ=UTC node scripts/extract-pwa-maintenance-fixtures.cjs` 또는 `TZ=America/New_York node scripts/extract-pwa-maintenance-fixtures.cjs`로 표준 출력에 다시 추출할 수 있다. 스크립트는 원본을 읽기만 하며, 다른 환경에서는 `CARLOG_PWA_ROOT`와 로컬 TypeScript 컴파일러 경로 `CARLOG_TYPESCRIPT_PATH`를 지정한다.
- 지출 통계는 PWA의 `analytics.ts`와 `models.ts`에서 추출한 [검증 자료](docs/examples/pwa-spending-golden.json)로 전체·기간 시작·기록 없음 사례를 비교한다. 비교 자료의 PWA 주행거리 목록은 동일 입력 레코드에서 명시적으로 구성한 것이며 실제 백업 변환을 검증한 것은 아니다. 같은 환경에서 `TZ=UTC node scripts/extract-pwa-spending-fixtures.cjs`로 재생성할 수 있다.
- Carlog PWA v1 백업의 차량·기록 개수와 ID 문제를 읽기 전용으로 미리보기. 실제 PWA 데이터 이관은 수행하지 않음.

## 제한 및 후속 작업

실제 Pleos SDK 음성 호출, 차량 물리 버튼, 실차 위치, 백그라운드 수집은 앱에 연결하지 않았다. 별도 Android 음성 인식 앱을 이용한 자유 발화 메모 입력은 지원한다. 공식 SDK로 컴파일하는 독립 실험 모듈과 접근·권한 확인 결과는 [SDK 확인 기록](docs/pleos-sdk.md)을 참고한다. 실차 capability와 서비스 수명 관리 검증 후 별도 VehicleDataSource에 연결해야 한다. 현재 Activity는 주행 중 사용 가능하도록 승인되거나 검증되지 않았다. 플랫폼의 주행 제한으로 화면과 이벤트 버튼이 차단될 수 있으며 시연은 정차 상태를 기준으로 한다. 자세한 조건과 에뮬레이터 시험 한계는 [시연 문서](docs/demo.md)를 참고한다. 교체 판정 주행거리는 기본적으로 등록 시 주행거리와 수동 기록의 최대값이다. 사용자가 시뮬레이션 적용을 선택하면 Mock 값도 포함하며 이를 화면에 표시한다. 추정 함수는 UI에 적용하지 않는다. 엔진오일 가동시간은 소모품의 마지막 교체일 이후 정비 기록을 우선하고 그 기록이 없으면 마지막 교체일을 기준으로 한다. 교체 기준 갱신은 날짜 단위이며 정비 이력을 자동 생성하지 않는다. 지도 내장과 PWA 백업 가져오기는 남았다. 정비 입력에서는 이벤트를 10개씩 더 열어 오래된 이벤트에도 연결할 수 있다. 이벤트 JSON의 차량 ID가 다르면 사용자가 확인한 뒤 현재 차량에 연결하고 원본 ID를 보존한다. 차량 관리 JSON은 Mock 수집 중단 후 파일의 차량 UUID를 별도로 등록·선택하고 기록을 가져올 수 있다. 기존 차량과 기록은 보존되며 연결 이벤트는 먼저 가져와야 한다. Room은 1→2→3→4→5→6→7→8→9→10→11 마이그레이션을 사용한다. 차량 관리 JSON은 삭제 기록과 이벤트를 포함하지 않으므로 새 기기의 전체 복원이 아니다. 프로세스 종료 시 후반 구간은 복구되지 않는다. 시스템 시간이 크게 변경되면 진행 중인 이벤트를 중단하고 버퍼를 초기화하며, 늦게 도착하는 신호는 완료 이후에는 추가되지 않는다. 실차 신호 지연과 앱 생명주기는 실차 연동 전에 검증해야 한다. `docs/contract.md`의 교환 규격은 v1과 v2이며 웹 가져오기는 구현되지 않았다.

## Pleos 공식 문서 확인

[시작하기](https://document.pleos.ai/en/docs/pleos-only/getting-started)는 API 34, 최소 API 28 및 Pleos 에뮬레이터를 안내한다. [SDK 개요](https://document.pleos.ai/en/api-reference/connect-sdk-pleos)는 Vehicle, FusedLocation, SpeechToText 등을 설명하고 차량 API 권한의 사전 승인과 capability 확인을 요구한다. [Vehicle 소개](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/intro)는 Brake, Fuel, DrivingMode 등 도메인을 설명한다. Odometer·Brake·TurnSignal·배터리 충전율과 Tire 저압 기준은 전용 에뮬레이터에서 일부 응답을 확인했으며 Safety 일부와 Fuel 등은 미지원 오류를 받았다. 실차 및 음성 호출은 미검증이다. 기본 앱에는 SDK 의존성과 권한을 추가하지 않았다.
