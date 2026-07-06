#!/usr/bin/env bash
# Phase 0 최우선 검증: Pi Zero 2 W의 단일 무선칩이 WiFi AP와 BLE 광고를
# 동시에 안정적으로 유지하는가? 불안정하면 전체 설계(Pi 핫스팟)를 폴백으로 바꿔야 하므로
# 코드 짜기 전에 이걸 먼저 통과시킨다.
#
# 사용법: teslausb-neo의 AP 핫스팟 모드를 켠 상태에서 Pi에서 실행.
#   sudo ./test_coexistence.sh [지속초=120]
#
# 판정: 지정 시간 동안 (1) AP 인터페이스 UP 유지 (2) BLE advertising 활성 유지면 PASS.
set -euo pipefail

DURATION="${1:-120}"
WIFI_IF="${WIFI_IF:-wlan0}"     # neo AP가 쓰는 인터페이스
INTERVAL=5

[[ $EUID -eq 0 ]] || { echo "sudo로 실행하세요"; exit 1; }

echo "== 공존 테스트 ${DURATION}s (AP=$WIFI_IF + BLE) =="

# BLE 광고 시작 (없으면)
./ble_advertise.sh >/dev/null 2>&1 || true

ap_up()  { iw dev "$WIFI_IF" info 2>/dev/null | grep -q "type AP"; }
ble_on() { bluetoothctl show 2>/dev/null | grep -q "Powered: yes"; }
# advertising 인스턴스 수 (LEAdvertisingManager). 0이면 광고가 죽은 것.
adv_cnt(){ bluetoothctl show 2>/dev/null | awk -F': ' '/ActiveInstances/{print $2+0}'; }

fail=0
elapsed=0
while (( elapsed < DURATION )); do
  ap=$(ap_up && echo OK || echo DOWN)
  ble=$(ble_on && echo OK || echo DOWN)
  adv=$(adv_cnt); adv=${adv:-0}
  printf "[%3ds] AP=%-4s BLE=%-4s adv_instances=%s\n" "$elapsed" "$ap" "$ble" "$adv"
  [[ "$ap" == OK && "$ble" == OK && "$adv" -ge 1 ]] || { fail=1; echo "  ^ 둘 중 하나 끊김"; }
  sleep "$INTERVAL"; elapsed=$((elapsed+INTERVAL))
done

if [[ $fail -eq 0 ]]; then
  echo "PASS — AP+BLE 공존 안정. 설계대로 Pi 자체 핫스팟 진행."
else
  echo "FAIL — 공존 불안정. 폴백: 폰 핫스팟에 Pi가 접속하는 구조로 전환 (PLAN.md 리스크 항목)."
  exit 1
fi
