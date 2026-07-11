#!/usr/bin/env bash
# TeslaSync clipserver 보드 설치 — repo checkout에서: sudo pi/install_clipserver.sh
# /mutable에 스크립트 설치(읽기전용 루트 대응), systemd 유닛 등록·기동, /healthz 확인.
# 재실행해도 안전(업데이트 겸용).
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

if [[ $EUID -ne 0 ]]; then
  echo "ERROR: root로 실행하세요: sudo pi/install_clipserver.sh" >&2
  exit 1
fi
if [[ ! -d /mutable ]]; then
  echo "ERROR: /mutable 없음 — teslausb 쓰기 파티션이 마운트 안 된 보드?" >&2
  exit 1
fi

# teslausb는 /를 읽기전용으로 유지 — 유닛 파일 설치 동안만 rw, 끝나면 원복
remounted=0
if findmnt -no OPTIONS / | grep -qw ro; then
  mount -o remount,rw /
  remounted=1
fi
install -m 0755 clipserver.py /mutable/clipserver.py
install -m 0644 clipserver.service /etc/systemd/system/clipserver.service
systemctl daemon-reload
systemctl enable --now clipserver.service
systemctl restart clipserver.service  # 재설치 시 새 코드 반영
if [[ $remounted -eq 1 ]]; then
  mount -o remount,ro /
fi

sleep 1
echo "── 셀프체크 (/healthz) ──"
if command -v curl >/dev/null 2>&1; then
  curl -fsS http://127.0.0.1:8080/healthz && echo
else
  echo "curl 없음 — 폰 브라우저로 http://<보드IP>:8080/healthz 를 열어 확인하세요"
fi
echo "다음: 폰에서 http://<보드IP>:8080/api/events 확인 (PLAN.md Phase 1 셀프체크)"
