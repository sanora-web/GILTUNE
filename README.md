# 길튠 GILTUNE — 길마다 다른 음악

제작: **SANORA WEB** · 현대자동차그룹 Pleos 차량용 앱 개발 공모전 출품작

**한 줄 소개**: ▶를 한 번 누르면 지나가는 도로(고속도로·도시고속도로·도심·골목·다리·터널·강변·해안)와 날씨·시간·속도에 맞춰 곡이 자동으로 바뀝니다. 내비 경로에서 터널·다리를 미리 알아내 다음 곡을 먼저 준비합니다.

## APK
- `apk/SANORAWEB_GILTUNE_release.apk`: 서명된 release APK(`pleos` flavor). Pleos 에뮬레이터와 일반 Android 폰(API 28 이상)에 같은 APK를 설치합니다.
- 파일명은 공모전 규칙 `팀명_앱이름_release.apk`를 따릅니다(https://appdevchallenge.auto-hmg.io/guide/submission).

## 실행 방법 (Pleos 에뮬레이터 + Virtual Console)
1. APK 설치 → 실행 → 위치·알림 권한 허용 → **▶**.
2. Virtual Console
   - **기어 P ↔ D**: 상단 상태줄에 `P 정차` / `주행 기어` 표시. 주행 기어면 ♡·≡·지도 확대가 잠기고 "주행 중" 안내가 나옵니다.
   - **와이퍼 ON**: `와이퍼 ON` 표시. 날씨 정보가 없으면 비 장면으로 전환합니다.
   - **위치 이동**: FusedLocation 좌표로 도로 종류(OSM)를 판정하고, 연속 좌표로 속도를 계산해 표시합니다.
3. **앞 구간 곡 미리 준비(핵심)**: 내비 경로 안내를 시작하면 NaviHelper 턴바이턴 정보에서 2km 안의 터널·다리·IC를 찾습니다. "다음" 줄에 `내비 경로: 800m 앞 · 미리 준비됨`이 표시되고, 그 장면의 Spotify 곡 묶음(취향 검색 결과 전체)을 미리 검색해 두고 합성곡도 버퍼링합니다. 실제로 그 구간에 들어서면 검색 대기 없이 바로 전환됩니다.
4. 재생 중 미디어 버튼
   - Spotify 모드에서 다음은 재생목록의 다음 곡, 이전은 들은 곡으로 갑니다. 30초 안에 넘기면 '넘김'으로 학습합니다.
   - 음성·검색 재생(onPlayFromSearch)은 그 검색어로 Spotify에서 찾아 재생합니다.
   - 합성 음원 모드에서는 장면을 바꿉니다.
   - 알림과 미디어 정보에는 파일명이 아니라 곡 제목이 표시됩니다.
5. ≡ 메뉴(정차 중에만): 양화대교 시연 주행(데모) / 비 시연 / 주행 로그·CSV 공유 / 취향(장르·분위기) / 재생목록 / 노래 검색 / Spotify 연결 / Spotify 검색 로그인 / 앱 정보.
6. 첫 실행: 위치·개인정보 안내 → 권한 → 장르(팝·발라드·힙합·K-pop·R&B·록·인디·재즈·트로트 등)·분위기 복수 선택.
7. 재생목록: 지금 재생 / 다음 곡(장면별 묶음, 눌러서 삭제) / 들은 곡(넘김·끝까지·♥). 검색: 곡·아티스트 검색(최대 10건) → 바로 재생 또는 다음 곡에 추가. 모든 줄은 76dp 이상입니다.

## 음악 소스
1. **Spotify(메인)**
   - `QueryPlanner`가 장면 키워드(무작위) × 분위기(사용자 선택 + 시간대·속도·날씨 가중치) × 선택 장르/아티스트로 검색어를 매번 다르게 만들고, offset도 무작위로 줍니다. Web API Search(`GET /v1/search`, type=track, limit=10)로 찾은 뒤 학습 가중치로 정렬하고 최근 곡·아티스트를 빼며 아티스트당 1곡으로 제한합니다. 결과가 없으면 장면 기본 키워드로 다시 검색합니다.
   - 시간대는 장면을 바꾸지 않고 분위기 가중치에만 쓰입니다(밤 장면 없음).
   - 학습: 30초 안에 넘김 −, 끝까지 들음 +, ♡ ++(Spotify 보관함 저장, App Remote `UserApi.addToLibrary`). 장면별 조합도 따로 학습합니다.
   - 재생목록은 앱이 가지고 있고, 다음 곡은 App Remote `PlayerApi.queue`로 Spotify에 넘겨 끊김 없이 이어집니다.
   - 곡 URI를 직접 적어 두지 않습니다.
   - 재생은 App Remote가 합니다.
   - 토큰은 공식 Spotify Android auth-lib(`com.spotify.android:auth:2.1.2`, Authorization Code + PKCE)로 받습니다.
2. **합성 음원(폴백)**
   - Spotify 앱이 없거나, 로그인·검색·재생이 실패하면 사용합니다.
   - `highway / city_express / alley / tunnel / coastal.mp3` 5곡이며, `tools/music/generate_tracks.py`로 수학 오실레이터만 써서 합성했습니다. 샘플과 외부 음원은 쓰지 않습니다.
   - 해시: `tools/music/TRACKS_SHA256.txt`.
   - 장면별 파일은 `scene_rules.json`의 `local` 값으로 정합니다.

## 장면 규칙
- `app/src/main/assets/scene_rules.json`
  - 웹 버전과 같은 파일이며, 스키마는 `docs/scene_rules.schema_ko.md`입니다.
  - OSM 매핑, 우선순위, 임계값(주행 판정 2.5m/s, 스로틀, 안정화)을 모두 이 파일에서 읽습니다.
- `Scenes.kt`는 웹 엔진(`web/js/engine.js`)과 1:1로 대응합니다.
  - `SceneEngineEquivalenceTest`가 JS로 만든 픽스처(`app/src/test/resources/engine_fixtures.json`, `tools/gen_engine_fixtures.js`)와 결과를 비교합니다.
  - 비교 대상: 장면 400건, OSM 분류 200건, 안정화 30개 시퀀스, 취향 검색 계획 120건(난수열 재생).
  - `WebEngineTest`는 웹 `engine.test.js` 21개 케이스를 그대로 옮긴 것입니다.
- 도로 조회(100m·8초 간격): Overpass 미러 4곳을 순서대로(8초 타임아웃, 성공 미러 기억). 모두 실패하면 Overpass만 60초 쉬고 그동안 Nominatim reverse(zoom 17, 1초 1회)로 판정합니다.
- 지도 결과가 없거나 45초보다 오래되면 속도 구간으로 추정합니다: 30km/h 이하 골목, 70km/h 이하 도심, 그 이상 도시고속도로(자체 규칙). 터널·다리·강변은 속도로 알 수 없습니다.
- 화면 아래 작은 줄에 도로 판단·출처(OSM/주소/속도/데모/없음)·다음 전환 조건(후보 확인 횟수·남은 초·최소 유지)을 표시합니다.
- 진행 바: 정차 중 드래그로 이동, 주행 중에는 76dp −10초/+10초 버튼. Spotify는 App Remote `PlayerApi.seekTo`/`seekToRelativePosition`, 합성곡은 MediaPlayer `seekTo`.
- Spotify 권한 범위: `app-remote-control`, `user-library-modify`(♡ 보관함 저장. 공식 scopes 문서의 "Save Tracks for User"). 예전 로그인이면 메뉴에 '다시 로그인'이 뜹니다.
- 취향 화면에서 아티스트를 검색(정차 중, Spotify 로그인 후)해 추가·삭제할 수 있습니다.
- 속도: GPS 속도가 없거나 음수면 좌표 차분으로 계산(정확도 50m 초과·비현실 속도 제외).
- 깜빡임 방지
  - 같은 판정이 2회 연속이고 25초 이상 지속되며, 현재 곡을 40초 이상 유지한 경우에만 전환합니다.
  - 터널·다리는 3초 만에 전환합니다.
  - 전환할 때는 2초 동안 크로스페이드합니다.

## 테스트 환경
- 빌드·단위 테스트: Linux x86_64, JDK 17(Temurin 17.0.21), Android SDK 34(build-tools 34.0.0), Gradle 8.9 wrapper. 4개 변형 빌드와 단위 테스트 59개 × 2 flavor(debug) 통과
- 심사 환경 가정: Pleos Playground 에뮬레이터 + Virtual Console(기어·와이퍼·위치 변경)
- 미확인: Pleos 에뮬레이터 실제 실행, 실차, Spotify 실계정 재생(빌드 박스에서 에뮬레이터 부팅 실패, report 참고)

## 위치·개인정보
- 첫 실행 때 안내 화면을 보여 준 뒤 권한을 요청합니다.
- 좌표는 도로 종류(OSM Overpass), 지역명(Nominatim), 날씨(Open-Meteo) 조회에만 쓰입니다.
- 주행 로그는 기기 안에만 저장되며, 사용자가 공유할 때만 밖으로 나갑니다.
- Spotify로는 검색어(장면·장르)만 보내고, 위치는 보내지 않습니다.
- 위치를 거부하거나 오프라인이어도 재생됩니다. 이때 화면에 `위치 없음 · 시간·날씨 기준 선곡`이 표시됩니다.

## 라이선스
- `LICENSE`: All rights reserved, © 2026 SANORA WEB
- 서드파티 고지: `THIRD_PARTY_NOTICES.md`(OSM ODbL, Open-Meteo CC BY 4.0, Spotify SDK, Pleos SDK, AndroidX·Gson 등)

## 빌드·테스트
- 필요 환경: JDK 17, Android SDK 34, Gradle 8.9(wrapper), AGP 8.6.1, Kotlin 2.0.21
- Flavor
  - `pleos`: FusedLocation 1.1.3 / NaviHelper 3.0.5 / Vehicle 2.3.2. 저장소는 `https://nexus-playground.pleos.ai/repository/maven-releases/`입니다.
  - `demo`: SDK를 뺀 빌드입니다.
- `./gradlew assemblePleosRelease assemblePleosDebug assembleDemoRelease assembleDemoDebug testPleosDebugUnitTest testDemoDebugUnitTest`
- 서명 키(`../../keys/keystore.properties`)와 Spotify Client ID(`../../spotify/client_id.env`, `SPOTIFY_CLIENT_ID=...`)는 저장소 밖에 둡니다.
  - Client ID가 없으면 합성 음원만 재생합니다.
- Spotify 대시보드: Redirect URI `giltune://spotify-callback`, 패키지 `com.sanora.giltune`, 서명 SHA-1 등록.

## 권한 (문서에 있는 것만)
| 권한 | 근거 |
|---|---|
| `pleos.car.permission.FUSED_LOCATION` | FusedLocation 권한 문서 |
| `pleos.car.permission.NAVI_ROUTE` | NaviHelper 권한 문서 |
| `pleos.car.permission.CAR_DRIVE` | Vehicle 권한 문서 + `Gear` API `@RequiresPermission` |
| `pleos.car.permission.CAR_WINDOWS` | Vehicle 권한 문서(Window, Wiper) |
| `ACCESS_FINE/COARSE_LOCATION`, `INTERNET`, `FOREGROUND_SERVICE(_MEDIA_PLAYBACK/_LOCATION)`, `POST_NOTIFICATIONS` | Android 표준 |

## 구현 / 데모 / 미검증
| 항목 | 상태 |
|---|---|
| GPS·FusedLocation → OSM 도로 → 장면 → 곡 자동 전환 | 구현, 단위·동일성 테스트 통과. 실차 미검증 |
| Pleos Vehicle(기어·와이퍼), NaviHelper(주소·제한속도·턴바이턴) | 구현(aar·문서 확인). Pleos 에뮬레이터 실행 미검증 |
| 내비 턴바이턴 기반 앞 구간 예측·사전 준비 | 구현. 다리는 안내문 속 "대교/철교"로만 판단 |
| Spotify 검색·재생 | 구현. 실기기·실계정 미검증 |
| 주행 중 입력 제한(기어≠P, 기어 미상이면 2.5m/s 이상) | 자체 기준. Pleos 공식 주행 규제 API는 문서에 없음 |
| 양화대교 시연 주행, 비 시연 | 데모(화면에 표시) |

## 문서 근거
- Pleos SDK: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/ (FusedLocation / NaviHelper / Vehicle)
- Spotify auth-lib: https://developer.spotify.com/documentation/android/tutorials/authorization
- PKCE: https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow
- Search: https://developer.spotify.com/documentation/web-api/reference/search
- App Remote: https://developer.spotify.com/documentation/android/tutorials/getting-started
- 그 밖의 링크: `docs/공식_참고링크.md`

© OpenStreetMap contributors · Open-Meteo · © 2026 SANORA WEB

## v5 (웹 최신 반영)
- 공용 `scene_rules.json` v5 · `geo_kr.json`(해안·강, 격자 인덱스, 다리 이름 규칙, © OpenStreetMap contributors, ODbL) 내장
- 60km/h 30초 고속 후보, 정체·90분 장거리, 날씨 6종·기온·계절·일출몰·주말·공휴일·출퇴근·심야 선곡 요소(독점 금지)와 '선곡 이유' 줄
- 메인 장르 칩(정차 중만, 다음 곡부터, 주행 중 잠김)
- 연타 반복 대응: Spotify 반복 끄기(PlayerApi.setRepeat), 큐 밖 곡 교정, 넘김 직렬화, 자체 음원 5곡 셔플 + 'Spotify 미연결 — 자체 음원 5곡' 표시·다시 연결
- 단위 테스트 74개(웹 engine·v5·skip 이식, 공용 파일 비교 포함)
