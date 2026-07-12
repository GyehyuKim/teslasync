# TeslaSync 진행 로그

차에 타면(BLE 근접) Tesla 대시캠 영상을 Android 폰으로 자동 import.
설계 근거는 [PLAN.md](PLAN.md), 구성요소는 [README.md](README.md).

마지막 업데이트: 2026-07-12

---

## ✅ 2026-07-12 clip-browser main 병합 + 다운로드 신뢰성 고도화
- **병합**: `worktree-clip-browser`(clipserver + 앱 클립 브라우저)를 PR #1로 main에
  통합 (merge commit `7c033c5`). revert 흔적(d811b32→b5a84c6) 보존, 최종 코드는
  `ab1d9f2`/`587df5d` 기준.
- **다운로드 신뢰성** (`download-reliability` 브랜치):
  - `ClipApi.openFile`: HTTP status 검증 — 전체 200 / Range 206 아니면 IOException
    (404/416/500을 조용히 흘려 엉뚱한 바이트를 클립으로 저장하지 않게)
  - `DownloadService`: 받은 바이트 == 총 크기 검증(연결 끊김에 의한 **조용한 잘림**
    차단 — 잘린 클립이 '완료'로 갤러리에 저장되던 위험) + 클립당 3회 재시도(백오프)
  - `pi/test_clipserver.py`: 이어받기(Range) 재조립 계약 테스트 추가
- **검증**: `python3 pi/test_clipserver.py` **20개 통과**. Android 빌드는
  **비대화형 세션의 승인 게이트 때문에 실행 불가** → Kotlin 변경은 **인스펙션으로만
  검증**(재빌드는 실기 준비 시 필요).

---

## ✅ 2026-07-12 Phase 1 + Phase 2 구현: 파일 API 서버 + 앱 클립 브라우저 재작성
로드맵의 두 미구현 단계를 코드로 만들었음. **로컬 검증만 완료 — 보드 배포·실기
연결은 아직 안 함** (검증/미검증 구분 유지).

**Phase 1 — `pi/clipserver.py` (신규, 파이썬 표준 라이브러리만)**
- `GET /api/events`: SavedClips/SentryClips 이벤트 목록(최신순) + event.json
  메타(timestamp/street/reason) + 카메라별 클립·크기. 깨진 event.json은 폴더명 폴백
- `GET /files/<타입>/<이벤트>/<파일>`: mp4/thumb.png 서빙, **Range(이어받기) 지원**
- RecentClips/EncryptedClips/Photobooth는 화이트리스트로 원천 차단(PLAN 결정 반영),
  `../` 경로 탈출 차단. 아카이브가 `TeslaCam/` 하위에 쌓이는 배치도 자동 인식
- `GET /healthz`: 배포 셀프체크 — `/api/events`는 루트가 없어도 빈 목록 200이라
  배포 실패를 못 잡아서 별도로 둠 (ok/root/event_dirs 반환)
- ✅ **`python3 pi/test_clipserver.py` — 20개 테스트 전부 통과** (Python 3.9 로컬)
- `pi/clipserver.service`: systemd 유닛. 읽기전용 루트 대응으로 스크립트를
  `/mutable`에 두는 구성 (smbd 버그 때와 같은 교훈 적용)
- `pi/install_clipserver.sh`: 보드 checkout에서 `sudo pi/install_clipserver.sh` 한 번으로
  설치→enable/start→`/healthz` 확인. `/`가 읽기전용이면 rw로 풀었다가 **원복**까지 함

**Phase 2 — Android 앱을 "클립 브라우저"로 재작성 (07-05 요구사항 변경 반영)**
- 삭제: `SyncService`(자동 풀싱크 — 분당 266MB 실측으로 폐기된 UX),
  `NeoApi`(존재하지 않는 neo `/api/v1` 가정 — 보드 변경으로 무효화됐던 것)
- 신규: `ClipApi`(clipserver 계약), `PiNet`(핫스팟 연결 공용 관리),
  `ClipBrowserActivity`(이벤트 목록 → 탭 → 카메라 선택[front 우선, 세그먼트 수·MB
  표시, 받은 것 ✓] → 단건 다운로드), `DownloadService`(선택 파일만 순차 다운로드,
  진행률 알림, MediaStore IS_PENDING으로 부분 파일 노출 방지, 실패 시 부분 파일 삭제)
- `CarCompanionService`: 근접 시 자동 다운로드 대신 **WiFi 사전 연결 + "클립 보기"
  알림**으로 변경 (PLAN "준비만" 설계 그대로)
- ✅ **`assembleDebug` 빌드 통과** (APK 5.5MB). ⚠️ 실기(폰↔보드) 검증 미완
- `Config.kt`: `NEO_BASE` → `API_BASE`(`http://192.168.4.1:8080`) — AP 게이트웨이
  IP/SSID는 여전히 "남은 미지수" (보드 AP 구성 후 교체)

**다음 할 일**: ① 보드에 clipserver 배포(`sudo pi/install_clipserver.sh`) 후
폰 브라우저로 `/healthz` → `/api/events` 셀프체크 ② 보드 AP 구성 → `Config.kt` 실값 반영 ③
실기 페어링 + 클립 다운로드 검증

---

## ✅ 2026-07-06 큰 진전: teslausb 전체 파이프라인 실사용 가능 확인
Radxa Zero 3W에서 USB gadget mode 완전히 작동 확인 (`fcc00000.usb` UDC 바인딩,
맥에서 실제 "CAM" FAT32 드라이브로 인식+마운트 성공), teslausb 설치 완료,
SMB로 네트워크 다운로드까지 실제 검증. smbd 재부팅 크래시 버그 발견·수정.
fancyindex 웹 UI 다크테마 개선 + 터치타겟 버그 수정. **teslausb 내장 비디오
뷰어 발견**(우리 수동 테스트 데이터로는 재생 안 됨, 실차 테스트 때 재확인 필요).
상세 전체 기록: [`pi/RADXA_SETUP.md`](pi/RADXA_SETUP.md)

**다음 할 일**: 실차 연결 테스트(사용자가 진행 예정) → 실제 이벤트가 쌓이면
teslausb 웹 뷰어가 정상 재생되는지 재확인.

---

## 핵심 설계 (확정, 보드와 무관)
- **BT = 트리거, WiFi = 데이터 파이프.** 블루투스 직접 전송은 대역폭상 불가(~1-2Mbps)
- SBC가 Tesla USB 포트에 꽂혀 **USB 드라이브를 에뮬레이트** → 영상은 **microSD**에 저장
- 차 안에서 SBC가 **WiFi 핫스팟** → 폰이 접속 → 웹/API로 영상 다운로드
- **폰이 곧 백업** (NAS 없음). microSD는 거쳐가는 버퍼
- Android **CompanionDeviceManager**가 SBC(BLE) 근접을 감지해 앱을 깨움

## 저장 구조 (자주 헷갈린 부분)
- 저장소는 **microSD 한 곳뿐.** 기존 USB 메모리는 **안 씀**(보드가 그 포트 차지)
- Tesla는 **한 번에 한 드라이브에만** 기록 → "USB+SD 동시 저장(이원화)"은 Tesla가 지원 안 함
- 이중화는 Tesla쪽이 아니라 **microSD(원본) + 폰(복사본)**으로 달성. 폰 사본이 안전망
- 기존 USB 메모리는 **"보드 고장 시 10초 복귀용 비상부품"**으로 보관

---

## 하드웨어 (보드 = Radxa Zero 3W 로 변경됨)

### 왜 Pi Zero 2 W → Radxa Zero 3W 로 바꿨나
- Pi Zero 2 W는 **전 세계 품귀**. 국내 전부 품절, The Pi Hut도 품절, AliExpress·쿠팡·Arace는
  미끼/웃돈(9~11만원). **쿠팡 주문은 셀러가 재고를 못 구해 자동 취소됨** → 신뢰 불가
- Pi 4는 재고 있으나 **여름철 차량 발열로 녹화 끊길 위험** + 전력/크기 → 차량용 부적합
- **Radxa Zero 3W = 저전력(차량 OK) + 실제 재고 + teslausb 커뮤니티 지원** → 채택
- 교훈: 품귀+국제배송+메모리값 상승으로 "MSRP 2만원"은 환상. 실구매가 **6.5만원이 바닥**

### 구매 확정 목록
| 품목 | 선택 | 가격 | 상태 |
|---|---|---|---|
| **보드** | **Radxa Zero 3W 1GB** (AliExpress, Byte Factory Store) | ₩65,010 (총 ₩72,804) | **도착 완료** (예정이던 7/10보다 앞당겨 수령, 아래 "큰 진전" 로그대로 실사용 테스트 중) |
| **microSD** | SanDisk 블랙박스전용 High Endurance **128GB** (SDSQQNR) | ~3만원대 | 보유 |
| **케이블** | **USB-A ↔ USB-C "데이터" 케이블** | 2~3천원 | 미구매 (집 케이블 확인 후) |

### 구매 시 교훈 (반복된 함정)
- AliExpress **변형 미끼**: 표시 저가는 최저 변형(1GB)이고, 비싼 변형(4GB/eMMC) 누르면 가격 폭등.
  → 우리는 **1GB, eMMC 없음**이면 충분(Pi Zero 2W가 512MB로 다 했음). eMMC는 가성비 꽝이라 뺌
- 보드 확정 주의: **"Zero 3W"(WiFi/BT)** ↔ "3E"(이더넷, WiFi 없음 — 절대 안 됨) 구분
- **케이블 변경**: Radxa는 **USB-C** 포트 → Pi용 micro-5핀 케이블 못 씀. **USB-A to C** 필요.
  C-to-C는 Tesla(USB-A)에 안 꽂힘. 데이터 지원되는 A-to-C여야 함

---

## 소프트웨어

### ✅ 완료 (보드 무관하게 유효)
- **Android 앱 빌드·실기 설치 성공** → `app/build/outputs/apk/debug/app-debug.apk`
  - 폰에 설치되어 실행됨 (CDM 페어링 화면 뜸)
  - 잡은 버그: ① AppCompat 테마 없이 AppCompatActivity → 즉시 크래시 → **ComponentActivity로 전환**
    ② Kotlin 한글 템플릿 변수 (`$saved개` → `${saved}개`)
  - 페어링 버튼 실패 시 크래시 대신 **화면에 이유 표시**하도록 방어코드 추가 (진단용)
- **Android 빌드 환경 구축**:
  - SDK(android-34, build-tools 34.0.0) at `~/Library/Android/sdk`
  - Gradle wrapper **8.7** (AGP 8.5.0 호환), JDK = Android Studio 내장 JBR 21
  - 빌드: `cd android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug`

### ⚠️ 보드 변경으로 무효화된 것
- **teslausb-neo 이미지(`teslausb/sdcard.img.xz`)는 Pi 전용 → Radxa엔 못 씀.**
  Radxa는 아래 "참고 레포"의 Zero 3W 셋업을 따라야 함 (prebuilt 이미지 없음, 더 수동)
- `teslausb/teslausb.draft.toml`도 neo 전용 → Radxa 셋업에서 다시 작성 필요
- Android `NeoApi.kt`의 `/api/v1/*` 가정도 neo 기준 → Radxa는 우리가 파일서버를
  직접 올려야 할 수 있음 (PLAN.md의 Flask 서버 아이디어 부활 가능)

---

## Tesla 대시캠 실제 저장 포맷 (2026-07-05 확인, 출처 있음)
- 폴더: `TeslaCam/{SentryClips,SavedClips,RecentClips}/이벤트폴더(YYYY-MM-DD_HH-MM-SS)/`
- **이벤트 폴더 안엔 영상만 있는 게 아님**: 카메라별 `*.mp4`(front/back/left_repeater/
  right_repeater 등) + **`event.json`(메타데이터)** + **`thumb.png`(썸네일)**가 섞여 있음
- **코덱**: HW3 이상 차량은 **H.265(HEVC)**, 이전 하드웨어는 H.264일 수 있음(컨테이너는
  둘 다 mp4라 MIME은 `video/mp4`로 동일, 재생 호환성은 폰 쪽 문제라 우리 코드와 무관)
- **버그 발견·수정**: `SyncService.kt`가 폴더의 모든 파일(json/png 포함)을 무조건
  `MediaStore.Video`로 저장하려던 버그 발견 → **`.mp4` 확장자만 필터링하도록 수정**
  (안 고쳤으면 event.json/thumb.png가 깨진 동영상으로 갤러리에 들어갈 뻔함)
- 출처: puretesla.com, Tesla 공식 오너 매뉴얼, teslamotorsclub.com, marcone/teslausb issue #825

### 추가 조사 (2026-07-05, 2차)
- **파일 크기**: 카메라 1개당 약 25~30MB/분 → 4캠 동시면 1분 이벤트당 대략 100~120MB
  (WiFi 동기화 소요시간 추정 근거. 출처: teslamotorsclub.com 실측 스레드)
- **저장공간 처리**: SentryClips는 5GB 넘으면 **오래된 것부터 자동 삭제(FIFO)**,
  SavedClips는 삭제 안 되고 꽉 차면 **저장 자체가 실패**함 — 그래서 "느리게 동기화"가
  단순 불편이 아니라 **실제 데이터 유실로 이어질 수 있음**이 확인됨
- **event.json 실제 필드** (ehendrix23/tesla_dashcam 소스 확인): `timestamp`, `city`,
  `street`, `reason`(예: `sentry_aware_object_detection`), `est_lat`/`est_lon`
  → 나중에 앱에 "감지 사유" 표시하는 기능에 쓸 수 있음 (지금 범위 아님, 아이디어로만 기록)
- **Android HEVC 호환성**: API 21+부터 디코딩 가능, API 31+는 시스템이 자동 변환까지 지원
  → 웬만한 최신 폰에서 재생 문제 없을 것으로 판단
- ⚠️ **불확실**: Tesla 공식 매뉴얼 원문이 403으로 직접 열람 안 돼서 USB
  파일시스템(GPT/MBR, 볼륨레이블) 요구사항은 검색엔진 요약에만 의존 — 재검증 필요.
  exFAT/FAT32/ext4 지원, 64GB+ 권장, 4MB/s 이상 쓰기속도는 확인됨

## 실제 Tesla USB 드라이브 실측 (2026-07-05, 3차 — 조사 아니라 실물 확인)
기존 USB 메모리를 맥에 직결해서 실제 내용 확인. 조사(문서)와 다르거나 새로 안 것들:

- **`RecentClips`는 이벤트 폴더 구조가 아님** — `타임스탬프-카메라.mp4` 파일이 폴더 없이
  평평하게 나열됨. `SavedClips`/`SentryClips`만 이벤트 폴더(+event.json+thumb.png) 구조
- **실제 파일 크기가 조사 추정치보다 훨씬 큼**: front 카메라 분당 ~75~79MB,
  나머지 5개(back/좌우 필러/좌우 리피터) 분당 ~37~40MB씩 → **6캠 합쳐 분당 약 266MB**
  (조사에서 나온 "분당 100~120MB(4캠 가정)"는 이 차량 기준 과소추정이었음 — HW4급
  6카메라라 그런 듯)
- **event.json 실물 예시** (`SavedClips` 이벤트 하나):
  ```json
  {"timestamp":"2026-06-24T16:17:43","city":"","street":"○○로",
   "est_lat":"37.5665","est_lon":"126.9780",
   "reason":"user_interaction_dashcam_multifunction_selected","camera":"0"}
  ```
  → `camera` 필드는 조사에서 못 찾았던 새 필드(트리거된 카메라 인덱스로 추정).
  (도로명·좌표는 개인정보라 예시값으로 대체함 — 실제 필드 형식만 보여주는 용도)
- **예상 못한 폴더 2개**: `EncryptedClips`(비어있음, 용도 불명), `Photobooth`(`.webp`
  이미지, 대시캠과 무관한 기능) — 둘 다 우리 동기화 대상에서 제외해야 함.
  Android `.mp4` 확장자 필터가 이미 이걸 자동으로 걸러줌(전에 고친 버그 수정 덕)
- **파일시스템**: exFAT 맞음 (diskutil이 파티션타입 코드를 "Windows_NTFS"로 잠깐
  헷갈리게 표시했으나, 실제 Type(Bundle)은 "exfat"으로 확인됨 — MBR 0x07 타입코드가
  NTFS/exFAT/HPFS 공유라 생기는 표시상 혼동일 뿐)

## 요구사항 변경 (2026-07-05) — 자동 풀싱크 → 수동 클립 픽업
- 사용자 확인: 자동 전체 동기화 불필요. **"필요할 때 골라서, 카메라 하나(주로 front)만
  다운로드"**가 실제 요구사항. 위 실측 파일크기(분당 266MB) 감안하면 이 방향이 합리적
- 동기화 대상 폴더를 **`SavedClips`+`SentryClips`로 한정**, `RecentClips` 제외 결정
- PLAN.md 전면 갱신: Android 앱을 기존 `SyncService`(자동 풀다운로드)에서
  **"클립 브라우저"(이벤트 목록 → 카메라 선택 → 단건 다운로드)**로 재설계
- 기존 `android/` 코드(`SyncService.kt` 등)는 이 새 UX 기준으로 **재작성 필요** —
  파일서버 API(Phase 1, 아직 미구현)가 먼저 정해져야 착수 가능

## 참고 레포·자료 (맨땅 아님 — 이걸 딛고 감)

**USB 에뮬레이션 + 아카이빙 (가장 어려운 부분, 재사용)**
- `marcone/teslausb` — teslausb 본체 (스크립트 경로)
- `bt/teslausb-radxa-zero` — Radxa용 포크 (원조 Radxa Zero 기준)
- **csgordon gist "Notes on running with the Radxa Zero 3w"** — 우리 보드(3W) 커뮤니티 셋업 노트 ← 핵심
- `marcone/teslausb` Discussion **#864** (radxa zero 3w) — 3W 관련 논의

**Radxa OS / USB 가젯**
- `docs.radxa.com/en/zero/zero3` — OS 이미지 굽기 + RK3566 USB gadget(dwc3/configfs) 설정

**Android 앱**
- `android/platform-samples` (.../bluetooth/companion) — CompanionDeviceManager 공식 예제 (우리 앱 출발점)

**우리가 직접 짠 것** (= 신규 개발 부분)
- `android/` — CDM 페어링 → 포그라운드 동기화 서비스 (BLE 트리거 + WiFi 다운로드)
- `pi/ble_advertise.sh`, `pi/test_coexistence.sh` — BLE 광고 + AP/BLE 공존 테스트 (Radxa BlueZ에서도 동작 예상)

---

## 🗺️ 하드웨어 도착 후 로드맵

1. ✅ **Radxa에 teslausb 올리기** — csgordon gist + Discussion #864 따라:
   Radxa OS 굽기 → RK3566 USB 가젯 모드 설정 → teslausb 스크립트 설치까지 완료
   (상세: [`pi/RADXA_SETUP.md`](pi/RADXA_SETUP.md)). 맥에서 "CAM" FAT32 드라이브로
   정상 인식 확인. **실차 연결 시 인식되는지는 아직 미검증**
2. **WiFi AP + 파일 서버** — 지금은 로컬 Samba(archive 공유) + teslausb 내장
   nginx/fancyindex 웹 UI로 대체 중. Android 앱 전용 파일서버 API는 아직 미구현
3. **BLE 광고** — `pi/ble_advertise.sh`를 Radxa BlueZ에서 구동
4. **무선 공존 테스트** — `pi/test_coexistence.sh`로 AP+BLE 동시 안정성 (불안정 시 폰 핫스팟 폴백)
5. **Android 앱 연결** — `Config.kt`의 SSID/IP/UUID를 Radxa 실제값으로 + 파일 API 경로 맞춤
6. **차량 통합 테스트** — Tesla 연결 → 녹화·근접트리거·자동다운로드 며칠 검증 후 신뢰

### 남은 미지수
1. **AP + BLE 무선 공존** 안정성 (`test_coexistence.sh`, 아직 미실행)
2. **파일 서버를 직접 올릴지 / teslausb 웹 UI를 쓸지** — 현재는 teslausb 내장 웹 UI
   (다크테마 적용됨) 사용 중, Android 앱용 API는 별도 설계 필요
3. **AP 게이트웨이 IP / SSID / BLE UUID** → Android `Config.kt`에 반영
4. **케이블**: 집 USB-A↔C 데이터 케이블 확인 (없으면 구매)

---

## 다음 할 일
- [ ] (안 급함) 집에서 **USB-A ↔ C 데이터 케이블** 확보 — 한쪽이 큰 USB-A인 것
- [ ] 앱은 이미 폰에 깔림 — 보드 BLE 광고 켜지면 실제 페어링 테스트

## 결정: Radxa Zero 3W로 확정 진행 (취소 안 함)
- 2026-06-30 결정. 근거: 하드웨어(저전력)는 차량 용도에 맞음 — 문제는 소프트웨어 셋업이 덜 닦인 길이라는 것뿐
- **리스크 정확한 위치**: `bt/teslausb-radxa-zero` 포크는 원조 Radxa Zero(Amlogic S905Y2)용이고
  **우리 3W는 칩이 다름(Rockchip RK3566)** → 그 포크를 그대로 못 씀. 3W는 아직 전용 포크가 없음
- **⚠️ 정정 (2026-07-01 상세 조사 후)**: csgordon gist는 "성공 사례"가 아니라
  **USB gadget mode 설정 중 막힌 채(Mac에서 인식 안 됨) 끝난 미완주 기록**이었음.
  이전에 "검증됨"이라 표현한 건 과장이었음. 다만 같은 RK35xx/RK3588 계열
  (Rock 5C Lite, Rock Pi 4C+)에서는 유사 방법으로 **실제 성공 사례가 있어** 막다른 길은 아님.
  또한 teslausb에 **범용(비-라즈베리파이) 설치 스크립트**(`setup/generic/install.sh`)가
  이미 존재해서, USB gadget mode만 뚫으면 이후는 무리 없이 진행 가능.
- **상세 셋업 절차**: [`pi/RADXA_SETUP.md`](pi/RADXA_SETUP.md) — 단계별 셀프체크,
  확실/추정 구분, 도착 당일 실행 순서 포함
- **폴백 순서(3W 가젯모드가 안 뚫리면)**: ① Discussion #864/#1021에 진행상황 공유해 피드백 받기
  ② RK35xx 성공사례 기준으로 overlay 직접 시도 ③ 그래도 안 되면 원조 Radxa Zero로 보드 교체

### 🔍 2차 조사 (2026-07-01) — 관문을 하나로 좁힘
- **좋은 소식**: teslausb의 `run/enable_gadget.sh`는 **UDC를 자동 감지**함
  (`/sys/class/udc/`에서 첫 컨트롤러를 읽음) → 뚫어야 할 지점은 사실상
  **"USB 컨트롤러를 peripheral 모드로 만드는 overlay" 단 하나**로 좁혀짐
- **확보한 것**: RK3399(Rock Pi 4C+)·RK3588(Rock 5C Lite)의 **실제 검증된 overlay
  레시피**(`dr_mode="peripheral"` + `maximum-speed="super-speed"`), Armbian vendor
  이미지 정확한 위치(`dl.armbian.com/radxa-zero3/`, vendor 6.1.115 커널 필수)
### 🔍 3차 조사 (2026-07-01) — dwc2/dwc3 확정 (실기 없이 회로도·커널소스로 가능했음)
- 커널 dts(`rk356x-base.dtsi`/`rk3566-base.dtsi`/`rk3566-radxa-zero-3.dtsi`)와
  공식 스키매틱 PDF를 대조해 **확정**: OTG 포트는 **dwc3 드라이버**(dwc2 아님).
  단 RK3566엔 USB3 SuperSpeed PHY 배선이 없어 속도만 USB2로 묶임 — 그래서 스펙에
  "USB2.0 OTG"로 표기된 것. RK3399/RK3588과 **같은 dwc3 드라이버 계열**이라
  그쪽 성공 레시피가 거의 그대로 적용 가능해짐
- **정확한 device tree 노드명까지 확정**: `usb_host0_xhci` (실기 없이는 못 얻는 정보)
- 교훈: "하드웨어라 알 수 없다"고 여긴 것도, 실은 스키매틱·커널소스라는 1차 자료가
  공개돼 있어서 더 깊이 파면 확정 가능했음. 1차 자료 도달 전엔 "불확실"로 성급히
  단정하지 말 것
