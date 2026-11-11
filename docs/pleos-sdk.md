# Pleos Connect SDK 확인 기록

## 격리 앱의 Vehicle 주행거리 이벤트 시험

Pleos 전용 AVD에서 별도 앱 ID `kr.carlog.pleos.vehicleprobe`와 Vehicle SDK 2.3.2로 이벤트 표시 전후 주행거리 단발 조회를 각각 실행하고, 두 콜백의 값을 이벤트 버퍼·인메모리 Room·이벤트 JSON까지 전달하는 선택형 시험이 통과했다. SDK가 제공한 측정 시각은 확인되지 않아 앱 콜백 수신 시각을 `RECEIVED`로 기록했고 이벤트는 시험 종료를 위해 `interrupted`로 닫았다. `VEHICLE`은 시험에서 SDK 출처를 나타내며 에뮬레이터 데이터가 실차 값이라는 뜻이 아니다.

SDK의 안전한 콜백 종료 계약이 확인되지 않았으므로 콜백이 메인 스레드에서 실행되고 해당 큐가 반환된 것이 확인된 경우에만 `release()`를 호출한다. 그 외에는 격리 시험 프로세스와 전용 에뮬레이터 종료로 정리하며, 이 방식은 실서비스의 SDK 수명 관리 해결책이 아니다. 기본 Mock 앱 APK에 Vehicle·FusedLocation·SpeechToText SDK 및 관련 권한이 포함되지 않음도 별도 계측 시험에서 확인했다. 시험은 실차, 연속 수집, 전후 60초 SDK 기록을 검증하지 않는다.

```bash
CARLOG_PLEOS_MAX_SECONDS=180 ./scripts/pleos-emulator-test.sh :app:connectedDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=kr.carlog.pleos.PleosOdometerCaptureTest' \
  -Pandroid.testInstrumentationRunnerArguments.odometerCaptureProbe=true \
  -Pcarlog.isolatedVehicleProbe=true
```

2026-11-09 한국어 공식 [릴리스 노트](https://document.pleos.ai/docs/pleos-only/release-note/sdk-release-note)와 [의존성 안내](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/project-setup)의 FusedLocation 1.1.3은 공개 Maven에서 받아졌다. 별도 `pleos-location` 모듈에서 생성자와 `isSupported()`를 컴파일했고 Pleos 전용 AVD에서 `isSupported()`가 true였다. 후속 시험에서 `initialize()`와 `release()` 호출도 반환됐다. 이 시험은 서비스 연결 완료, 위치 권한·좌표 획득이나 실차 지원을 입증하지 않는다. 위치 권한 선언이나 앱 연결은 하지 않았다. 영문 의존성 안내의 1.1.2 POM은 같은 시점 404였다.

후속 선택형 시험에서는 공식 [권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/declaring-permissions)을 별도 시험 앱에만 선언·부여하고 `getLastKnownLocation()`의 비어 있지 않은 콜백을 받았다. 좌표는 출력하거나 저장하지 않았다. 에뮬레이터의 단일 조회이며 값의 정확성·시각 의미·수명 주기 경합이나 실차 지원은 확인하지 않았다. 이후 진단에는 에뮬레이터 여부 확인을 추가했으며 기본 앱에는 위치 권한이나 SDK를 연결하지 않았다.

별도의 에뮬레이터 전용 선택형 구독 시험에서는 `registerFusedLocationCallback()`으로 받은 콜백 한 건의 위도·경도 범위와 양의 타임스탬프를 확인한 뒤 같은 리스너로 등록 해제하고 SDK를 해제했다. 값 자체는 출력·저장하지 않았다. 한 건을 받은 결과로 연속 갱신이나 해제 후 콜백의 부재를 보장하지 않는다. 문서에서 타임스탬프의 시간 기준과 측정·수신 시각 여부, 해제 시 이미 실행 중인 콜백의 완료 보장은 확인하지 못했으므로 이벤트 수집기에 연결하지 않는다.

별도 선택형 시험에서 전용 AVD 시험 앱의 위치 권한을 거부한 뒤 단발 조회의 첫 결과가 예외·오류 또는 null이었음을 확인했다. 시험은 세 결과를 구분해 기록하지 않고 첫 결과만 검사했으므로 이후 늦은 콜백의 부재나 다른 환경에서의 권한 동작을 보증하지 않는다. 실제 앱에는 사용자가 권한을 거부하면 SDK 위치 조회를 시작하지 않는 처리가 필요하다.

실험 모듈의 위치 변환은 좌표 범위를 확인하고 SDK 타임스탬프 원본과 앱 수신 시각을 별도로 보존한다. 이 변환은 저장이나 SDK 수명 관리를 수행하지 않는다. 미확인 계약은 [문의 초안](pleos-support-request.md)에 추가했으며 전송하지 않았다.

별도 앱 ID `kr.carlog.pleos.locationprobe`의 에뮬레이터 전용 선택형 시험에서 SDK의 단일 위치 콜백을 이벤트 링 버퍼와 인메모리 Room에 넣고 이벤트 JSON으로 다시 읽는 흐름이 통과했다. 위도·경도 쌍에는 SDK 시각을 측정 시각으로 가정하지 않고 콜백을 받은 기기 시각과 `RECEIVED`를 사용했다. 이벤트는 시험 종료를 위해 `interrupted`로 닫았고 좌표 값은 출력·디스크에 저장하지 않았다. 이는 에뮬레이터 데이터 한 번을 사용한 시험이며 실차, 연속 수집, 전후 60초 SDK 기록, 권한 UI와 모든 콜백 종료 경합의 검증이 아니다.

시험 앱은 `-Pcarlog.isolatedLocationProbe=true`일 때만 위치 권한과 SDK를 포함한다. 기본 Mock 앱 APK에는 이 권한·SDK가 포함되지 않음을 별도 계측 시험으로 확인했다. 테스트 APK 권한만으로는 SDK의 대상 앱 권한 검사를 통과하지 못했고, 대상 앱에 공식 SDK의 서비스 선언이 합쳐지지 않은 상태에서는 콜백이 도착하지 않았다. 격리 빌드에서만 대상 앱에 SDK를 포함해 시험했다. 실행 예:

```bash
CARLOG_PLEOS_MAX_SECONDS=180 ./scripts/pleos-emulator-test.sh :app:connectedDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=kr.carlog.pleos.PleosLocationCaptureTest' \
  -Pandroid.testInstrumentationRunnerArguments.locationCaptureProbe=true \
  -Pcarlog.isolatedLocationProbe=true
```

2026-11-09 공식 [SpeechToText 소개](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/intro)와 [의존성 안내](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/importing-sdk)를 확인했다. 공개 Maven에서 `SpeechToText:3.3.1` POM·AAR과 의존성 `ai.pleos.playground.security:universal-access:0.6.0` POM이 응답했고, 별도 `pleos-speech` 모듈의 `Mode.ON_DEVICE` 생성자 컴파일과 debug 빌드가 통과했다. POM 응답은 해당 계정의 Playground 서비스 승인이나 실제 음성 인식 가능성의 증거가 아니다. 문서상 ON_DEVICE는 네트워크·앱 등록이 필요하지 않으며, 실제 음성 인식과 리스너 수명은 아직 시험하지 않았다. SDK는 음성을 텍스트로 변환하며 명령 실행 자체는 제공하지 않는다. 앱은 이 모듈에 의존하지 않고 마이크를 사용하지 않는다. 공식 [FusedLocation 의존성 안내](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/FusedLocation/project-setup)의 `FusedLocation:1.1.2` POM은 같은 시점 HTTP 404였다.

이후 공식 [권한](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/declaring-permissions)과 [초기화·해제](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/initialization-and-release) 가이드에 따라 별도 시험 모듈에 `pleos.car.permission.STT_SERVICE`를 선언했다. Pleos 전용 AVD에서 ON_DEVICE 초기화 성공 콜백을 받고 `release()`가 반환됐다. 음성 인식 요청과 마이크 접근 없이 수행한 단일 초기화 시험이므로 실제 음성 인식, 권한 거부, 생명주기 경합이나 실차 지원을 보장하지 않는다. 기본 앱 매니페스트에는 권한을 추가하지 않았다.

이후 공식 [PCM 입력](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/sending-audio-data)과 [리스너](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/result-listeners) 안내를 참고해 같은 AVD에서 1초 분량의 무음 PCM을 `sendAudio()`와 `completeAudioSend()`로 전달했다. 초기화는 성공했지만 15초 내 종료·오류 콜백이 오지 않아 시험이 실패했다. 미지원, 무음의 특성, 호출 순서 또는 다른 원인 중 무엇 때문인지 확인되지 않았다. 마이크와 사용자 음성은 사용하지 않았다. 이 진단은 `speechPcmProbe=true`를 지정할 때만 실행되며 현재 실패가 예상된다. 기본 앱 연결 전 정상 음성 입력, 오류·취소와 리스너 종료 계약을 확인해야 한다.

콜백 종류만 기록하도록 보강한 재시험에서는 15초 동안 준비·시작·인식·종료·오류 콜백이 모두 없었다. 이는 무음 입력과 해당 에뮬레이터에서 관찰한 결과이며 SDK나 실차의 일반적인 동작을 의미하지 않는다. 인식 텍스트와 오디오를 기록하지 않았다.

실험 모듈에는 최종 인식 결과 중 `이 순간 기록` 또는 `카로그 이 순간 기록`만 판별하는 준비 코드가 있다. 음성 SDK 또는 이벤트 기록기에 연결하지 않았으며, 중간 결과나 다른 문구는 무시한다. 실제 인식률·운전자 조작·주행 제한 검증 전 음성 기록 기능으로 제공하지 않는다.

확인일: 2026-11-03. 이 문서는 공개 문서와 Maven 접근 확인 결과이며, 특정 계정·차종의 권한 승인을 의미하지 않는다.

2026-11-07 [도메인 객체 가이드](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/obtain-domain-object)와 [샘플 가이드](https://document.pleos.ai/en/docs/pleos-only/tutorial/vehicle-sdk-sample-app)를 재확인했다. 전자는 비동기 콜백 완료 전 `release()`를 금하고, 후자는 같은 리스너 객체와 영역으로 등록을 해제하라고 설명한다. 확인한 해당 문서들에서는 반환되지 않는 요청의 취소나 콜백 반환을 관찰하는 방법을 찾지 못했다. 문서에서 찾지 못했다는 사실은 API가 존재하지 않음을 증명하지 않는다. 추가 확인은 [문의 초안](pleos-support-request.md)에 남기며 앱을 Mock으로 유지한다.

2026-11-06 공개 [시작 가이드](https://document.pleos.ai/en/docs/pleos-only/getting-started)와 [도메인 객체 가이드](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/obtain-domain-object)를 다시 확인했다. 가이드는 콜백 처리 완료 후 또는 SDK를 더 사용하지 않는 생명주기 시점에 해제하라고 안내하며 콜백 완료 전 해제를 금한다. 확인한 설명에는 대기 중 요청의 취소나 콜백 반환을 관찰하는 구체적인 방법이 없어 아래 종료 계약의 미확인 사항은 그대로다.

2026-11-06 [Vehicle 소개](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/intro)는 CarInfo의 예로 제조사와 연식을 설명한다. 그러나 이 프로젝트가 받은 Vehicle 2.3.2 AAR의 공개 `CarInfo` 인터페이스를 `javap`으로 확인한 결과 `checkCRNInfoCapabilities`, `checkVINInfoCapabilities`, `getCRNInfo`, `getVINInfo`, `getCapabilityInfo`가 있었고 제조사·모델·연식 이름의 직접 getter는 보이지 않았다. 이것만으로 다른 경로가 없거나 해당 정보가 어떤 식별값에 포함되는지 결론 낼 수 없다. 차량 식별값을 조회하지 않았고, 사용자 동의나 별도 확인 없이 이를 차량 UUID로 쓰지 않는다. 대시보드의 차량 정보는 현재 사용자가 입력한다.

- [Vehicle SDK 도입 가이드](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup)는 Pleos Maven의 `ai.pleos.playground:Vehicle:2.3.2`를 안내한다. 이 환경에서 2.3.2 POM과 AAR을 내려받을 수 있었다. 같은 경로의 2.3.1 POM은 404였다. SDK 바이너리는 저장소에 추가하지 않았다.
- 공개 SDK의 Odometer 소스는 `checkCurrentDistanceDrivenCapabilities`와 `getCurrentDistanceDriven`을 제공하며 값의 단위를 km로 설명한다. Fuel의 `checkFuelPercentCapabilities`와 `getFuelPercent`는 연료량을 %로 설명한다. 두 API의 콜백에는 원래 센서 측정 시각이 포함되지 않으므로, 수신 시각을 쓰게 된다면 측정 시각과 구분해야 한다.
- [권한 가이드](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission)는 Odometer의 대표 권한을 `CAR_DRIVE`, Fuel을 `CAR_ENERGY`로 안내한다. API별 어노테이션을 다시 확인해야 하며, [SDK 개요](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/)는 앱 심사에서 사전 협의·승인된 권한 범위만 사용할 것을 요구한다.
- [프로젝트 및 SDK 접근 가이드](https://document.pleos.ai/en/docs/pleos-only/create-my-project)는 Vehicle State를 Ready to Use, FusedLocation과 SpeechToText를 Not Available Yet으로 표시한다. 이것은 공개 문서의 표시이며 실제 계정 상태나 개별 API·차량 지원을 검증한 결과가 아니다.
- 2026-11-04 사용자의 Pleos Playground 계정에 `Carlog` 프로젝트를 생성하고 SDK 상태를 확인했다. Vehicle State의 주행 정보, EV·연료, 조명, 안전 상태 등은 `Ready to Use`; FusedLocation과 SpeechToText는 `Not Available Yet`으로 표시됐다. 이 상태는 각 차량 신호나 실제 앱 심사 권한의 승인·실행을 보증하지 않는다. 현재 앱은 Mock만 사용하고 Pleos 권한을 선언하거나 SDK를 호출하지 않는다.
- [에뮬레이터 설치 문서](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)의 Linux용 [공식 저장소 메타데이터](https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml)는 2026-11-04 인증 없이 HTTP 200으로 조회됐다. 사용자 승인 후 Pleos Connect v2.1.0 API 34 x86_64 이미지(압축 4,087,751,964 bytes)를 프로젝트 전용 SDK에 설치하고 메타데이터의 SHA-1과 일치함을 확인했다. 이미지 내부 `source.properties`가 가리키는 Android Automotive 경로에 설치했다. 프로젝트 전용 AVD `carlog_pleos_2_1`은 RAM 4GB·Cold Boot로 구성했으며 헤드리스 부팅과 ADB 연결을 확인했다.

2026-11-04 전용 AVD의 Vehicle Service 버전은 2.3.22였다. SDK 2.3.2 실험 모듈의 계측 테스트에서 주행거리 `0.0 km`, 브레이크 페달 `0.0`(눌리지 않음)을 콜백으로 받았고, 연료 비율 읽기는 `ERROR_NOT_SUPPORTED_PROPERTY`를 반환했다. 이 테스트는 각 요청의 성공 또는 오류 콜백 도착을 검사하므로 통과 자체가 모든 기능 지원을 뜻하지 않는다. 값은 에뮬레이터의 시험 데이터이며 앱에는 연결하지 않았다. SDK 콜백 복귀를 확인할 방법이 없어 계측 테스트는 `release`를 호출하지 않고 단기 테스트 프로세스 종료로 정리한다. 실제 앱 수명 관리의 대체가 아니다. [공식 오류 코드 문서](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-error-code)에 따라 해당 오류를 미지원 속성으로 기록하되, 원인이나 실제 차량 지원 여부는 추정하지 않는다.

[공식 샘플의 VehicleClient](https://github.com/Pleos-Playground/pleos-sample-vehicle-app/blob/959981d960ec71144accb27af5ae5d341e72a644/app/src/main/java/ai/pleos/playground/sample/vehicle/vehicle/VehicleClient.kt)는 사용 중인 리스너를 해제한 뒤 `release()`를 호출하고, [MainViewModel](https://github.com/Pleos-Playground/pleos-sample-vehicle-app/blob/959981d960ec71144accb27af5ae5d341e72a644/app/src/main/java/ai/pleos/playground/sample/vehicle/MainViewModel.kt)은 `onCleared()`에서 이를 호출한다. 확인한 코드에는 일회성 요청의 모든 비동기 콜백 반환을 기다리는 명시적 장치가 보이지 않는다. 공식 도메인 가이드는 콜백이 완료되기 전에 `release()`하지 말라고 설명하므로, 이 샘플만으로 요청 중 종료의 안전성을 확정하지 않는다.

2026-11-04 별도 종료 시험에서는 비동기 조회나 리스너 등록이 없는 `Vehicle` 인스턴스 두 개를 각각 초기화한 직후 `release()`했으며 두 호출이 반환됐다. 이후 새 인스턴스에서 주행거리 getter의 `0.0 km` 콜백을 받았다. 이는 이 전용 AVD의 해당 호출 순서에서만 관찰한 결과다. 새 인스턴스의 콜백 반환과 해제는 확인하지 않았으며, 요청·리스너가 있는 경우나 실차의 안전한 수명 관리를 입증하지 않는다.

SDK 조회 콜백 이후 스레드 스택을 관찰해 앱 콜백·SDK 코드 밖으로 돌아간 시점에 `release()`하고 다시 읽는 진단 시험도 시도했다. 전용 AVD에서 주행거리 콜백은 도착했지만 실행 스레드는 살아 있고 `Thread.getStackTrace()`는 빈 배열을 반환했다. 호출 스택의 부재로 콜백 종료를 판정할 수 없어 두 번 모두 release 전에 시험을 건너뛰었다. 이 관찰은 SDK가 콜백을 반환하지 않았다는 뜻이 아니다. 이 방식은 별도 `manualCallbackStackProbe=true` 옵션에서만 실행하며 실제 앱의 수명 관리에 사용하지 않는다.

2026-11-04 추가 시험에서 주행거리 getter는 `0.0 km` 콜백을 전달했으나 콜백은 Android 메인 Looper가 아닌 스레드에서 실행됐다. 메인 Looper에 작업을 게시하는 방법으로는 이 콜백의 반환을 확인할 수 없어 `odometerCallbackCanDrainBeforeReleaseAndReinitialize` 시험은 `release()` 호출 전에 스스로 건너뛰었다. 테스트 프로세스 종료로 정리하며, 이 결과는 SDK가 항상 같은 스레드에서 콜백한다거나 release가 실패한다는 뜻이 아니다. 콜백을 보낸 executor의 종료 순서 또는 SDK가 제공하는 명시적인 종료 계약을 확인하기 전에는 실험 모듈을 앱에 연결하지 않는다.

동일 전용 AVD에서 요청을 확대해 방향지시등 `OFF`를 수신했다. 주행 모드·엔진오일 수준·브레이크액 수준 요청은 연료 비율과 마찬가지로 `ERROR_NOT_SUPPORTED_PROPERTY`를 반환했다. [공식 가상 콘솔](https://document.pleos.ai/en/docs/pleos-only/app-test-guide/virtual-console-guide)에서 시험 주행거리를 `12345 km`, 방향지시등을 `Blink left`로 설정한 후 계측 테스트를 다시 실행하자 SDK 콜백에서 각각 `12345.0 km`, `LEFT`를 수신했다. 이는 변경 후의 단발성 읽기 결과이며 지속 구독이나 실차 신호 지원을 검증한 것은 아니다. SDK 콜백의 시각은 수신 시각이다.

별도 계측 시험에서 SDK 2.3.2의 `registerDistanceDriven` 호출 후 주행거리 알림 `0.0`을 받고 같은 리스너를 `unregisterDistanceDriven`에 전달해 호출이 반환됐다. 추가 수동 시험에서는 가상 콘솔을 `0`에서 `12345 km`로 바꾸자 리스너가 `12345.0`을 수신했다. 해제 호출 반환 후 `54321 km`로 바꾸고 40초간 추가 콜백이 없었으며, 이후 단발성 getter는 `54321.0`을 반환했다. 해당 에뮬레이터에서의 제한된 관찰이며 모든 경합 조건이나 실차에서 해제 뒤 콜백이 없다는 보장은 아니다. 콜백 완전 종료와 `release()`는 검증하지 않았다. [공식 샘플 가이드](https://document.pleos.ai/en/docs/pleos-only/tutorial/vehicle-sdk-sample-app)에 따라 등록·해제 호출의 반환을 서비스 측 완료 확인으로 해석하지 않는다. 초기 시험은 에뮬레이터 운전자 프로필이 잠긴 동안 테스트 프로세스 시작이 거부돼 대기했으며, 프로필 잠금 해제 상태를 확인한 뒤 재실행했다.

수동 리스너 변경 시험은 일반 계측 실행에서는 건너뛴다. 전용 AVD의 가상 콘솔에서 주행거리를 0으로 둔 뒤 아래 명령을 실행하고, `CarlogPleosProbe` 로그의 `READY_FOR_12345`가 나오면 12345로 설정한다. `UNREGISTERED_SET_54321`이 나오면 40초 안에 54321로 설정한다. 시험용 로컬 에뮬레이터에만 적용한다.

```bash
ANDROID_SERIAL=emulator-5556 ./scripts/gradle.sh :pleos-vehicle:connectedDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=kr.carlog.pleos.vehicle.PleosServiceProbeTest#manualOdometerChangeAndUnregisterProbe' \
  -Pandroid.testInstrumentationRunnerArguments.manualVehicleProbe=true
```

## 분리된 실험 모듈

`pleos-vehicle`은 공개 Maven의 Vehicle SDK 2.3.2에 직접 의존하는 별도 Android 라이브러리다. Odometer의 누적 거리, Fuel의 연료 비율, Brake 페달 눌림 여부, DrivingMode, TurnSignal 및 Safety 엔진오일·브레이크액 수준 상태에 대해 capability의 `READ` 또는 `READ_WRITE` 확인 후 단발성 get을 호출하며, 값이 없는 응답과 오류를 호출자에게 전달한다. 브레이크 값은 `brake_pedal_pressed`, 단위 `boolean`으로 표현하며 1은 눌림, 0은 눌리지 않음을 뜻한다. 이는 페달 압력이나 제동 강도가 아니다. enum 상태는 SDK enum의 이름과 `2.3.2` 버전을 별도로 유지한다. 현재 이벤트 v1·v2와 Room 신호는 숫자만 지원하므로 상태 문자열은 여기에 저장하거나 교환하지 않는다. SDK 콜백에서 얻은 시각은 원래 측정 시각이 아니라 `receivedAtMillis` 수신 시각으로 표현한다. 실험 모듈 manifest에는 문서와 SDK 어노테이션에서 확인한 `CAR_DRIVE`, `CAR_ENERGY`, `CAR_LIGHTS`, `CAR_SAFETY`를 선언한다. 앱은 이 모듈에 의존하지 않으며 앱 manifest에 이 권한들은 포함되지 않는다.

전기차 배터리 충전율은 연료와 별도인 `ev_battery_soc` 신호로 `checkEvBatteryStateOfChargeCapabilities`와 `getEvBatteryStateOfCharge`를 사용한다. 공개 가상 콘솔의 SOC 단위는 0~100%이며 실험 모듈은 이 범위 밖의 값을 오류로 전달한다. 전용 AVD에서 기본 `100.0%`를 읽었고 가상 콘솔을 65%로 변경한 뒤 `65.0%`를 읽었다. 이 값은 시험용이며 실차·구독·앱 연결은 검증하지 않았다. [공식 FAQ](https://document.pleos.ai/en/docs/pleos-only/vehicle-app-developer-guide/vehicle-app-faq)는 차량 배터리 SOC 확인에 이 API를 안내한다.

### 추가 신호 검토

다음은 공개 SDK 2.3.2 소스의 API 정의다. 선언이나 컴파일만으로 접근 권한 승인과 차종 지원이 확인된 것은 아니다. 표의 읽기 권한은 해당 메서드의 어노테이션이며, SDK 통합 전 최신 문서를 다시 확인해야 한다.

2026-11-10 재확인한 [공식 Vehicle 샘플](https://github.com/Pleos-Playground/pleos-sample-vehicle-app)은 샘플의 공개 `CAR_*` 권한을 `normal`로 설명한다. 따라서 해당 샘플 환경에서는 일반 Android 위험 권한 대화상자를 전제로 하지 않는다. 이 설명은 비공개 권한이나 모든 차량의 기능 지원을 의미하지 않으며, 개별 capability와 실제 서비스 동작은 별도 확인해야 한다. [공식 가이드](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/obtain-domain-object)는 비동기 콜백이 완료되기 전 `release()`하지 않도록 안내하지만, 콜백 미도착 시 취소와 콜백 반환을 확인하는 방법은 명시하지 않는다. 이 부분은 기존 문의 대상으로 남는다.

| 도메인 | 검토한 읽기 API와 의미 | 권한 및 상태 |
| --- | --- | --- |
| Brake | `getCurrentBrakePedalStatus`: 페달 눌림 Boolean | `CAR_DRIVE`; 실험 모듈 컴파일, 실차 미검증 |
| Safety | `getEngineOilLevelState`, `getBrakeFluidLevelState`: 각각 오일·브레이크액 상태 enum | `CAR_SAFETY`; 두 상태 모두 실험 모듈 컴파일, 앱 연동 미구현 |
| DrivingMode | `getCurrentDrivingModeState`: 주행 모드 enum | `CAR_DRIVE`; 실험 모듈 컴파일, 앱 연동 미구현 |
| Tire | `getLowTirePressure`: 바퀴별 저압 **임계값** kPa | `CAR_SAFETY`; 현재 공기압이 아니므로 센서 측정값으로 표시하면 안 됨 |
| Tire | `getTirePressure`: 바퀴별 현재 압력 kPa | SDK 소스에서 `PRIVATE_CAR_SAFETY` 및 `signature\|privileged`로 표기; 사용 가능하다고 가정하지 않음 |
| TurnSignal | `getCurrentTurnSignalState`: 방향지시등 상태 enum | `CAR_LIGHTS`; 실험 모듈 컴파일, 앱 연동 미구현 |

각 getter에 대응하는 capability API가 있으며, 타이어의 경우 지원 바퀴 영역도 반환한다. enum 값을 이벤트 숫자 신호로 임의 변환하기 전에 의미와 버전 관리가 가능한 계약을 정해야 한다. 위치·음성 SDK의 공개 문서 상태는 위 접근 가이드를 따른다.

전용 AVD의 Vehicle Service 2.3.22와 SDK 2.3.2에서 `checkLowTirePressureCapabilities`는 `READ`와 네 영역을 반환했다. 영역 값은 각각 `[UNKNOWN, LEFT_FRONT]`, `[UNKNOWN, RIGHT_FRONT]`, `[UNKNOWN, LEFT_REAR]`, `[UNKNOWN, RIGHT_REAR]`였으며, 반환된 각 영역을 그대로 `getLowTirePressure`에 전달했을 때 모두 `137.0 kPa`가 반환됐다. `UNKNOWN`이 함께 있는 이유는 확인되지 않았으므로 제거하거나 의미를 추정하지 않는다. 이 값은 저압 경고 기준값이고 **현재 타이어 공기압이 아니다**. 계측 시험의 통과는 응답 또는 오류 콜백 도착을 뜻하며 실차 지원을 보장하지 않는다. [SDK 릴리스 노트](https://document.pleos.ai/docs/pleos-only/release-note/sdk-261015)는 이전 `checkWheelAreaCapabilities` 대신 capability 설정과 영역 목록을 함께 반환하는 API로의 전환을 안내한다.

실험 모듈의 `readLowTirePressureThreshold`는 호출자가 전달한 SDK `WheelArea`가 capability 결과에 포함되고 읽기 가능할 때만 저압 경고 기준값을 kPa로 조회한다. 결과는 수신 시각이며 현재 공기압이 아니다. `listLowTirePressureThresholdAreas`는 SDK capability에서 읽기 가능한 영역 목록을 원래 `WheelArea` 값 그대로 반환하며 이를 조회 메서드에 전달할 수 있다. 전용 AVD에서 같은 실험 리더로 영역 검색과 기준값 수신을 확인했다. 앱 연결과 SDK의 콜백 종료·해제 안전성은 여전히 검증되지 않았다.

실험 모듈은 숫자 응답이 유한한지 검사하고, 주행거리 음수, 연료 비율 0~100% 밖의 값, 페달 Boolean 변환 결과가 0 또는 1이 아닌 값을 오류로 전달한다. 값이 없는 응답과 구분하며 임의로 0이나 경계값으로 바꾸지 않는다. 이는 앱 측 검증 정책이며 실차에서 SDK가 보내는 오류 코드나 특수값의 의미를 확인한 결과는 아니다.

오류 처리에는 배포본과 문서 사이의 확인이 더 필요하다. [공식 오류 안내](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-error-code)는 `VehicleException.errorCode`와 `ErrorCode`를 안내하지만, 이 프로젝트에서 받은 2.3.2 AAR의 Gradle API JAR에는 그 이름의 클래스가 포함되어 있지 않았다. 전용 AVD에서 관찰한 미지원 오류의 메시지는 `ERROR_NOT_SUPPORTED_PROPERTY`였으나 메시지 파싱이나 난독화된 내부 클래스에 의존하지 않는다. 실험 모듈은 원본 `Exception`을 호출자에게 전달하며 권한 거부·서비스 오류 등을 현재 자동 분류하지 않는다. SDK 제공 측의 공개 오류 API 확인이 필요하다.

프로젝트 전용 도구로 컴파일과 가짜 SDK 응답을 사용하는 단위 테스트를 실행할 수 있다.

```bash
./scripts/gradle.sh :pleos-vehicle:testDebugUnitTest :pleos-vehicle:assembleDebug
```

이 명령은 API 시그니처와 가짜 포트에서 capability·값 없음·오류·중복 및 지연 콜백·대기 중 종료 거부를 확인한다. 전용 AVD의 별도 계측 테스트에서는 주행거리·연료·배터리 SOC·브레이크·주행 모드·방향지시등·엔진오일·브레이크액 요청의 성공 또는 오류 콜백 도착을 확인했다. 서비스 연결 준비 시점, 권한 거부·서비스 단절, 콜백 중단과 정리, 실차 값·차종 지원은 검증하지 않았다. `initialize()`는 비동기 서비스 연결을 시작하므로 호출 직후 서비스가 준비됐다고 가정해서는 안 된다. `release()`는 요청 결과를 기다리는 동안뿐 아니라 동기 콜백이 완료된 후에도 해당 capability/get 호출이 반환되기 전이면 거부된다. 이 보호는 비동기 SDK 콜백 자체의 반환을 보장하지 않으므로 호출자는 그 반환도 별도로 확인해야 한다. 콜백이 오지 않으면 대기 상태가 유지된다. 이 경우의 안전한 수명 관리와 오류 복구를 검증하기 전에는 실험 모듈을 앱 또는 이벤트 기록기에 연결하지 않는다. 이벤트 v2는 시각 종류를 보존할 수 있지만 앱은 SDK에 아직 연결되지 않았다. `receivedAtMillis`를 차량 센서의 측정 시각으로 취급하지 않는다.

후속 연동 시 공식 SDK와 서비스 버전의 호환성을 확인하고 도메인별 capability, 거부·미지원·서비스 단절, callback 수신 시각, 종료 시 `release` 및 각 권한을 검증한다. 고주파 신호나 상시 백그라운드 수집은 가정하지 않는다.

SDK 제공 측에 전달할 구체적인 종료·연결·타임스탬프 확인 질문은 [문의 초안](pleos-support-request.md)에 정리했다. 문의는 아직 전송되지 않았다.
