# 서드파티 고지 — 길튠 GILTUNE (© 2026 SANORA WEB)

| 구성요소 | 용도 | 라이선스 / 조건 |
|---|---|---|
| OpenStreetMap 데이터·타일 (Overpass API, Nominatim, tile.openstreetmap.org) | 지도, 도로 종류, 지역명 | 데이터 © OpenStreetMap contributors, **ODbL 1.0** (https://www.openstreetmap.org/copyright). 타일·Nominatim 사용 정책 준수(User-Agent, 요청 간격, 캐시). 지도에 저작권 표기를 항상 표시 |
| 내장 해안선·강 데이터 `app/src/main/assets/geo_kr.json` (Geofabrik south-korea 추출, 2026-10-28 기준을 단순화) | 해안·강변 판정, 다리/고가 구분 | Data © OpenStreetMap contributors, **ODbL 1.0** (https://opendatacommons.org/licenses/odbl/1-0/). 파생 데이터베이스로서 같은 ODbL 조건으로 제공 |
| Open-Meteo | 현재 날씨·일출/일몰 | **CC BY 4.0** (https://open-meteo.com/en/license), 출처 표기 |
| Spotify Android SDK — App Remote 0.8.0 (app/libs aar) | Spotify 재생 제어 | SDK 저장소 Apache License 2.0 (https://github.com/spotify/android-sdk), 사용 시 Spotify Developer Terms (https://developer.spotify.com/terms) |
| Spotify Android auth-lib 2.1.2 (`com.spotify.android:auth`) | Spotify 로그인 | Apache License 2.0 (https://github.com/spotify/android-auth) |
| Spotify Web API | 곡 검색 | Spotify Developer Terms. 곡·아트워크의 권리는 Spotify와 권리자에게 있음 |
| Pleos Playground SDK (FusedLocation 1.1.3, NaviHelper 3.0.5, Vehicle 2.3.2) | 차량 위치·내비·기어·와이퍼 | Pleos Playground 이용 약관(https://document.pleos.ai). 별도 OSS 라이선스 파일은 확인하지 못함(미확인) |
| AndroidX Core 1.13.1, AndroidX Browser 1.5.0 (auth-lib 경유) | Android 지원 | Apache License 2.0 |
| Gson 2.10.1 | App Remote 직렬화 | Apache License 2.0 |
| Kotlin 표준 라이브러리 | 언어 런타임 | Apache License 2.0 |
| JUnit 4.13.2, org.json 20240303 (테스트 전용, APK 미포함) | 단위 테스트 | EPL 1.0 / Public Domain(JSON License 아님, 20240303 버전) |

길튠 합성 음원 5곡은 `tools/music/generate_tracks.py`로 만든 자체 음원이며, 외부 샘플을 쓰지 않았습니다.
