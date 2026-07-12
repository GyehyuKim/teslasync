# Radxa Zero 3W에 teslausb 올리기 — 실행 노트

조사 기반: csgordon gist, marcone/teslausb Discussion #864/#883/#1021,
armbian.com/radxa-zero-3, armbian/build overlay 저장소, teslausb 소스코드 실측.

**✅ 2026-07-06 돌파 성공.** csgordon이 막혔던 gadget mode 단계를 실제로 넘었음 —
`ls /sys/class/udc/` → **`fcc00000.usb`** 확인. 아래는 그 과정과, 이후 재현할 사람을
위한 정확한 절차.

**실제로 통했던 방법 (추측 아니라 실측)**:
- Armbian vendor 6.1.115 (`Armbian_26.2.1_Radxa-zero3_trixie_vendor_6.1.115_minimal`) 굽기
- `/boot/dtb/rockchip/overlay/`에 **`rk3568-dwc3-peripheral.dtbo`가 이미 존재함**
  (직접 overlay 작성 필요 없었음 — 이전 조사에서 "overlay가 없을 수 있다"고 걱정했던
  것과 달리, 실제로는 Armbian이 rk356x 계열용으로 이미 제공하고 있었음)
- `/boot/armbianEnv.txt`에 `overlays=rk3568-dwc3-peripheral` 한 줄만 추가 → 재부팅
- `ls /sys/class/udc/` → `fcc00000.usb` 등장 = **성공**
- **재부팅 후 재로그인해서 재확인 → 여전히 `fcc00000.usb` 유지됨** (우연 아니고 안정적)
- **보너스 검증**: HOST 포트에 실제 Tesla exFAT USB 드라이브 연결 →
  `sudo mount /dev/sda1 /mnt` → `TeslaCam/{EncryptedClips,Photobooth,RecentClips,
  SavedClips,SentryClips}` 정상 인식 (맥에서 봤던 구조와 100% 일치) — exFAT 지원도 확인됨

## ✅✅ 2026-07-06 최종 완주 — teslausb 설치 + gadget mode 실사용 확인
`marcone/teslausb` generic install.sh(`main-dev` 브랜치) 끝까지 실행 성공. 핵심 우회:
- **archive 설정이 필수**임을 확인(스킵 불가, 이 브랜치는 CIFS 하드코딩) →
  **보드 자기 자신에 로컬 Samba 서버**를 띄워 `ARCHIVE_SERVER=127.0.0.1`로 우회.
  이러면 필수체크 통과 + 나중에 우리 앱이 읽을 "정리된 클립 폴더"가 부산물로 생김
- `apt-get -y install samba` → 공유 폴더(`/backingfiles/archive_share`) + 전용 계정
  (`archiveuser`) 생성 → `smb.conf`에 `[archive]` 섹션 추가 → `smbd` 재시작
- `teslausb_setup_variables.conf`(실제 위치는 `/root/`로 재배치됨, `/teslausb`는
  `/boot`의 심볼릭 링크)에 `ARCHIVE_SERVER=127.0.0.1`, `SHARE_NAME=archive`,
  `SHARE_USER=archiveuser`, `SHARE_PASSWORD=...` 설정 후 `/etc/rc.local` 재실행
- 설치 완료 로그: `"All done."` — 마지막에 자동 재부팅 발생(readonly rootfs 적용 위함)

**최종 검증 (실기, 최고 수준의 증거)**:
- `ls /sys/kernel/config/usb_gadget/` → `teslausb` 존재
- `cat .../teslausb/UDC` → `fcc00000.usb` (gadget이 실제 컨트롤러에 바인딩됨)
- `.../mass_storage.0/lun.0/file` → `/backingfiles/cam_disk.bin` (CAM 파티션과 연결됨)
- **OTG 케이블을 맥에 연결한 채로 맥에서 직접 확인** → `diskutil list`에
  **"CAM" 96.6GB Windows_FAT_32 외장 드라이브로 실제 인식됨**
- 마운트해서 열어보니 **`TeslaCam/`, `TeslaTrackMode/` 폴더가 이미 준비돼 있음**
  (Tesla가 인식하는 정확한 폴더 구조) — **하드웨어 파이프라인 전체가 실사용 가능함을 확인**

**남은 것**: 이 OTG 케이블을 실제 Tesla USB 포트에 연결해 진짜 차량에서도
동일하게 인식되는지 최종 확인 (이론상 될 것으로 보이나 아직 실차 테스트는 안 함)

## 🐛 발견·수정: smbd가 재부팅 후 크래시하는 버그 (2026-07-06)
**증상**: teslausb 설치 마지막 단계(`make-root-fs-readonly.sh`)가 루트를 읽기전용으로
바꾸는데, 그 상태로 재부팅하면 **smbd가 매번 크래시**해서 archive(SMB) 다운로드가 안 됨.

**정확한 원인** (`/var/log/samba/log.smbd` 확인):
```
Failed to open /var/lib/samba/private/secrets.tdb
ERROR: failed to setup profiling
```
`/var/lib/samba`, `/var/cache/samba`가 **루트파일시스템(읽기전용 대상)에 있어서**
쓰기 실패 → smbd 즉시 종료.

**수정 (영구 반영, fstab 등록됨)**:
```bash
systemctl stop smbd nmbd
mkdir -p /mutable/samba-lib /mutable/samba-cache
cp -a /var/lib/samba/. /mutable/samba-lib/
cp -a /var/cache/samba/. /mutable/samba-cache/
rm -rf /var/lib/samba/* /var/cache/samba/*
# /etc/fstab에 추가:
#   /mutable/samba-lib /var/lib/samba none bind 0 0
#   /mutable/samba-cache /var/cache/samba none bind 0 0
mount /var/lib/samba
mount /var/cache/samba
```
`/mutable`은 teslausb가 설계상 **항상 쓰기 가능하게 유지하는 영구 파티션**이라, 여기로
옮기면 root가 읽기전용이어도 samba 상태(비밀키 등)가 안전하게 유지됨.

**검증**: `mount / -o remount,ro` 후 `systemctl restart smbd` → 정상 기동 확인,
SMB 네트워크 접속도 정상 확인 (재부팅 시뮬레이션, 실제 재부팅 없이 검증)

## 📱 클립 서버(clipserver) 배포 — Android 앱용 파일 API (2026-07-12 작성, ⚠️ 보드 미배포)
Android 클립 브라우저가 쓰는 Phase 1 API. 코드는 이 레포 `pi/clipserver.py`
(표준 라이브러리만 — 보드에 pip 불필요, python3만 있으면 됨). 로컬 20개 테스트
통과 상태이며 **보드 실배포·실데이터 검증은 아직**.

```bash
# 루트가 읽기전용이므로 잠깐 풀고 작업 (smbd 수정 때와 동일 패턴)
sudo mount / -o remount,rw

# 스크립트는 항상 쓰기 가능한 /mutable에 (재부팅에도 유지됨)
scp pi/clipserver.py <보드>:/mutable/clipserver.py
scp pi/clipserver.service <보드>:/etc/systemd/system/clipserver.service
sudo systemctl daemon-reload
sudo systemctl enable --now clipserver

sudo mount / -o remount,ro   # 원복
```

**셀프체크** (폰/맥 브라우저 또는 curl):
```bash
curl http://<보드IP>:8080/api/events                       # 이벤트 목록 JSON
curl -H "Range: bytes=0-99" -o /dev/null -w "%{http_code}" \
     "http://<보드IP>:8080/files/SavedClips/<이벤트>/<파일>.mp4"   # 206이면 정상
```
- 아카이브 루트 기본값은 `/backingfiles/archive_share` — 다르면
  `clipserver.service`의 `--root` 수정
- Android `Config.kt`의 `API_BASE`가 AP 게이트웨이 IP + 이 포트(8080)를 가리켜야 함

## 🎨 UI/UX 개선 (2026-07-06 밤, design-review 방식으로 진행)
사용자가 "fancyindex 목록이 못생겼다"고 지적 → 다크테마 CSS 작성·적용, 실제
스크린샷으로 검증하며 반복 개선. git 저장소가 없는 원격 임베디드 보드라
design-review 스킬의 git 커밋/테스트부트스트랩 절차는 생략, 체크리스트
알맹이만 적용(SSH 직접 편집 + 브라우저 스크린샷 검증).

**적용/발견/수정한 것**:
- 다크테마 CSS (`/var/www/html/fancyindex.css`) — fancyindex 인라인 스타일의
  짝수행 흰 배경(`#f4f4f4`) 새는 버그 발견·수정
- `fancyindex_exact_size off;` 추가 → 파일 크기 사람이 읽기 쉬운 단위(MiB)로 표시
- **터치 타겟 버그**: 파일명 `<a>`의 실제 클릭 가능 높이가 21px뿐이었음(행 높이
  46px인데 텍스트 줄 높이만 클릭됨) — 44px 최소 기준 미달. `<td>`의 패딩을
  `<a>`로 옮겨 클릭 영역이 행 전체(47px)를 채우도록 수정, JS로 실측 검증
- **색상 대비 WCAG AA 검증**(JS로 실제 계산): 링크 8.09:1, 본문 13.55:1,
  회색 텍스트 6.41:1 — 전부 4.5:1 기준 통과

**🔍 중요 발견 — teslausb 내장 비디오 뷰어 (루트 `/` 페이지)**:
지금까지 손본 fancyindex는 사실 **대체 수단**이고, 루트 페이지에 **훨씬 완성도
높은 전용 비디오 뷰어**(카메라 6분할, 타임라인 스크러버, RecentClips/SavedClips
선택기)가 이미 내장돼 있음(`teslausb-webui` 패키지). 발견 경위: `filebrowser.js`엔
비디오 관련 코드가 전혀 없어서(파일탐색기 전용) → 루트 `index.html`의 **인라인
스크립트**가 실제 뷰어 로직(`VideoSequence`/`VideoSegment` 클래스, `currentsequence`
전역 등)을 담고 있음을 확인.

**증상**: 우리가 수동 삽입한 샘플 이벤트를 선택하고 재생을 눌러도 비디오 영역이
계속 검게 나옴 (`.mp4` 파일 요청이 아예 발생하지 않음 — 네트워크 탭으로 확인).

**진단 결과 (원인 미확정, 재현 조건은 확인)**:
- 백엔드(`cgi-bin/videolist.sh`)는 우리 샘플 파일을 정확히 나열함 — 정상
- 해당 mp4 파일에 대한 직접 HTTP 요청(`curl`)은 완벽하게 동작함 — `Content-Type:
  video/mp4`, **Range 요청(206 Partial Content)도 정상 지원** — nginx/서빙 문제 아님
- JS의 파일명→카메라 매핑 로직(`addVideo()`)도 코드 리딩상 우리 파일명과
  정확히 일치함 (front/back/left_pillar/right_repeater 등 substring 매칭)
- 그런데도 재생 버튼(`startPlaying()` → `currentsequence.toggleplaypause()`)을
  눌러도 `videoelems[i].src` 할당이 실행되는 지점까지 도달 못 하는 것으로 보임
  (이벤트 선택→세그먼트 select() 사이 어딘가에서 끊김. 실시간 브라우저
  디버깅 없이는 더 정확한 지점 특정 어려움 — 여기서 조사 중단)

**권장 다음 단계**: 이건 우리가 **정상 아카이빙 파이프라인을 거치지 않고
수동으로 파일을 욱여넣은 테스트 데이터**라 그럴 가능성이 있음. **실차 연결
후 진짜 이벤트가 정상적으로(심볼릭링크·스냅샷 경유) 쌓였을 때 이 뷰어가
자동으로 정상 작동하는지 재확인**할 것. 그래도 안 되면 그때 브라우저
개발자도구로 실시간 디버깅 필요 (지금처럼 코드만 읽어서는 한계).

범례: ✅ 확실(실측 확인)  ⚠️ 불확실(실기 확인 필요) — 🎯 최우선 확인 대상

---

## 0. 확정됨 ✅ — dwc3다 (회로도·커널 dts 원본 대조로 확인, 2026-07-01)
Zero 3W의 USB-C **OTG** 포트는 **dwc3 드라이버**를 쓴다(dwc2 아님). 단 USB3
SuperSpeed PHY(combphy0) 배선이 RK3566엔 없어서 **속도는 USB2(480Mbps)로 묶인
dwc3**다 — 그래서 스펙상 "USB2.0 OTG"로 표기된 것. RK3399/RK3588 성공 사례와
**같은 dwc3 드라이버 계열**이라 그 레시피가 거의 그대로 통할 가능성이 큼.
(참고: Zero 3W엔 USB-C가 2개 — **OTG용**과 별도의 **USB3 HOST 전용** 포트가 있음.
우리는 반드시 **OTG 포트**에 연결해야 함, HOST 포트에 꽂으면 절대 인식 안 됨.)

- **정확한 device tree 노드명 확정**: `usb_host0_xhci`
  (`rk356x-base.dtsi`에 정의, `compatible = "rockchip,rk3568-dwc3","snps,dwc3"`,
  보드 dtsi `rk3566-radxa-zero-3.dtsi`에서 `status="okay"`로 이미 활성화됨)
- **`maximum-speed="super-speed"` 트릭은 불필요** — SS PHY 자체가 안 붙어 있어서
  넣어도 의미 없음(RK3399/3588은 PHY가 있어서 필요했던 것, 우리는 다름)
- 출처: `torvalds/linux` 커널 소스의 `rk356x-base.dtsi`, `rk3566-base.dtsi`,
  `rk3568.dtsi`, `rk3566-radxa-zero-3.dtsi` / Radxa 공식 스키매틱
  `radxa_zero_3w_v1110_schematic.pdf` / RK3566 Brief Datasheet(rock-chips.com)

---

## 1. OS 이미지 굽기 ✅
- **Armbian Radxa Zero 3 vendor 6.1.115 커널** (공식 페이지: armbian.com/radxa-zero-3)
  - 다운로드 URL 패턴: `dl.armbian.com/radxa-zero3/` 밑에
    `Armbian_26.x.x_Radxa-zero3_trixie_vendor_6.1.115_minimal.img.xz` 형태
  - **"vendor" 커널 반드시 선택.** "current"(mainline)는 OTG용 overlay가 없어서
    Discussion #1021에서도 실패가 확인됨.
- Raspberry Pi Imager는 못 씀. `dd` 또는 balenaEtcher로 SD카드에 직접 굽기.
  ```bash
  # macOS 예시 (디스크 번호는 diskutil list로 반드시 확인 후. 실수하면 다른 디스크 날림 주의)
  diskutil unmountDisk /dev/diskN
  sudo dd if=Armbian_..._vendor_6.1.115_minimal.img of=/dev/rdiskN bs=4m status=progress
  ```
- eMMC는 우리 안 씀(1GB 모델, eMMC 없음) — SD 부팅만 신경 쓰면 됨.

## 2. 부팅 후 진단 ✅
```bash
uname -r                          # vendor 커널(6.1.115)인지 확인
ls /sys/class/udc/                # 보통 비어있음 — 정상 (아직 peripheral 모드 아님)
dmesg | grep -i dwc                # usb_host0_xhci(OTG)가 dwc3로 잡히는지 확인용(0번 항목 재검증)
find /lib/modules/$(uname -r) -name "g_mass_storage.ko*"   # 모듈 위치 확인용
```

## 3. USB gadget(peripheral) 모드 활성화 — 여기가 진짜 관문

`/boot/dtb/rockchip/overlay/`에 관련 peripheral overlay가 이미 있는지 먼저 확인
(`ls | grep -iE "dwc3|otg|usb-peripheral"`). **없으면** 아래 커스텀 overlay 작성
(RK3399/RK3588 레시피를 우리 확정 노드명 `usb_host0_xhci`에 맞게 조정, SS 트릭은 뺌):
```
/dts-v1/;
/plugin/;
/ {
    fragment@0 {
        target = <&usb_host0_xhci>;
        __overlay__ {
            dr_mode = "peripheral";
        };
    };
};
```
`armbian-add-overlay <파일>.dts`로 컴파일 → `/boot/armbianEnv.txt`에
`overlays=<overlay이름>` 추가 → 재부팅.

⚠️ 주의: RK3399 사례에서 `*-usb-host` 계열 overlay를 동시에 켜면 OTG 포트가 강제로
host 모드가 되어 충돌 — peripheral 관련 overlay 하나만 켤 것. 우리는 애초에
`usb_host0_xhci`(OTG용, `dr_mode` 기본값 "otg")만 건드리고 `usb_host1_xhci`
(HOST 전용 포트)는 손대지 않으면 됨 — 물리적으로 다른 포트라 자동으로 안전.

### 성공 확인 ✅
```bash
ls /sys/class/udc/                              # 컨트롤러 이름(예: fc000000.usb)이 뜨면 성공
cat /sys/class/udc/<이름>/current_speed          # high-speed(=USB2) 뜨면 정상 — super-speed 기대 안 함
cat /sys/class/udc/<이름>/state                  # configured면 정상
```
⚠️ **연결 포트 재확인**: Zero 3W엔 USB-C가 2개. 케이블은 반드시 **OTG라고 표기된
포트**에 꽂을 것 (보통 기판 실크스크린에 "OTG" 표시, 또는 문서의 포트 배치도 참고).
HOST 포트에 꽂으면 이 모든 설정을 해도 인식 안 됨.

## 4. teslausb 설치 ✅ (범용 스크립트, UDC 자동 감지 확인됨)
```bash
curl https://raw.githubusercontent.com/marcone/teslausb/main-dev/setup/generic/install.sh | sudo bash
```
- **UDC를 수동으로 지정할 필요 없음.** teslausb의 `run/enable_gadget.sh`가
  `/sys/class/udc/`에서 첫 번째 컨트롤러를 자동으로 읽어 씀
  (`find /sys/class/udc -type l -printf '%P\n' | head -1`).
  → 즉 **3단계에서 `/sys/class/udc/`에 정확히 하나만 뜨면**, 이후는 손댈 것 없음.
- 설치 후 `teslausb_setup_variables.conf` 편집 (archive 방식, wifi 등).
  `pi/teslausb.draft.toml`(neo 전용 포맷)을 이 conf 포맷에 맞게 다시 옮겨야 함.

## 5. Radxa 공식 OS 대안 (Armbian이 막히면) ⚠️
`docs.radxa.com/en/zero/zero3/radxa-os/usbnet`에 `rsetup` → "Set OTG port to
Peripheral mode" 메뉴가 있음(원래는 USB 이더넷 gadget용). Mass storage용 공식
가이드는 없지만, **"OTG를 peripheral로 전환하는 메커니즘 자체는 이미 존재한다"**는
증거라 Armbian 경로가 막히면 Radxa 공식 OS + 이 메뉴를 파보는 게 차선책.

## 6. 물리 연결 주의 ✅
- Zero 3W의 USB-C OTG 포트는 **USB2 속도만 지원**한다는 게 원래 알려진 정보였으나,
  0번 항목에서 확인하듯 실제 컨트롤러가 뭔지가 더 중요한 변수. dwc3+super-speed
  overlay가 먹히면 USB3 속도도 가능(RK3399/3588 사례 기준).
- **데이터 전용 케이블 사용, 가능하면 전원은 별도 공급** — 테슬라가 부팅 중 순간
  전원을 끊는 경우가 있어 리부팅 루프 위험 (teslausb 공통 트러블슈팅 팁).

## 7. 안 뚫릴 경우 폴백
1. Discussion #864/#1021/#883에 우리 진행상황(dmesg 결과 등) 공유해서 피드백 받기
2. dwc2로 확인되면, Radxa 공식 OS의 rsetup peripheral 전환 메커니즘을 riverse 참고
3. `bt/teslausb-radxa-zero`(원조 Radxa Zero, Amlogic 칩용 — 우리 3W엔 안 맞음, 참고만)
4. 그래도 안 되면 원조 Radxa Zero 보드로 교체 (전용 포크 있음, 재고는 별도 확인 필요)

---

## 도착 당일 실행 순서 (셀프체크)
1. [ ] Armbian **vendor 6.1.115** 이미지 다운로드 (dl.armbian.com/radxa-zero3/) → SD에 굽기
2. [ ] 부팅 → `uname -r`로 vendor 커널 확인
3. [ ] **케이블을 "OTG" 표기 포트에 연결**(HOST 포트 아님) — 실크스크린/문서로 확인
4. [ ] `/boot/dtb/rockchip/overlay/`에 기존 peripheral overlay 있는지 확인
   (있으면 커스텀 작성 생략 가능)
5. [ ] 없으면 `usb_host0_xhci` 대상 overlay(위 3단계 예시) 작성·적용 → 재부팅
6. [ ] `ls /sys/class/udc/`에 컨트롤러 뜨는지 확인 ← 여기가 여전히 실기 검증 필요한 지점
   (dwc3 자체는 확정됐지만 "이 조합의 overlay가 실제로 먹히는지"는 처음 시도)
7. [ ] 뜨면 → `curl .../install.sh | sudo bash` (UDC 자동 감지되므로 그대로 진행)
8. [ ] 안 뜨면 → node 경로/overlay 문법 재점검, 그래도 안 되면 폴백 검토
