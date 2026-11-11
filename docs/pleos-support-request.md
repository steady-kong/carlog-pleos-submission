# Pleos SDK 연동 확인 요청 초안

이 문서는 문의 초안이며 Pleos에 전송되지 않았다. 계정 정보, Client ID, 토큰, 차량 식별정보와 사용자 기록은 포함하지 않는다.

## 환경과 목적

- Vehicle SDK: `ai.pleos.playground:Vehicle:2.3.2`
- 시험 환경: Pleos Connect v2.1.0 API 34 x86_64 전용 에뮬레이터, Vehicle Service 2.3.22
- Android 앱에서 주행거리 등 읽기 전용 신호를 수집하고 화면 종료 또는 수집 중단 시 리스너와 SDK를 정리하려 한다.
- 공개 가이드는 비동기 capability/get/set 콜백이 완료되기 전에 `Vehicle.release()`를 호출하지 않도록 안내한다. 샘플은 리스너를 해제한 뒤 `release()`한다.

## 확인 요청

1. `onComplete` 또는 `onFailed`에서 앱이 결과를 처리하고 다른 스레드에 완료 신호를 보낸 직후, SDK가 콜백 함수에서 아직 반환하지 않았을 수 있다. 이때 다른 스레드에서 `release()`해도 되는가? 불가능하다면 SDK가 콜백 반환 또는 안전한 해제 시점을 알리는 공개 API나 권장 패턴이 있는가?
2. capability/get 요청의 콜백이 서비스 단절 등으로 도착하지 않을 때, 요청을 취소하거나 안전하게 정리하는 공식 방법은 무엇인가? 앱 수명 종료 시 대기 중 요청을 어떻게 처리해야 하는가?
3. `unregisterDistanceDriven` 반환 후 실행 중이거나 이미 대기 중인 리스너 콜백이 남을 수 있는가? 리스너 제거와 `release()` 사이에 서비스 측 완료 확인이 필요한가? 필요하다면 그 방법은 무엇인가?
4. `initialize()` 직후 Vehicle Service 연결 완료와 연결 해제를 관찰하는 공개 API가 있는가? 권한 거부, 미지원 속성, 서비스 단절을 SDK 2.3.2에서 안정적으로 구분할 수 있는 공개 예외 타입과 패키지명도 확인 부탁한다.
5. 공식 `Odometer` getter 또는 리스너 값에 센서 측정 시각이 포함되는가? 없다면 앱이 기록한 콜백 수신 시각은 측정 시각이 아님을 전제로 표시하려 한다.
6. Vehicle 2.3.2의 공개 소개는 CarInfo의 예로 제조사·연식을 언급하지만 AAR의 `CarInfo`에는 해당 이름의 직접 getter가 보이지 않았다. 차량 식별번호를 조회하지 않고 제조사·모델·연식만 읽을 수 있는 공식 API와 필요한 권한이 있는가? 없다면 사용자 입력을 유지하려 한다.

## FusedLocation 1.1.3 추가 확인 요청

1. `LocationData.timestampMillis`의 시간 기준과 단위, 측정 시각인지 수신 시각인지, 기기 시각 변경의 영향을 알려 달라. 값이 없거나 유효하지 않을 때의 표현도 확인하고 싶다.
2. `unregisterFusedLocationCallback()` 반환 뒤 이미 실행 중이거나 대기 중인 콜백이 남을 수 있는가? `release()`를 안전하게 호출할 수 있는 시점과 확인 방법은 무엇인가?
3. `getLastKnownLocation()`의 콜백이 오지 않는 경우 요청을 취소하고 안전하게 해제하는 공식 방법은 무엇인가? 서비스 연결 완료·단절을 확인하는 공개 API도 있는가?

전용 에뮬레이터에서 지원 여부와 단일 조회·구독 콜백을 확인했지만, 위치 값 자체는 기록하지 않았다. 실차와 모든 경합에 대한 보장은 확인하지 않았다. 관련 문서: [위치 데이터 접근](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/location-data-access), [초기화와 해제](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/FusedLocation/initialization-and-release).

## SpeechToText 3.3.1 추가 확인 요청

전용 Pleos Connect v2.1.0 API 34 에뮬레이터에서 `STT_SERVICE` 권한을 선언하고 `ON_DEVICE` 초기화 성공 콜백을 받았다. `ResultListener` 등록 후 16 kHz·16-bit·mono의 1초 무음 PCM 32,000바이트를 `sendAudio()`로 전달하고 `completeAudioSend()`를 호출했으나, 15초 동안 준비·시작·결과·종료·오류 콜백이 없었다. 사용자 음성·마이크·인증정보는 사용하지 않았다.

1. 외부 PCM을 보내기 전에 `request()` 또는 별도의 서비스 준비 절차가 필요한가? 음성 구간이 없는 무음에서도 종료 또는 오류 콜백을 기대할 수 있는가?
2. 위 SDK 버전과 에뮬레이터 이미지의 ON_DEVICE 엔진 조합이 호환되는가? 필요한 음성 모델, 언어 설정, 권한과 최소 오디오 길이는 무엇인가?
3. 인식 콜백이 반환되지 않을 때 요청 취소와 리스너 제거·`release()`를 안전하게 수행하는 방법은 무엇인가? 해제 후 대기 중인 콜백이 실행될 수 있는가?

관련 문서: [PCM 입력](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/sending-audio-data), [리스너](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/result-listeners), [초기화와 해제](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/SpeechToText/initialization-and-release).

## 관찰한 사실과 한계

- 전용 AVD에서 `getCurrentDistanceDriven`의 콜백을 수신했고, 한 시험에서 콜백은 Android 메인 Looper 밖의 스레드에서 실행됐다. 모든 환경에서 같은 스레드를 쓴다는 의미는 아니다.
- 주행거리 리스너를 등록하고 동일 객체로 해제한 뒤 제한된 시간 동안 추가 알림이 없는 것을 관찰했다. 이 관찰만으로 모든 경합에서 콜백 종료 또는 서비스 측 해제 완료를 보장할 수는 없다.
- SDK 2.3.2에서 capability/get이 없는 인스턴스의 초기화와 `release()`는 전용 AVD에서 반환됐다. 요청 또는 리스너가 진행 중인 경우의 안전한 종료를 증명하지는 않는다.
- 현재 앱은 시뮬레이션 데이터만 사용한다. 실차 연결이나 지속 기록은 아직 사용하지 않는다.

관련 문서: [Vehicle 도메인 객체 가이드](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/obtain-domain-object), [Vehicle 샘플 가이드](https://document.pleos.ai/en/docs/pleos-only/tutorial/vehicle-sdk-sample-app), [오류 코드](https://document.pleos.ai/en/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-error-code).
