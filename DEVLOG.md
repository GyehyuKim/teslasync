# TeslaSync 개발 일지 — Tesla 대시캠 → 폰 클립 선택 다운로드 프로젝트

Tesla 차량용 대시캠/센트리 영상을, USB 자리에 끼운 SBC(Single Board Computer)가
가로채서 폰 WiFi로 받아볼 수 있게 만드는 DIY 프로젝트의 전체 개발 기록입니다.
처음 아이디어 구상부터 실제 하드웨어에서 USB gadget mode가 동작해 네트워크로
클립을 내려받기까지, 실수와 막힘까지 포함해 그대로 남겼습니다.

## 목차
1. [프로젝트 개요](#프로젝트-개요)
2. [최종 아키텍처](#최종-아키텍처)
3. [사전 준비물](#사전-준비물)
4. [부품 목록 (BOM)](#부품-목록-bom)
5. [부품 구매 여정 — 왜 이렇게 오래 걸렸나](#부품-구매-여정--왜-이렇게-오래-걸렸나)
6. [하드웨어 조립 및 최초 부팅](#하드웨어-조립-및-최초-부팅)
7. [WiFi 설정 — 막힌 지점과 팁](#wifi-설정--막힌-지점과-팁)
8. [원격 개발 환경 — SSH로 갈아타기](#원격-개발-환경--ssh로-갈아타기)
9. [USB gadget mode 활성화 — 최대 난관](#usb-gadget-mode-활성화--최대-난관)
10. [teslausb 설치 — 두 번째 난관 (archive 설정)](#teslausb-설치--두-번째-난관-archive-설정)
11. [발견한 버그와 수정](#발견한-버그와-수정)
12. [실제 다운로드 파이프라인 검증](#실제-다운로드-파이프라인-검증)
13. [UI/UX 개선](#uiux-개선)
14. [알려진 이슈 / 다음 단계](#알려진-이슈--다음-단계)
15. [교훈 정리](#교훈-정리)

---

**실제 보드 수령일**: 2026-07-05 (당초 예정이던 7/10보다 앞당겨 도착)

## 프로젝트 개요

**목표**: Tesla 대시캠/센트리 영상 중 원하는 클립을, 원할 때 골라서 Android 폰으로
가져옵니다. 자동으로 전체를 동기화하는 게 아니라 "이벤트 목록 보고 → 카메라 하나
골라서 → 그 클립만 다운로드" 방식.

**핵심 설계 원칙**: 블루투스(BLE)는 "차에 탔다"를 감지하는 트리거로만 쓰고,
실제 대용량 영상 전송은 WiFi로 합니다. 블루투스 실효 대역폭(1~2 Mbps)으로는
1080p 클립(분당 수십 MB) 전송이 사실상 불가능하기 때문.

**작동 원리**: Tesla는 대시캠 저장용 USB 드라이브가 꽂혀 있다고 인식하면 거기에
영상을 씁니다. SBC(Radxa Zero 3W)가 **"USB gadget mode"**라는 기능으로 자기
자신을 가짜 USB 드라이브처럼 꾸며서 Tesla의 USB 포트에 꽂으면, Tesla는 진짜
USB 메모리인 줄 알고 그대로 녹화합니다. SBC 안의 microSD 카드에 있는 큰
파일(가짜 드라이브 이미지)이 그 저장 공간 역할을 합니다.

---

## 최종 아키텍처

```
Tesla USB 포트
   │  (USB-A to USB-C 케이블, 데이터+전원 겸용)
   │
Radxa Zero 3W (OTG 포트에 연결)
   │
   ├─ microSD 카드 안의 큰 파일(cam_disk.bin, ~90GB)
   │    = USB gadget mode로 Tesla에게 "나 USB 드라이브야"라고 보여주는 가짜 드라이브
   │    Tesla가 여기에 TeslaCam/{SavedClips,SentryClips,RecentClips} 구조로 씀
   │
   ├─ WiFi 핫스팟 (또는 폰 핫스팟에 접속) — 인터넷 없이 로컬 통신
   │    (단, 개발/설치 단계 — Armbian 첫 설정의 무선 인터넷 연결, teslausb curl
   │    install.sh, apt-get install samba 등 — 에선 폰 핫스팟이 셀룰러 데이터로
   │    인터넷을 공유해야 함. 실차 배포 후 실사용 단계에서만 인터넷이 필요 없음)
   │
   ├─ teslausb (marcone/teslausb) — USB gadget 설정 + 클립 아카이빙 데몬
   │    └─ 로컬 Samba(SMB) 서버로 "이미 완성된" 클립만 안전하게 복사
   │       (실시간 쓰는 중인 파일과 섞이지 않게)
   │
   └─ nginx + fancyindex — 웹 브라우저로 클립 목록 보고 다운로드
        (+ teslausb 내장 비디오 뷰어, 카메라 6분할 재생 지원 — 발견했지만 아직 미해결 버그 있음)

폰 (또는 PC) — 같은 WiFi에 접속해서 SMB/웹으로 클립 다운로드
```

CDM 페어링 등 기본 앱 골격은 이미 빌드·실기 테스트 완료(AppCompatActivity→
ComponentActivity 전환, .mp4 필터링 버그 수정 등), 다만 자동 풀싱크 SyncService
로직을 새 클립 브라우저 UX와 파일서버 API에 맞춰 재작성해야 함. 지금은 "폰
브라우저로 SMB/웹 UI 접속"이 임시 대체 수단.

---

## 사전 준비물

개발 PC에 미리 준비해두면 좋은 것:
- **소프트웨어**: balenaEtcher(SD카드 굽기), SSH 클라이언트(터미널 기본 내장
  `ssh`로 충분), `sshpass`(자동화 스크립트용 비밀번호 자동 입력)
- **하드웨어**: microSD 카드 리더(카드를 PC에 연결해 굽기 위함, PC에 내장 슬롯이
  없으면 별도 구매 필요)

---

## 부품 목록 (BOM)

| 부품 | 실제 구매한 것 | 비고 |
|---|---|---|
| **SBC 보드** | Radxa Zero 3W (1GB RAM, eMMC 없음) | 아래 "구매 여정" 참고 — 원래 Raspberry Pi Zero 2 W를 사려다 품귀로 포기 |
| **microSD 카드** | SanDisk 블랙박스 전용 High Endurance 128GB | **일반 EVO/Ultra 안 됨** — 24시간 녹화 견디는 고내구성 카드 필수 |
| **USB 케이블 (본체용)** | USB-A ↔ USB-C 데이터 케이블 (미구매, 집 케이블 확인 예정) | Radxa는 USB-C 포트라 Pi용 micro-5핀 케이블과 다름. **"충전 전용"이 아니라 데이터 되는 케이블**인지 꼭 확인 |
| **모니터 연결용** | micro-HDMI → HDMI 젠더/케이블 | Radxa Zero 3W엔 초소형 micro-HDMI 포트가 있음(표기가 작아서 못 찾기 쉬움), 1080p60 지원 |
| **키보드** | 아무 USB 키보드 (허브 경유 가능) | 초기 설정(WiFi 연결 등)용, 이후엔 SSH로 대체 가능 |
| **모니터** | 아무 HDMI 입력 모니터/TV | 초기 설정용, 이후엔 SSH로 대체 가능 |
| **전원** | USB-C 케이블로 전원 공급 (개발 중엔 PC/맥에서, 실사용 시엔 Tesla USB 포트에서) | 최종 설치 시 Tesla가 전원+데이터를 케이블 하나로 공급 |

**포트 구분 주의**: Radxa Zero 3W엔 USB-C가 **두 개** 있습니다 —
- **OTG 포트** (USB2 전용, 전원+데이터 겸용) — Tesla/개발 PC에 연결하는 쪽. **최종 사용 시 반드시 이 포트를 Tesla에 연결**
- **HOST 포트** (USB3 지원) — 키보드/마우스, 또는 (테스트용으로) 외장 USB 드라이브를 꽂는 쪽

두 포트 모두 라벨이 실물에 잘 안 보여서, 처음엔 확실한 전원(충전기)을 하나씩
꽂아보고 **부팅이 되는 쪽이 OTG**라고 판별했습니다.

---

## 부품 구매 여정 — 왜 이렇게 오래 걸렸나

원래 계획은 Raspberry Pi Zero 2 W + `teslausb-neo`(Pi 전용, USB 에뮬·아카이빙·
웹UI·AP핫스팟까지 다 갖춘 검증된 프로젝트)였습니다. 이론상 설정만 하면 되는
가장 쉬운 길이었는데, **Pi Zero 2 W가 전 세계적으로 품귀**였습니다.

- 국내 전자부품몰(디바이스마트·엘레파츠·메카솔루션) 전부 품절
- 해외 공인 리셀러(The Pi Hut, 영국)도 품절
- 쿠팡/AliExpress엔 있었지만 **"'구형 Zero' 가격으로 미끼를 걸고, 실제 옵션에서
  'Zero 2 W'를 선택하면 가격이 2~3배로 뛰는 함정"**이 반복됨
- **쿠팡 주문은 셀러가 재고를 못 구해 자동 취소됨** → 신뢰 불가

대안으로 **Radxa Zero 3W**(Rockchip RK3566, WiFi6+BT5.4)로 전환했습니다. 이유:
- **저전력** — 여름철 주차된 차 실내(60~70℃)에서 Pi 4 같은 고전력 보드는 발열로
  스로틀/셧다운 위험. Zero 폼팩터 계열이 차량용엔 적합
- **실제 재고 있음** (Pi Zero 2W와 달리)

단, Radxa Zero 3W는 teslausb 커뮤니티에서 **완주 성공 사례가 없는 미개척
경로**였습니다 (마이그레이션 참고 지점 참고 — csgordon이라는 사람이 시도했지만
USB gadget mode 단계에서 막힌 채 끝난 기록만 있었음). "불가능하진 않지만
까다로울 것"이란 걸 알고도 진행했고, 아래 "USB gadget mode 활성화" 절에서
실제로 그 난관을 넘습니다.

**교훈**: 이 카테고리(초소형 저전력 SBC) 자체가 한국 재고가 만성적으로 부실합니다.
품귀 상품을 "이번엔 되겠지" 하고 여러 쇼핑몰 돌아다니는 것보다, **재고 있는
대체 보드로 빠르게 전환하는 게 결과적으로 더 빨랐습니다.**

---

## 하드웨어 조립 및 최초 부팅

### OS 이미지 준비
- **Armbian (vendor 커널) 필수** — mainline 커널은 USB gadget mode에 필요한
  overlay가 없어서 반드시 vendor 6.1.115 커널 이미지를 사용해야 함
- 다운로드: `dl.armbian.com/radxa-zero3/` 에서
  `Armbian_26.2.1_Radxa-zero3_trixie_vendor_6.1.115_minimal.img.xz`
- **Raspberry Pi Imager로는 못 구움** — balenaEtcher 사용 (`.img.xz` 압축 해제
  없이 그대로 구울 수 있어서 실수 여지가 적음)

### SD카드 굽기 실수담 (그대로 남김 — 반복하지 않도록)
1. **"이미징"과 "플래싱"을 다른 단계로 착각** — Etcher에서 "Flash from file"로
   파일만 선택한 걸 다 됐다고 착각하고 그냥 카드를 뽑음 → GPT 헤더만 살고
   실제 파티션 내용이 없는 반쪽짜리 굽기가 됨. **반드시 "Flash!" 버튼을 누르고,
   "Flashing..." → "Validating..." 두 단계가 끝나 초록 체크마크(Flash Complete!)가
   뜰 때까지 기다려야 함.**
2. **굽고 나서 Windows 탐색기로 드라이브를 열어봄** — Windows가 ext4 파티션을
   못 읽어서 "포맷하시겠습니까?" 팝업을 띄울 수 있고, 이게 파티션을 깨뜨리는
   원인이 됐을 가능성이 큼. **구운 직후엔 절대 탐색기에서 열어보지 말고, Etcher
   완료 확인 후 바로 안전 제거.**
3. **전원이 켜진 채로 SD카드를 교체함(핫스왑)** — 원래 안전한 방법이 아니지만
   재부팅(`reboot` 명령)으로 복구됨. 가능하면 전원 내리고 카드 교체할 것.

이 세 가지 실수 때문에 부팅이 몇 번이나 실패했고 (`initramfs` 셸로 떨어짐,
`ALERT! UUID=... does not exist`), 결국 SD카드를 처음부터 완전히 다시 구워서
해결했습니다.

### 부팅 확인
전원(OTG 포트) + micro-HDMI(모니터) + HOST 포트(키보드) 연결 후 전원을 넣으면
Armbian 부팅 로그가 뜹니다. 로그인 화면에서는 계정 `root`, 기본 비밀번호
`1234`(Armbian 기본값)로 로그인하면 되고, 로그인 직후 바로 새 비밀번호로
바꾸라는 강제 프롬프트가 뜨면서 첫 설정 마법사가 시작됩니다:

```
root 계정 비밀번호 설정
→ 사용자 계정 생성 (예: hyu)
→ "무선으로 인터넷 연결할까요? [Y/n]" → Y
→ WiFi 목록에서 선택, 비밀번호 입력
→ 타임존/로케일 확인
```

---

## WiFi 설정 — 막힌 지점과 팁

### 문제: 폰 핫스팟이 목록에 안 뜬다
Armbian 첫 설정 마법사의 WiFi 스캔에서 **폰 핫스팟이 계속 안 보였습니다.**
원인 후보를 하나씩 소거:
- 핫스팟이 꺼져 있었나? → 아니었음, 켜져 있었음
- 5GHz 전용이라 안 잡히나? → 이미 2.4GHz로 설정돼 있었음
- 숨김 네트워크인가? → 아니었음 (브로드캐스트 중이었음)

### 진짜 원인: WPA3 + 관리 프레임 보호(PMF)
폰 핫스팟 보안 설정을 확인해보니 **`WPA3-Personal` + 관리 프레임 보호(PMF)**가
켜져 있었습니다. 삼성 핫스팟 설정 화면에 이미 힌트가 있었습니다:

> "관리 프레임 보호 — 모바일 핫스팟 보안을 강화합니다. **오래된 기기에서
> 연결하지 못할 수 있습니다.**"

**임베디드 보드의 WiFi 스택(특히 vendor 커널의 오래된/최소 wpa_supplicant)은
WPA3-SAE나 PMF를 지원하지 못하는 경우가 흔합니다.** "Wrong password or weak
signal"이라는 에러 메시지는 사실 비밀번호 문제가 아니라 **보안 프로토콜
자체를 못 알아듣는 것**이었습니다.

**해결**: 폰 핫스팟 보안을 **WPA2-Personal** 또는 **WPA2/WPA3 혼합(전환)
모드**로 변경. 혼합 모드는 최신 기기는 WPA3로, 구형/임베디드 기기는 WPA2로
알아서 붙기 때문에 일상 사용에 지장 없이 호환성 문제를 해결합니다.

> **팁**: 임베디드 리눅스 보드를 폰 핫스팟에 연결해야 하는 프로젝트를 한다면,
> 처음부터 핫스팟을 WPA2 또는 혼합 모드로 설정해두세요. WPA3 전용은 최신
> 스마트폰끼리는 문제없지만 오래된 IoT/개발 보드에서 흔히 막힙니다.

### 부수적 문제: armbian-config의 WiFi 스캔이 재스캔을 안 함
설정 화면에서 "Enter a number of SSID"가 반복되며 목록이 안 바뀌는 문제도
있었습니다 (첫 스캔 스냅샷을 재사용하는 것으로 추정). `Ctrl+C`/`Ctrl+D`로도
못 빠져나오면, 이 단계를 강제로 스킵하고 로그인한 뒤 `armbian-config` →
Network 메뉴에서 다시 시도하거나, `nmtui`(설치돼 있다면) 같은 다른 도구로
재시도하는 게 낫습니다. minimal 이미지는 NetworkManager가 없어서 `nmtui`
자체가 없을 수 있으니, 그 경우 `armbian-config`의 Network 메뉴만 남습니다.

---

## 원격 개발 환경 — SSH로 갈아타기

### 발견: 개발 PC를 같은 핫스팟에 연결하면 SSH로 직접 조작 가능
보드가 폰 핫스팟에 붙은 뒤, 개발에 쓰던 맥(Mac mini)도 **같은 폰 핫스팟**에
연결돼 있다는 걸 우연히 알게 됐습니다. 둘 다 같은 WiFi의 "손님"이라 서로
`ping`/`ssh`가 바로 됐습니다:

```bash
ping 10.240.23.72              # 로그인 화면 MOTD에 뜨는 보드의 IP
ssh root@10.240.23.72           # 비밀번호 입력하면 바로 셸 접속
```

> **팁**: 임베디드 보드를 모니터+키보드로 설정하는 건 사진 찍어서 옮겨
> 적거나 손으로 타이핑하는 과정이 매우 느리고 오탈자가 잦습니다. **개발
> PC와 보드를 같은 네트워크(폰 핫스팟 등)에 두면, SSH로 훨씬 빠르고 정확하게
> 작업할 수 있습니다.** 특히 긴 로그를 봐야 하거나 파일을 여러 번 고쳐야
> 할 때 차이가 큽니다.

### SSH 자동화 시 주의점 (실수 기록)
- **`sshpass`로 비밀번호를 자동 입력**할 때, `root` 계정으로 바로 붙는 게
  `sudo` 비밀번호 프롬프트 꼬임을 피하는 가장 간단한 방법이었습니다
  (`sudo`는 pty 할당이 필요해서 단순 SSH 파이프에서 문제가 생길 수 있음).
- **`pkill -f "패턴"`을 조심하세요** — 그 패턴 문자열이 **지금 실행 중인
  명령 자체의 텍스트에도 포함**되면, 자기 자신을 죽여서 SSH 세션이 뚝
  끊깁니다. 실제로 `pkill -9 -f "rc.local"`을 실행하다가 그 명령을 담고
  있던 셸 자체가 죽어서 세션이 끊긴 적이 있습니다. **PID 숫자로 특정해서
  죽이거나, `ps aux | grep '[r]c.local'`처럼 대괄호 트릭으로 자기 자신
  매칭을 피하세요.**
- **탭 완성을 적극 활용**하세요 — 파일 경로를 손으로 여러 번 옮겨 적다
  보면 `teslausb_setup_variables.conf`를 `tesla_setup_variables.conf`,
  `teslaush_setpup_variables.conf` 등으로 계속 틀리게 됩니다.

---

## USB gadget mode 활성화 — 최대 난관

이 프로젝트에서 **가장 불확실했던 지점**입니다. 커뮤니티에 완주 성공 사례가
없었고, RK3566 SoC가 실제로 어떤 USB 컨트롤러(dwc2 vs dwc3)를 쓰는지도
확실치 않았습니다. Radxa Zero 3W 자체는 완주 사례가 없었지만, 같은 dwc3
드라이버 계열인 RK3399(Rock Pi 4C+)·RK3588(Rock 5C Lite)에는 검증된 성공
레시피가 있어 이를 참고해 진행했습니다.

### 1단계: 하드웨어 스펙 확정 (실기 없이, 자료만으로 가능했음)
Radxa Zero 3W 공식 스키매틱(회로도)과 리눅스 커널 소스(`rk356x-base.dtsi`,
`rk3566-base.dtsi`, `rk3568.dtsi`, `rk3566-radxa-zero-3.dtsi`)를 대조해서
확인:
- OTG 포트는 **dwc3 드라이버**를 씀 (dwc2 아님)
- 단, RK3566엔 USB3 SuperSpeed PHY 배선이 없어서 **속도는 USB2로 묶인 dwc3**
  (그래서 공식 스펙에 "USB2.0 OTG"로 표기됨)
- 정확한 디바이스트리 노드명까지 확인: `usb_host0_xhci`

이건 실기가 없어도 **공개된 1차 자료(회로도·커널 소스)만으로 확정 가능한
정적 사실**이었습니다. "하드웨어라서 미리 알 수 없다"고 성급히 포기하지
않고 더 깊이 판 게 나중에 시간을 크게 아꼈습니다.

### 2단계: 실기에서 확인
```bash
uname -r                          # 6.1.115-vendor-rk35xx (vendor 커널 확인)
ls /sys/class/udc/                 # 보통 비어있음 (아직 peripheral 모드 아님)
ls /boot/dtb/rockchip/overlay/ | grep dwc
```
**놀랍게도 필요한 overlay 파일(`rk3568-dwc3-peripheral.dtbo`)이 이미
존재**했습니다. RK3566/RK3568이 디바이스트리 베이스를 공유해서, RK3568용으로
묶인 overlay가 우리 보드에도 그대로 먹혔습니다. 직접 overlay를 작성할 필요가
없었습니다.

### 3단계: overlay 적용
```bash
# 주의: 우리 보드엔 overlays= 줄이 아예 없어서 바로 추가했지만, 이미 다른 overlay를
# 쓰고 있는 보드라면 먼저 기존 줄이 있는지 확인할 것:
grep -n "^overlays=" /boot/armbianEnv.txt

# /boot/armbianEnv.txt에 overlays= 줄이 아예 없었음 → 새로 추가
echo "overlays=rk3568-dwc3-peripheral" >> /boot/armbianEnv.txt
reboot
```

### 4단계: 재부팅 후 확인 — 성공
```bash
ls /sys/class/udc/
# → fcc00000.usb
```
**드디어 성공.** 이게 뜨는 순간, USB gadget mode를 담당하는 컨트롤러가
실제로 활성화됐다는 뜻입니다. 재부팅 후 재확인해도 안정적으로 유지됨을
확인했습니다.

---

## teslausb 설치 — 두 번째 난관 (archive 설정)

전체 과정은 실제 겪은 시간순으로 아래 5단계입니다.

### 1단계: `curl install.sh` 최초 실행과 프롬프트 응답
```bash
curl https://raw.githubusercontent.com/marcone/teslausb/main-dev/setup/generic/install.sh | sudo bash
```
이 스크립트는 대화형으로 진행되며, 설정 파일(`/root/teslausb_setup_variables.conf`
— 처음엔 `/teslausb/teslausb_setup_variables.conf`에 있다가 설치 과정에서
`/root/`로 재배치됨. `/teslausb`는 사실 `/boot`의 심볼릭 링크)을 채운 뒤
`sudo -i` → `/etc/rc.local`을 실행해 이어갑니다.

**실행 중 마주친 프롬프트 (순서대로)**:
1. 호스트명 입력 → 기본값 유지
2. WiFi를 AP(핫스팟) 모드로 쓸지 station(클라이언트) 모드로 쓸지 → **station**
   (이미 폰 핫스팟에 붙어 있으므로 자체 AP 불필요). **참고**: 이 station 선택이
   (a) 개발 편의상 임시로 고른 것이라 실차 배포 전 AP 모드로 되돌릴지, 아니면
   (b) PLAN.md(47행)·PROGRESS.md(27·172·177행)에 적힌 "보드 자체 WiFi AP 핫스팟"
   확정 아키텍처 자체가 station 방식으로 바뀐 것인지는 이 시점에 명확히 정리되지
   않았음 — 저 문서들과의 이 불일치를 여기 명시적으로 남겨둡니다.
3. 알림 서비스(pushover 등) 설정 여부 → **스킵** (지금 범위 밖)
4. archive 서버 주소 입력 → 처음엔 플레이스홀더로 진행 시도했다가 3단계
   "막힌 지점" 때문에 결국 `127.0.0.1`로 재입력

### 2단계: conf 파일 1차 편집 (`CAM_SIZE` 등)
설정 파일(`teslausb_setup_variables.conf`) 편집은 `vi`로 진행했습니다.

주요 설정값 (우리 프로젝트는 NAS 자동 백업 없이, 나중에 폰 앱으로 직접
골라 받는 설계라 최소한만 채움):
```bash
export CAM_SIZE=90G   # 기본 추천값(40G)보다 넉넉하게 — 자동 정리가 없으니 여유 필요
# DATA_DRIVE는 설정 안 함 (SD카드 하나로 부팅+저장 둘 다 함)
```

### 3단계: 막힌 지점 — `STOP: archive server unreachable`
```
STOP: The archive server your_archive_name_or_ip is unreachable.
```
우리는 "NAS 자동 백업 없이 나중에 골라서 받기" 설계라 archive 설정을 아예
안 건드리려 했는데, **teslausb의 generic 설치 스크립트는 archive 서버
설정 없이는 설치가 완료되지 않았습니다** (기본값이 CIFS로 고정돼 있고,
플레이스홀더 값(`your_archive_name_or_ip`)이 그대로 있으면 도달 가능성
체크에서 멈춤).

### 4단계: samba 설치 및 `[archive]` 섹션 vi 편집(smb.conf) + conf 재편집
**해결책: 보드 자기 자신에게 로컬 Samba(SMB) 서버를 띄워서 "자기 자신을
archive 서버로 지정"**:
```bash
apt-get -y install samba
mkdir -p /backingfiles/archive_share
useradd -M -s /usr/sbin/nologin archiveuser
smbpasswd -a archiveuser
```
`/etc/samba/smb.conf`에 아래 `[archive]` 섹션을 추가(`vi`로 편집):
```ini
[archive]
   path = /backingfiles/archive_share
   valid users = archiveuser
   writable = yes
   create mask = 0644
   directory mask = 0755
   guest ok = no
```
문법 검증 후 재시작:
```bash
testparm            # 에러 없이 [archive] 섹션이 파싱되는지 확인
systemctl restart smbd
```
그리고 `teslausb_setup_variables.conf`를 다시 `vi`로 열어 archive 관련 값을 채움:
```bash
export ARCHIVE_SERVER=127.0.0.1
export SHARE_NAME="archive"
export SHARE_USER=archiveuser
export SHARE_PASSWORD="<smbpasswd -a archiveuser에서 설정한 실제 비밀번호>"
```

### 5단계: `sudo -i && /etc/rc.local`로 설치 재개
archive 설정을 마친 뒤 설치를 이어가기 위해 실행한 명령(스크립트 재실행이나
reboot가 아니라, 스크립트가 원래 안내한 rc.local 재실행 방식 그대로):
```bash
sudo -i
/etc/rc.local
```

이러면 (1) 필수 체크를 통과하고, (2) **나중에 우리 앱이 읽을 "이미 완성된
클립만 안전하게 복사된 폴더"가 부산물로 생깁니다** — teslausb의 스냅샷
기반 아카이빙 로직이 "차가 아직 쓰는 중인 파일"과 "다 쓴 파일"을 구분해서
안전하게 복사해주는 기능을 그대로 활용하는 셈입니다.

> **몰랐던 것 — 스냅샷 기반 아카이빙**: teslausb는 라이브 파일을 바로
> archive하지 않고, 주기적으로 **스냅샷**(`/mutable/TeslaCam`, 실제로는
> `/backingfiles/snapshots/snap-XXXXXX/`를 가리키는 심볼릭 링크)을 찍은 뒤
> 그 스냅샷에서 새 파일을 찾아 복사합니다. 그래서 우리가 테스트용으로 파일을
> CAM 파티션에 직접 복사해 넣었을 때, **스냅샷을 따로 찍어주지 않으면
> archive 프로세스가 그 파일의 존재 자체를 몰랐습니다** (`/root/bin/make_snapshot.sh`를
> 수동으로 돌려서 해결).

---

## 발견한 버그와 수정

### 버그 1: smbd가 재부팅 후 항상 크래시함
**증상**: teslausb 설치 마지막 단계(`make-root-fs-readonly.sh`)가 SD카드
보호를 위해 루트파일시스템을 읽기전용으로 바꾸는데, 그 상태로 재부팅하면
Samba가 매번 죽어서 다운로드 경로 자체가 막힘.

**원인** (`/var/log/samba/log.smbd` 확인):
```
Failed to open /var/lib/samba/private/secrets.tdb
ERROR: failed to setup profiling
```
`/var/lib/samba`, `/var/cache/samba`가 읽기전용 대상인 루트파일시스템에
있어서 쓰기 실패.

**수정**: 이 두 디렉토리를 teslausb가 이미 "항상 쓰기 가능하게 유지하도록
설계한" `/mutable` 파티션으로 옮기고 바인드마운트:
```bash
systemctl stop smbd nmbd
mkdir -p /mutable/samba-lib /mutable/samba-cache
cp -a /var/lib/samba/. /mutable/samba-lib/
cp -a /var/cache/samba/. /mutable/samba-cache/
rm -rf /var/lib/samba/* /var/cache/samba/*
# /etc/fstab에 아래 두 줄 추가 (재부팅해도 자동 적용). vi로 직접 추가하거나:
cat >> /etc/fstab <<'EOF'
/mutable/samba-lib /var/lib/samba none bind 0 0
/mutable/samba-cache /var/cache/samba none bind 0 0
EOF
mount /var/lib/samba && mount /var/cache/samba
```
**검증**: `mount / -o remount,ro` 후 `systemctl restart smbd` → 정상 기동
확인 (재부팅 없이 읽기전용 상태를 시뮬레이션해서 검증).

### 버그 2: 실제 Tesla USB 드라이브를 언마운트 없이 뽑아서 생긴 stale mount
샘플 데이터 테스트용으로 실제 Tesla USB 드라이브를 보드의 HOST 포트에
마운트해서 썼는데, 나중에 `umount` 없이 그냥 물리적으로 뽑아버려서
`/mnt` 마운트포인트 자체가 I/O 에러를 내는 상태가 됐습니다. 원인 파악
후 관련 프로세스를 정리하니 자연히 해소됐습니다 (`umount -l`/`-f`로
강제 해제하는 방법도 있음). **USB 드라이브는 항상 `umount` 하고 뽑을 것.**

---

## 실제 다운로드 파이프라인 검증

전체 파이프라인이 실제로 작동하는지, 실차 없이 최대한 검증했습니다:

1. **USB gadget이 진짜 USB 드라이브로 인식되는지**: OTG 케이블을 개발 PC(맥)에
   연결한 채로 맥의 `diskutil list`를 확인 → **"CAM"이라는 96.6GB
   `Windows_FAT_32` 외장 드라이브로 정상 인식**. 마운트해서 열어보니
   `TeslaCam/`, `TeslaTrackMode/` 폴더가 teslausb에 의해 이미 준비돼 있음
   (Tesla가 기대하는 정확한 폴더 구조).

2. **다운로드가 실제로 되는지**: 실제 Tesla USB 드라이브에서 진짜 샘플
   클립(6카메라 x 2분량, 약 530MB — PROGRESS.md에 기록된 이 차량의 실측
   비트레이트(6캠 합쳐 분당 약 266MB)로 재계산한 수치. 최초 기록했던
   "222MB"는 이 계산과 맞지 않아 정정함)을 하나 가져와서 CAM 파티션에 넣고,
   스냅샷 → archive 프로세스를 강제로 태워서 로컬 Samba 공유까지
   전달되는 것을 확인:
   ```bash
   /root/bin/make_snapshot.sh     # 스냅샷을 수동으로 찍어 새 파일을 archive 대상에 포함
   /root/bin/archiveloop           # 평소 주기 대기 없이 archive 프로세스를 강제로 1회 실행
   ```
   그 뒤 **개발 PC에서 SMB로 접속해 실제 파일을
   다운로드**해서 `event.json` 내용(실제 도로명, 좌표 등)까지 정상
   읽히는 것을 확인했습니다.

**아직 검증 안 된 것**: 실제 Tesla 차량에 연결했을 때도 똑같이 인식되는지
(이론상 될 것으로 보이나 실차 테스트는 별도로 진행 예정).

---

## UI/UX 개선

teslausb 기본 웹 UI(nginx + fancyindex 모듈 기반 파일 목록)가 기능은
하지만 스타일링이 부족해서, 다크테마 CSS로 개선했습니다.

**적용한 것**:
- 다크테마 (`/var/www/html/fancyindex.css`, `fancyindex_css_href`로 연결됨)
- `fancyindex_exact_size off;` (nginx 설정) — 파일 크기를 원시 바이트 대신
  `24.3 MiB` 같은 읽기 쉬운 단위로 표시

**재현용 상세 (실제 배포된 CSS 원본 · nginx 설정 위치 · 적용 명령)**: 아래는
보드에서 직접 가져온 `/var/www/html/fancyindex.css`의 실제 전체 내용입니다:
```css
/* TeslaSync 클립 브라우저 — fancyindex 테마
   목표: 폰 브라우저에서 깔끔하게 보이는 최소한의 개선. 전용 앱 전에 쓸 임시 UI. */

* { box-sizing: border-box; }

body, html {
  margin: 0;
  padding: 0;
  background: #1a1d21;
  color: #e6e6e6;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  font-size: 15px;
  line-height: 1.4;
}

h1 {
  background: #14161a;
  color: #fff;
  font-size: 1.15em;
  font-weight: 600;
  padding: 16px 20px;
  margin: 0;
  border-bottom: 1px solid #2a2e33;
  word-break: break-all;
}

#list {
  width: 100%;
  border-collapse: collapse;
  background: #1a1d21;
}

th {
  background: #202329;
  color: #9aa0a6;
  text-align: left;
  padding: 10px 16px;
  font-weight: 600;
  font-size: 0.85em;
  text-transform: uppercase;
  letter-spacing: 0.03em;
  border-bottom: 1px solid #2a2e33;
  position: sticky;
  top: 0;
}

td {
  padding: 12px 16px;
  border-bottom: 1px solid #24272c;
  font-weight: normal;
}

/* 파일명 셀: 패딩을 <a>로 옮겨서 클릭 가능 영역이 행 전체 높이를 채우게 함
   (안 그러면 <a>가 텍스트 줄 높이만큼만 클릭돼서 44px 터치 기준에 못 미침) */
td:first-child {
  padding: 0;
}
td:first-child a {
  padding: 13px 16px;
}

/* fancyindex 자체 인라인 style의 짝수행 줄무늬(#f4f4f4)를 덮어씀 */
tr:nth-child(even) {
  background: #1a1d21;
}

tr:hover td {
  background: #262b31;
}

/* 파일 크기 열 — 폰에서도 남겨둠(용량 확인이 중요) */
td.size, th.size {
  color: #9aa0a6;
  white-space: nowrap;
  text-align: right;
}

/* 날짜 열은 좁은 화면에서 숨김 */
tr th:nth-child(3),
tr td:nth-child(3) {
  display: none;
}

a {
  color: #7ab7ff;
  text-decoration: none;
  display: block;
}

a:hover {
  color: #a8d0ff;
}

/* 링크 텍스트가 길어도 줄바꿈되게 (mp4 파일명이 길다) */
td:first-child a {
  word-break: break-all;
}

/* 상위 폴더(..) 링크는 살짝 흐리게 */
tr:first-child td a[href$="../"] {
  color: #6b7076;
}

::-webkit-scrollbar { width: 10px; height: 10px; }
::-webkit-scrollbar-track { background: #1a1d21; }
::-webkit-scrollbar-thumb { background: #3a3f45; border-radius: 5px; }

@media (max-width: 480px) {
  h1 { font-size: 1em; padding: 12px 14px; }
  th, td { padding: 10px 12px; font-size: 0.9em; }
}
```
nginx 설정 파일은 `/etc/nginx/sites-available/teslausb.nginx`(teslausb
generic install이 생성, `/etc/nginx/sites-enabled/default`가 이걸 가리키는
심볼릭 링크)이고, 실제 `location` 블록엔 아래 두 줄이 들어있습니다:
```nginx
fancyindex_css_href /fancyindex.css;
fancyindex_exact_size off;
```
참고로 이 `location` 블록엔 `auth_basic_user_file /etc/nginx/.htpasswd;`로
HTTP Basic 인증도 걸려있습니다(teslausb generic install의 기본 설정). 편집
후 적용 명령:
```bash
nginx -t && systemctl reload nginx
```

**실제 스크린샷으로 검증하며 잡은 버그**:
- fancyindex 자체 인라인 스타일의 짝수행 배경(`#f4f4f4`, 밝은 회색)이
  다크테마 위에 그대로 새어나오는 문제 → `tr:nth-child(even)`를 명시적으로
  덮어써서 해결
- **터치 타겟 크기 버그**: 파일명 링크(`<a>`)의 실제 클릭 가능 높이가
  브라우저 JS로 측정해보니 **21px밖에 안 됐음** (행 높이는 46px인데, 링크가
  텍스트 줄 높이만큼만 감싸고 있었음). 모바일 터치 타겟 최소 기준(44px)에
  크게 못 미쳐서, `<td>`의 패딩을 `<a>`로 옮겨 클릭 영역이 행 전체(47px)를
  채우도록 수정
- 색상 대비를 WCAG 공식으로 직접 계산해서 검증: 링크 8.09:1, 본문 13.55:1,
  회색 텍스트 6.41:1 — 전부 AA 기준(4.5:1) 통과

**작업 방식 참고**: 이 프로젝트엔 git 저장소가 없고(원격 임베디드 보드에
SSH로 직접 파일을 편집하는 구조), 일반적인 "로컬 웹앱 + git 커밋" 전제의
디자인 리뷰 도구는 그대로 안 맞았습니다. 그래서 도구의 절차(git 커밋별
수정, 테스트 프레임워크 부트스트랩 등)는 건너뛰고, **체크리스트의 알맹이
(터치 타겟 44px, WCAG 대비, 다크모드 줄무늬 등)만 뽑아서 SSH 직접 편집 +
브라우저 스크린샷/JS 측정으로 검증**하는 방식으로 진행했습니다.

**보너스 발견 — 손 안 댄 것**: 사실 fancyindex 목록은 "대체 수단"이고,
루트 페이지(`http://<보드IP>/`)에 **teslausb 자체 내장 비디오 뷰어**
(카메라 6분할 화면, 타임라인 스크러버, SavedClips/RecentClips 선택기)가
이미 존재한다는 걸 발견했습니다. 다만 수동으로 끼워넣은 테스트 데이터로는
재생이 안 되는 버그가 있어(아래 "알려진 이슈" 참고) 여기엔 아직 손을
안 댔습니다.

---

## 알려진 이슈 / 다음 단계

### 이슈: teslausb 내장 비디오 뷰어가 테스트 데이터로 재생이 안 됨
**증상**: 루트 페이지의 비디오 뷰어에서 이벤트를 선택하고 재생을 눌러도
화면이 계속 검게 나옴 (`.mp4` 파일에 대한 실제 HTTP 요청 자체가 발생하지 않음).

**확인된 것 (원인은 미확정)**:
- 백엔드(`cgi-bin/videolist.sh`)는 파일을 정확히 나열함 — 정상
- 해당 mp4 파일 직접 HTTP 요청은 완벽하게 동작 (`Content-Type: video/mp4`,
  **Range 요청(206 Partial Content)도 정상 지원**) — nginx 문제 아님
- JS의 파일명→카메라 매핑 로직(`addVideo()`)도 코드 상 우리 파일명과
  정확히 일치함
- 그런데도 재생 버튼(`startPlaying()` → `currentsequence.toggleplaypause()`)을
  눌러도 실제 `<video>` 엘리먼트에 `src`가 할당되는 지점까지 도달하지
  못하는 것으로 보임. 이벤트 선택 → 세그먼트 `select()` 사이 어딘가에서
  끊기는데, **실시간 브라우저 디버깅 없이는 정확한 지점을 특정하기 어려워
  조사를 중단**했습니다.

**가설**: 우리가 정상적인 아카이빙 파이프라인(스냅샷 경유)을 거치지 않고
수동으로 파일을 욱여넣은 테스트 데이터라서 그럴 가능성이 있습니다.

**다음 단계**: 실차 연결 후 진짜 이벤트가 정상 경로로 쌓였을 때 이 뷰어가
자동으로 정상 작동하는지 재확인. 그래도 안 되면 브라우저 개발자도구로
실시간 디버깅 필요.

### 저장 공간 여유분과 archive_share 정리 정책
128GB 카드(실사용량 약 119GiB)에서 `CAM_SIZE=90G`를 할당하고 나면, OS/부트
파티션(수 GB) 등을 뺀 **약 20GB 정도가 `archive_share`(및 `/mutable`)가 쓸 수
있는 실질 여유 공간**입니다. archive_share는 teslausb가 자동으로 정리해주지
않으므로, 이 공간이 다 차면 새 클립 archive가 실패할 수 있습니다.

**정리 정책**: 지금은 SSH로 접속해 다운로드가 끝난 클립을 수동으로 삭제
(`rm -rf /backingfiles/archive_share/<이벤트폴더>`)하는 방식으로 운영합니다.
향후 Android 앱이 완성되면, 폰이 클립을 다운로드받은 직후 파일서버 API를
통해 archive_share에서 해당 클립을 자동 삭제하도록 만드는 게 목표입니다.

### 남은 큰 작업
1. **실차 테스트** — 이 OTG 케이블을 실제 Tesla USB 포트에 연결해 진짜
   차량에서도 동일하게 인식되는지 최종 확인
2. **Android 앱 SyncService 재작성** — CDM 페어링 골격은 이미 완료, 자동
   풀싱크 로직을 클립 브라우저 UX + 파일서버 API에 맞춰 다시 짜야 함
   (지금은 폰 브라우저로 수동 접속하는 임시 방식)
3. **뷰어 버그 해결** — 실차 데이터로도 안 되면 디버깅

---

## 교훈 정리

- **품귀 부품은 빨리 포기하고 대체품으로 전환하는 게 낫다.** Pi Zero 2W
  품귀에 매달리다 시간을 많이 썼는데, Radxa Zero 3W로 전환한 뒤가 훨씬
  빨랐음.
- **"하드웨어라서 미리 확인 불가능"이라고 성급히 결론짓지 말 것.** USB
  컨트롤러 종류(dwc2/dwc3)는 회로도·커널 소스라는 공개 1차 자료로 실기
  없이도 확정 가능했음. 처음에 대충 넘겨짚었다가 나중에 제대로 조사해서
  바로잡음.
- **불확실한 버그는 "재현 가능한 최소 조건"까지만 조사하고, 안 되면
  멈춰서 상황을 재평가할 것.** 비디오 뷰어 버그를 코드 리딩만으로 계속
  팠으면 끝없이 시간이 들었을 것 — 백엔드/HTTP/파싱 로직까지 확인해서
  "이 정도면 프론트엔드 상태 관리 버그"라고 좁힌 뒤, 실차 재테스트로
  넘기는 결정을 내림.
- **개발 PC와 대상 보드를 같은 네트워크에 두면 작업 속도가 크게 달라진다.**
  사진 찍어 옮겨 적는 것과 SSH로 직접 붙는 것의 생산성 차이가 큼.
- **`pkill -f`는 자기 자신을 죽일 수 있다.** 패턴이 자기 명령어 텍스트에
  포함되면 안전한 PID 지정이나 대괄호 트릭을 쓸 것.
- **읽기전용 루트파일시스템을 쓰는 임베디드 프로젝트에서, 추가로 설치한
  서비스(Samba 등)는 반드시 쓰기 가능한 영구 파티션에 상태를 저장하도록
  구성해야 함.** 안 그러면 재부팅마다 조용히 죽음.
