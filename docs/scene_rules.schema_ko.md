# scene_rules.json 스키마 (v1)
웹(A)·안드로이드(B)가 공유하는 장면 규칙. 코드 수정 없이 이 파일만 바꿔 튜닝.

| 키 | 타입 | 의미 |
|---|---|---|
| version | int | 스키마 버전(호환 깨지면 +1) |
| road.highwayMap | {OSM highway값: 도로종류} | 도로종류 = MOTORWAY / URBAN_EXPRESSWAY / DOWNTOWN / ALLEY. 매핑 없는 값은 무시 |
| road.importance | [도로종류] | 주변 way 여러 개면 앞쪽(중요) 우선 |
| road.slowSpeedMps | number | 이 속도 미만이면 고가 고속도로(MOTORWAY/URBAN_EXPRESSWAY) 아래 일반도로를 우선 |
| road.labels | {도로종류: 한글} | 표시명. BRIDGE/TUNNEL/UNKNOWN 포함 |
| proximity.roadRadiusM / riverRadiusM / coastRadiusM | m | Overpass `around` 반경(도로 / 강 waterway=river·riverbank·water=river / natural=coastline) |
| osm.* | | Overpass 조회 스로틀: minMoveM 이상 이동 **그리고** minIntervalMs 경과(또는 maxAgeMs 경과)일 때만 조회. cacheGridDeg 격자 캐시, cacheMax개, 실패 시 backoffMs 동안 재시도 안 하고 마지막 값 유지 |
| weather.* | | Open-Meteo 현재 weather_code 조회 간격, rainCodes에 속하면 rain=true (WMO 코드) |
| time.nightStartHour / nightEndHour | 0~23 | 로컬 시각 기준 밤 판정 [start, 24) ∪ [0, end) |
| speed.highwayFallbackMps | m/s | (참고값) 도로 UNKNOWN일 때 고속 판정 |
| speed.drivingMps | m/s | 이 이상이면 "주행 중" → 입력 제한 |
| stabilizer.minHoldMs | ms | 장면 최소 유지시간 |
| stabilizer.confirmCount | int | 새 장면이 연속 N회 판정돼야 전환(히스테리시스) |
| stabilizer.confirmMs / urgentConfirmMs | ms | 새 장면 후보가 이 시간 이상 계속 유지돼야 전환(일시적 오판·짧은 골목 진입 무시). 긴급 장면은 urgentConfirmMs |
| stabilizer.urgentScenes | [id] | 최소 유지시간 무시(터널·다리 진입은 거의 즉시) |
| genres | {키: Spotify genre 필터값} | 취향 장르 선택지 |
| scenes[] | 배열(순서 = 우선순위) | **위에서부터 첫 번째로 `when` 조건을 모두 만족하는 장면 선택**. 같은 id가 여러 번 나와도 됨 |
| scenes[].id / label / caption | string | 장면 키 / 표시명 / 설명 |
| scenes[].when | object | 조건 AND. 키: `roadTypes`(배열, 도로종류 + BRIDGE/TUNNEL/UNKNOWN), `rain`, `coastal`, `river`, `night`(bool), `minSpeedMps`, `maxSpeedMps`(number). 빈 객체 = 항상 참(마지막 기본값) |
| scenes[].local | string | 폴백 자체 MP3 파일명 |
| scenes[].spotifyQuery | string | Spotify `GET /v1/search` 키워드. 취향 장르가 있으면 ` genre:<값>` 를 덧붙이고, 결과 없으면 장르 없이 재검색 |

도로종류 판정: 반경 내 highway way 중 importance 최상위(느리면 고가 무시) way를 고르고, 그 way에 `tunnel`(≠no)이면 TUNNEL, `bridge`(≠no)면 BRIDGE로 덮어씀. way 없으면 UNKNOWN.

## v2 추가 (2026-10-29): 취향·학습
| 키 | 타입 | 의미 |
|---|---|---|
| genres / genreLabels | {키: Spotify genre 필터값} / {키: 한글} | 온보딩 장르 칩. 검색어에 ` genre:<값>` |
| taste.moods / moodLabels | {키: 검색 키워드} / {키: 한글} | 온보딩 분위기 칩. 장면 키워드 뒤에 붙임 |
| taste.plainWeight | number | 장면 키워드만으로 검색하는 계획의 기본 가중치 |
| taste.artistShare | number | 선택 아티스트(`artist:"이름"`) 검색 계획의 기본 가중치 배율 |
| taste.delta.like / complete / skip | number | 피드백 1회당 가중치 변화(좋아요 / 끝까지 들음 / 넘김) |
| taste.minW / maxW | number | 가중치 하한·상한(기본값 1) |
| taste.skipSec | s | 이 시간 전에 넘기면 skip |
| taste.completeRatio | 0~1 | 이 비율 이상 들으면 complete |
| taste.banAfterSkips | int | 같은 곡을 N번 넘기면 다시 선곡 안 함 |
| taste.batchSize | int | 장면 전환 시 큐에 채우는 곡 수 |
| taste.plansPerBatch | int | 한 번에 실행할 검색 계획 수(가중치 비례 추첨) |
| taste.searchLimit | int | Search limit(Development Mode 최대 10) |

가중치 키: `genre:<키>`, `mood:<키>`, `artist:<Spotify artist id>`, `scene:<장면id>`(장면 키워드만), 그리고 장면별 조합 `<장면id>|<키>`. 검색 계획 가중치 = 기본값 × w[키] × w[장면|키]. 곡 순위 = 곡 아티스트들의 w[artist:id] 평균 + 좋아요 보너스 + 작은 무작위.

## 2026-10-29 핫픽스 추가
| 키 | 의미 |
|---|---|
| osm.endpoints | Overpass 미러 목록(앞에서부터 시도, 마지막 성공 미러를 먼저). `osm.endpoint`(단수)는 폐지 |
| osm.timeoutMs | 미러 1곳당 요청 제한시간(ms). 쿼리 자체는 `[timeout:10]` |
| localTitles | {파일명: 곡 제목} 자체 음원 표시용 제목 |
| speed.maxAccuracyM | GPS speed가 없을 때 좌표 차분 계산에 쓸 최대 정확도 반경(m). 넘으면 계산 안 함 |
| speed.maxPlausibleMps | 이보다 빠른 차분 속도는 튐으로 보고 버림 |
| speed.holdMs | 마지막 유효 속도를 화면에 유지하는 시간 |

## Nominatim 보강 폴백
| 키 | 의미 |
|---|---|
| osm.nominatim.endpoint / zoom | Overpass 미러가 모두 실패했을 때만 `reverse?format=jsonv2&zoom=17&extratags=1` 호출. 결과의 `category`(class)=highway, `type`(예: motorway·primary·residential)과 extratags(bridge·tunnel)를 OSM 태그처럼 바꿔 같은 `road.highwayMap`으로 분류 |
| osm.nominatim.minIntervalMs | 요청 간 최소 간격(정책: 초당 1회 이하). 같은 격자 캐시(osm.cacheGridDeg) 공유 |
강·해안 근접은 Nominatim으로 알 수 없어 false로 둠(장면은 도로종류·속도·시간·날씨로 판정).

## v3 (2026-10-29): 선곡 다양화
- **시간대는 장면 결정에서 제외**(`when.night` 폐지, NIGHT 장면 삭제). 시간대는 `taste.timeBands`로 구간을 정하고 `taste.timeMoods`의 분위기에 `timeMoodBoost`배 가중치를 줌.
- `scenes[].spotifyQuery`(단일) → `scenes[].keywords`(배열). 검색어 = 무작위 장면 키워드 + 무작위 분위기 키워드(사용자 선택 + 시간·속도·날씨 분위기) + ` genre:<선택 장르 중 무작위>`.
- `taste.speedMoods`: fastMps 이상이면 fast 분위기, slowMps 이하이면 slow 분위기 후보 추가. `taste.weatherMoods.rain`: 비 올 때 후보 추가.
- `taste.offsetMax`: 검색 offset을 0~offsetMax에서 10 단위 무작위(Development Mode limit 10).
- 반복 방지: 최근 `recentTracks`곡 제외, 최근 `recentArtists`명 아티스트 제외, 한 묶음에 아티스트당 `maxPerArtist`곡.
- 장르 추가: 트로트(trot), 발라드(korean ballad) 등. `genreLabels`는 화면 표시명.

## v4 추가 (2026-10-29)
| 키 | 설명 |
|---|---|
| osm.minMoveM / minIntervalMs | 100m 이동 **그리고** 8초 경과 시 조회(이전 150m/15초). Overpass 공개 서버 안내(대략 하루 1만 건·1GB 이하 권장) 범위. 1시간 주행 최대 약 450회 |
| osm.nominatim.minIntervalMs | 1100ms 유지(정책: 초당 1회 이하). Overpass 실패 때만 호출 |
| road.speedBands.bands | OSM·주소 모두 실패했거나 마지막 결과가 staleMs(45초)보다 오래되면 속도로 도로 성격 추정: ≤8.3m/s(30km/h) 골목, ≤19.4m/s(70km/h) 도심, 그 이상 도시고속도로. 속도가 minSpeedForBandMps 미만/없음이면 UNKNOWN. 터널·다리·강변은 속도로 알 수 없음 |
| road.sourceLabels | 화면 표시용 출처 이름: OSM(Overpass)·주소(Nominatim)·속도·데모·없음 |
| osm.backoffMs (v4 의미 변경) | Overpass 미러가 모두 실패하면 **Overpass만** 이 시간 동안 쉼. 그동안 Nominatim(주소)은 같은 100m/8초 간격으로 계속 조회(초당 1회 이하 보장). 둘 다 실패하거나 결과가 staleMs보다 오래되면 road.speedBands로 판단 → 장면 전환이 멈추지 않음 |

## v5 추가 (2026-10-29)
### 내장 해안선·강 (geo)
| 키 | 설명 |
|---|---|
| geo.dataFile | `geo_kr.json` (shared/, 웹은 pack.sh가 복사). OSM 파생 데이터, **ODbL 1.0, © OpenStreetMap contributors**. 생성 방법·출처는 `tools/geo/README_ko.md` |
| geo.cellDeg | 격자 인덱스 칸 크기(도). 0.02° ≈ 2km. 조회는 주변 칸 선분만 검사 |
| geo.riverRadiusM / coastRadiusM | 내장 데이터 기준 강변·해안 판정 반경(400m). 강변북로·올림픽대로는 한강공원 때문에 수면에서 100~350m |
| geo.bridgeNeedsWater | 이 도로 등급(MOTORWAY·URBAN_EXPRESSWAY)에 bridge 태그가 있어도 **강 수면 위가 아니면** 다리가 아니라 고가(elevated)로 보고 원래 등급으로 판단 |
| geo.bridgeWaterMarginM | 수면 경계에서 이 거리 이내면 물 위로 인정(기본 0 = 수면 다각형 안만) |

geo_kr.json 형식: `coast`(해안선 선), `river`(이름 있는 30km+ 강 중심선), `water`(50만㎡+ 강 수면 다각형 외곽). 각 선/링은 `[dLon,dLat,...]` 1e-5도 정수 누적합.

### 속도 보정
| road.speedBands.fastSustain | 지도 결과가 없을 때 60km/h(16.7m/s) 이상이 30초 이상 지속되면 도시고속도로 후보 |

### 선곡 요소 (taste.context) — 모두 분위기 가중치만, 장면 결정에는 안 씀
| 키 | 설명 |
|---|---|
| factorWeight | 요소 하나가 더하는 가중치 합(그 요소의 분위기들에 나눠 줌). 요소 하나가 독점하지 않게 함(테스트: 요소 비중 ≤35%, 최다 분위기 <40%) |
| weather.codes/labels/moods | Open-Meteo weather_code → 맑음/흐림/안개/비/폭우/눈. `rainScene`에 든 분류는 RAIN 장면 조건(rain)도 켬 |
| temp | hotC 이상 무더위, coldC 이하 추위 |
| season | 월 → 봄/여름/가을/겨울 (weight 0.3) |
| twilight | Open-Meteo daily sunrise/sunset ± marginMin 분 → 해 뜰 무렵/노을 |
| dayType | 공휴일(holidays.dates) 우선, 아니면 토·일 주말 |
| commute | 평일·비공휴일 am 7:00–9:30 출근길, pm 17:30–20:00 퇴근길 |
| lateNight | 0–5시 심야 (아니면 기존 timeMoods 시간대 요소, weight 0.4) |
| jam | 최근 windowSec 동안 평균속도가 도로 종류별 maxAvgMps 미만 또는 stopSec 이상 정차가 stops회 이상 → 정체 |
| longDrive | breakMin(10분) 넘는 정차 없이 minutes(90분) 이상 운전 → 졸음 방지(업템포·신나는, weight 1.0) |
| maxReasons | 화면 '선곡 이유'에 붙는 요소 이름 최대 개수 |
| holidays.dates / source | 2026–2027 한국 공휴일(대체공휴일·선거일 포함, 임시공휴일 제외)과 출처 |
