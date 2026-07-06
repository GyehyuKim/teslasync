#!/usr/bin/env bash
# Pi가 고정 service UUID로 BLE 광고를 띄운다. Android CDM이 이 UUID로 기기를 잡아
# association + 근접 관찰(startObservingDevicePresence)을 건다.
#
# 의존성 0 — BlueZ의 bluetoothctl만 사용. systemd 서비스로 등록해 부팅 시 상시 advertise.
#
# ponytail: bluetoothctl advertise면 충분. 커스텀 GATT 서버는 폰이 데이터를 BLE로 받을
#           때만 필요한데, 데이터는 WiFi로 받으므로 불필요. 광고만 있으면 트리거는 성립.
set -euo pipefail

# Android 앱 Config.kt의 CAR_SERVICE_UUID와 반드시 동일해야 함.
SERVICE_UUID="0000fff0-0000-1000-8000-00805f9b34fb"
LOCAL_NAME="TeslaSync-Pi"

# 광고 등록은 대화형 세션 상태를 요구 → 한 세션에 명령을 밀어넣는다.
bluetoothctl <<EOF
menu advertise
uuids $SERVICE_UUID
name $LOCAL_NAME
discoverable on
back
advertise on
EOF

echo "BLE advertising on: $LOCAL_NAME ($SERVICE_UUID)"
echo "확인: 다른 기기 nRF Connect 앱에서 '$LOCAL_NAME'가 보이면 성공."
# bluetoothctl을 빠져나가도 광고는 유지됨. 상시 유지는 systemd unit으로(README 참고).
