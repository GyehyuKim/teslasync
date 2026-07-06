package com.example.teslasync

/**
 * 빌드 전에 실제 값으로 채워야 하는 설정.
 * ponytail: 환경설정 UI는 동작 검증 후에. MVP는 상수 한 곳에서 관리.
 */
object Config {
    // pi/ble_advertise.sh 의 SERVICE_UUID 와 반드시 동일
    const val CAR_SERVICE_UUID = "0000fff0-0000-1000-8000-00805f9b34fb"
    const val CAR_BLE_NAME = "TeslaSync-Pi"

    // teslausb-neo AP 핫스팟의 SSID/암호
    const val PI_SSID = "TeslaSync-Pi"
    const val PI_PASSPHRASE = "change-me"

    // neo REST API 베이스. AP 모드에서 Pi의 게이트웨이 주소.
    // ponytail-calibration: neo의 실제 API 경로/포트를 기기에서 확인해 NeoApi에 반영.
    const val NEO_BASE = "http://192.168.4.1"
}
