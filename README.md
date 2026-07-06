# TeslaSync

Tesla 대시캠/센트리 영상 중 원하는 클립을, 원할 때 골라서 Android 폰으로 가져오는
DIY 프로젝트. 자동 전체 동기화가 아니라 **"이벤트 목록 보고 → 카메라 하나 골라서
→ 그 클립만 다운로드"** 방식이다.

**BT = 트리거, WiFi = 데이터 파이프.** 블루투스 실효 대역폭(~1-2 Mbps)으로는
1080p 클립(분당 수십 MB) 전송이 불가능해서, 근접 감지는 BLE로 하고 실제 영상
전송은 WiFi로 한다. 설계 근거는 [PLAN.md](PLAN.md), 전체 개발 과정(부품 목록,
막힌 지점, 버그, 팁)은 [DEVLOG.md](DEVLOG.md)에 정리돼 있다.

## 아키텍처

```
Tesla USB 포트
   │  (USB-A to USB-C 케이블, 데이터+전원 겸용)
Radxa Zero 3W (OTG 포트)
   │
   ├─ USB gadget mode — microSD 안의 큰 파일(cam_disk.bin)을 진짜 USB 드라이브처럼
   │    보이게 함 → Tesla가 여기에 TeslaCam/{SavedClips,SentryClips,RecentClips} 기록
   ├─ teslausb (marcone/teslausb) — USB gadget 설정 + 클립 아카이빙 데몬
   │    └─ 로컬 Samba 서버로 "이미 완성된" 클립만 안전하게 복사
   └─ nginx + fancyindex — 웹 브라우저로 클립 목록 확인·다운로드
        (+ teslausb 내장 비디오 뷰어, 아직 재생 안 되는 버그 있음 — DEVLOG.md 참고)

폰 (또는 PC) — 같은 WiFi에 접속해서 SMB/웹으로 클립 다운로드
```

보드는 **Radxa Zero 3W**(Pi Zero 2 W가 전 세계 품귀라 대체, 저전력이라 여름철
차량 실내 발열에도 적합). USB 에뮬레이션·아카이빙은 `marcone/teslausb`를 그대로
쓰고, RK3566(Rockchip)에 맞는 USB gadget overlay만 직접 확인·적용했다.

## 현재 상태

- ✅ USB gadget mode 동작 확인 (개발 PC에서 실제 "CAM" FAT32 드라이브로 인식)
- ✅ teslausb 설치 완료, 로컬 Samba archive로 클립 다운로드까지 실제 검증
- ✅ 웹 UI(fancyindex) 다크테마 + 터치타겟/대비 수정
- ⬜ **실차 테스트 미완료** — OTG 케이블을 실제 Tesla USB 포트에 연결해 인식되는지 확인 필요
- ⬜ Android 앱은 CDM 페어링 골격만 완료, 클립 브라우저 UX(이벤트 목록 → 카메라
  선택 → 다운로드)에 맞춰 `SyncService` 재작성 필요 — 지금은 폰 브라우저로
  SMB/웹 UI 접속이 임시 대체 수단
- ⬜ teslausb 내장 비디오 뷰어가 테스트 데이터로는 재생 안 되는 버그 미해결

상세 경과는 [DEVLOG.md](DEVLOG.md)의 "알려진 이슈 / 다음 단계" 참고.

## 구성 요소

- [`pi/RADXA_SETUP.md`](pi/RADXA_SETUP.md) — Radxa 보드 셋업 전체 절차(OS 굽기,
  WiFi, USB gadget overlay, teslausb 설치, 버그 수정 기록)
- `pi/ble_advertise.sh`, `pi/test_coexistence.sh` — BLE 광고 + AP/BLE 무선 공존
  테스트 스크립트 (아직 Radxa에서 실행 검증 전)
- `android/` — Android 앱 골격 (CompanionDeviceManager 페어링 완료,
  `SyncService`는 옛 "자동 풀싱크" UX 기준이라 재작성 예정)
- [`PLAN.md`](PLAN.md) — 설계 근거와 확정 사항
- [`PROGRESS.md`](PROGRESS.md) — 진행 로그, 실측 데이터(대시캠 파일 포맷·비트레이트 등)
- [`DEVLOG.md`](DEVLOG.md) — 부품 목록부터 막힌 지점, 버그, 팁까지 전체 개발 기록

## 다음 할 일

1. Android 앱 `SyncService`를 클립 브라우저 UX + 파일서버 API에 맞춰 재작성
   (지금은 폰 브라우저로 SMB/웹 UI 접속이 임시 대체 수단)
2. 웹 UI가 RecentClips(상시 녹화)까지 나열하도록 개선
3. teslausb 내장 비디오 뷰어 버그 — 실차 데이터로도 재현되면 디버깅

## 기반·크레딧

- **[marcone/teslausb](https://github.com/marcone/teslausb)** — USB 가젯 에뮬레이션과
  클립 아카이빙의 핵심. 이 프로젝트는 teslausb를 **수정·재배포하지 않고** 그 위에
  Radxa Zero 3W 셋업·Android 앱·웹 UI를 얹은 것입니다. teslausb는 별도로 설치해야
  합니다(설치 절차는 [`pi/RADXA_SETUP.md`](pi/RADXA_SETUP.md) 참고).
- Android CompanionDeviceManager 예제: `android/platform-samples`

## 라이선스

이 저장소의 코드(Android 앱, `pi/` 스크립트, 문서)는 [MIT License](LICENSE)로 배포됩니다.
teslausb 등 별도 프로젝트는 각자의 라이선스를 따릅니다.
