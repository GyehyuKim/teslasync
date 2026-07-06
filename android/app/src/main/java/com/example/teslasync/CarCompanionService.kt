package com.example.teslasync

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Pi(BLE)가 근접/이탈할 때 시스템이 자동으로 bind/unbind 하는 서비스.
 * onDeviceAppeared = "차에 탔다" → 동기화 포그라운드 서비스를 깨운다.
 * 백그라운드 제약을 우회하는 정석 경로(CompanionDeviceService).
 */
@RequiresApi(Build.VERSION_CODES.S)
class CarCompanionService : CompanionDeviceService() {

    override fun onDeviceAppeared(address: String) {
        Log.i("TeslaSync", "Pi appeared: $address — sync 시작")
        SyncService.start(this)
    }

    override fun onDeviceDisappeared(address: String) {
        Log.i("TeslaSync", "Pi disappeared: $address")
        // 진행 중 동기화는 SyncService가 WiFi 끊김으로 자체 종료. 별도 처리 불필요.
    }

    // API 33+ 오버로드. 기본 구현이 위 String 버전으로 위임되지 않을 수 있어 함께 둠.
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        Log.i("TeslaSync", "Pi appeared (assoc=${associationInfo.id}) — sync 시작")
        SyncService.start(this)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        Log.i("TeslaSync", "Pi disappeared (assoc=${associationInfo.id})")
    }
}
