# Tesla USB → 폰 클립 픽업 빌드 기획

## 목표
Tesla 대시캠/센트리 영상 중 **원하는 클립을, 원할 때 골라서** Android 폰으로 가져온다.
자동 풀 동기화가 아니라 **"목록 보고 → 하나 골라서 → 그 카메라 하나만 다운로드"**.
블루투스는 "차에 탔다" 감지용 트리거로만 쓰고, 실제 영상 전송은 WiFi로 한다.

## 확정 사항
- 폰: **Android**
- 보드: **Radxa Zero 3W** (Rockchip RK3566, 1GB, eMMC 없음, microSD 부팅)
  — Pi Zero 2 W는 전 세계 품귀로 포기, 저전력(차량용 필수)+실제 재고 기준으로 선택
- 전송 경로: **보드 자체 WiFi 핫스팟** (차 안/오프라인에서도 동작, 인터넷 불필요)
- 베이스: **marcone/teslausb 위에 확장** — USB 에뮬은 재사용, **RK3566용 gadget-mode
  overlay는 우리가 직접 작성**(Pi 전용 prebuilt 이미지가 없어서, 이 부분만 신규).
  파일 브라우징 API는 neo가 아니라 **직접 만들어야 함** (Radxa는 neo 미지원, 아래 참고)

## UX (2026-07-05 확정 — 자동 풀싱크에서 변경)
- ❌ "차 타면 새 클립 전부 자동 다운로드" (예전 설계, 폐기)
- ✅ **"필요할 때 앱 열어서, 이벤트 목록 보고, 카메라 하나 골라서 그 클립만 당겨오기"**
- 이유: 실측 결과 6카메라 동시 저장 시 **분당 약 266MB**(front 카메라만 분당 ~76MB) —
  전부 자동 동기화하기엔 부담이지만, **원하는 클립 하나(주로 front)만 골라 받으면
  용량 문제 없음**
- 동기화 대상은 **`SavedClips`/`SentryClips`만** (이벤트 폴더 구조, `event.json`+
  `thumb.png` 있음). **`RecentClips`는 제외** — 차가 알아서 순환관리하는 상시 롤링
  버퍼라 "골라서 가져오기" 용도에 안 맞음. `EncryptedClips`/`Photobooth`도 무관(제외)
- 실제 대시캠 폴더 실측 결과·`event.json` 필드 상세는 [PROGRESS.md](PROGRESS.md) 참고

## 재사용 자산
| 레이어 | 쓸 것 | 직접 짤 것 |
|---|---|---|
| USB 에뮬(가젯모드) 기반 | **marcone/teslausb** (generic install.sh) | RK3566 dwc3 overlay (우리가 작성) |
| 무선 공존(AP+BT) 패턴 레퍼런스 | WirelessAndroidAutoDongle | — |
| BT 근접 깨우기 | android/platform-samples CDM 예제 | 앱에 통합 |
| **파일 목록/다운로드 API** | — | **직접 구현 필요** (neo는 Pi 전용이라 못 씀) |
| 클립 목록·선택 UI | — | **Android 앱 (신규)** |

- 상세 셋업 절차: [`pi/RADXA_SETUP.md`](pi/RADXA_SETUP.md)
- 레포: github.com/marcone/teslausb · nisargjhaveri/WirelessAndroidAutoDongle ·
  android/platform-samples(.../bluetooth/companion)

## 아키텍처
```
Tesla USB 포트
   │ (USB gadget = 가짜 USB 드라이브, RK3566 dwc3 peripheral overlay로 활성화)
Radxa Zero 3W ── teslausb: 클립을 microSD에 아카이브
   │
   ├─ WiFi AP 핫스팟  ← 폰이 접속할 SSID
   ├─ [신규] 파일 목록/다운로드 API — SavedClips/SentryClips 이벤트 목록,
   │         event.json 메타데이터(시각/장소/사유), 카메라별 byte-range 다운로드
   └─ BLE 광고 (폰이 근접 관찰할 대상)
        │
Android 앱 ("클립 브라우저")
   ├─ CompanionDeviceManager: 보드와 association + startObservingDevicePresence(BLE)
   │      → 보드가 BLE 범위에 들면 백그라운드에서 WiFi 미리 연결(대기 상태만, 자동 다운로드 X)
   ├─ 사용자가 앱 열면: 이벤트 목록 화면
   │      (event.json의 timestamp/street/reason + thumb.png 썸네일로 표시)
   └─ 이벤트 탭 → 카메라 선택(기본 front) → 그 파일 하나만 다운로드 → MediaStore 저장
```

**왜 BT 직접 전송이 아닌가:** 블루투스 실효 ~1–2 Mbps. 클립 하나(front, ~75MB)도
WiFi가 아니면 비현실적. BT는 근접 감지(트리거)에만.

**CDM 주의:** `startObservingDevicePresence`는 BLE/BT classic만 지원, WiFi 미지원 —
BT를 트리거로 쓰는 설계와 정확히 맞음. WiFi는 트리거가 아니라 데이터 파이프.

## 단계 (MVP 우선)

### Phase 0 — Radxa에 USB 가젯 모드 올리기 (최대 리스크, 진행 중)
- Armbian vendor 커널로 부팅 → RK3566 dwc3 overlay 작성·적용 → `/sys/class/udc/`에
  컨트롤러 뜨는지 확인 → teslausb generic install.sh
- 상세 절차·현재 상태: [`pi/RADXA_SETUP.md`](pi/RADXA_SETUP.md)
- **검증 포인트:** AP + BLE 동시 동작(무선 공존) — 불안정하면 폰 핫스팟에 보드가
  붙는 방식으로 폴백

### Phase 1 — 파일 목록/다운로드 API (신규 구현 필요)
- neo 없이 직접: `SavedClips`/`SentryClips` 밑 이벤트 폴더 목록 + 각 폴더의
  `event.json` 파싱 결과 + 카메라별 파일 다운로드(byte-range 지원)를 제공하는 작은 서버
- 언어/프레임워크는 보드에서 뭐가 가장 가볍게 도는지 보고 결정(Python/Flask 등 후보)
- **셀프체크:** 폰 브라우저로 이벤트 목록 JSON과 파일 다운로드가 되는지

### Phase 2 — Android 앱: 클립 브라우저
- CompanionDeviceManager로 보드(BLE) association + presence 관찰
  → 근접 시 WiFi 미리 연결(자동 다운로드는 안 함, 그냥 "준비"만)
- **이벤트 목록 화면**: 최근 SavedClips/SentryClips를 시각·장소·사유(event.json)로 표시,
  thumb.png 썸네일
- **선택 다운로드**: 이벤트 탭 → 카메라 고르기(기본 front) → 그 파일만 받아서
  MediaStore.Video 저장
- 출발점: `android/platform-samples`의 CDM 예제. 기존에 만든 `SyncService`(자동
  풀다운로드)는 이 UX에 안 맞으므로 **ClipBrowser 흐름으로 재작성 필요**
- 권한: BLUETOOTH_CONNECT, ACCESS_FINE_LOCATION, FOREGROUND_SERVICE,
  REQUEST_COMPANION_RUN_IN_BACKGROUND, (Android 13+) POST_NOTIFICATIONS

### Phase 3 — 다듬기 (필요 시)
- 여러 카메라 동시 선택, 보드 쪽 오래된 클립 정리, 다운로드 실패 재시도

## 리스크 / 캘리브레이션
- **RK3566 USB gadget mode**: 참고할 완주 성공사례가 아직 없음(csgordon도 미완주).
  단 dwc3 계열 확정, 정확한 노드명 확보 — 상세는 RADXA_SETUP.md
- **무선 공존:** AP + BLE 동시 동작 안정성. 불안정하면 폰 핫스팟에 보드 접속으로 폴백
- **부팅 시간:** Tesla가 USB 전원 줄 때 빠르게 떠야 함
- **CDM 근접 관찰 신뢰성:** 제조사/Android 버전마다 편차 — WiFi 접속 감지를 보조
  트리거로 병행 고려

## 부품
- Radxa Zero 3W 1GB (WiFi6 + BT5.4, USB-C OTG)
- SanDisk 블랙박스전용 High Endurance microSD 128GB
- USB-A ↔ USB-C 데이터 케이블 (Radxa는 micro-5핀 아님, Pi용 케이블 재사용 불가)

---
*ponytail: USB 에뮬은 teslausb 재사용, RK3566 gadget overlay만 신규. 파일서버 API +
Android 클립 브라우저 UI가 실질적인 신규 빌드.*
