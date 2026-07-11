package com.example.teslasync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Pi(BLE)가 근접/이탈할 때 시스템이 자동으로 bind/unbind 하는 서비스.
 * onDeviceAppeared = "차에 탔다" → WiFi를 미리 연결해 두고(준비만, 자동 다운로드 없음
 * — PLAN.md 2026-07-05 요구사항) 클립 브라우저로 가는 알림을 띄운다.
 * 백그라운드 제약을 우회하는 정석 경로(CompanionDeviceService).
 */
@RequiresApi(Build.VERSION_CODES.S)
class CarCompanionService : CompanionDeviceService() {

    override fun onDeviceAppeared(address: String) {
        Log.i("TeslaSync", "Pi appeared: $address")
        onCarEntered()
    }

    override fun onDeviceDisappeared(address: String) {
        Log.i("TeslaSync", "Pi disappeared: $address")
        // 진행 중 다운로드는 WiFi 끊김 예외로 DownloadService가 자체 종료. 별도 처리 불필요.
    }

    // API 33+ 오버로드. 기본 구현이 위 String 버전으로 위임되지 않을 수 있어 함께 둠.
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        Log.i("TeslaSync", "Pi appeared (assoc=${associationInfo.id})")
        onCarEntered()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        Log.i("TeslaSync", "Pi disappeared (assoc=${associationInfo.id})")
    }

    private fun onCarEntered() {
        // 백그라운드에선 WifiNetworkSpecifier 승인이 안 될 수 있음 — 실패해도 무해,
        // 브라우저를 열 때 다시 연결을 시도한다.
        PiNet.connect(applicationContext) { ok ->
            Log.i("TeslaSync", "WiFi 사전 연결: ${if (ok) "성공" else "실패(무시)"}")
        }
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CH, "차량 감지", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, ClipBrowserActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        mgr.notify(PRESENCE_NOTIF_ID, Notification.Builder(this, CH)
            .setContentTitle("TeslaSync")
            .setContentText("차량 감지됨 — 탭해서 대시캠 클립 보기")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }

    companion object {
        private const val CH = "presence"
        private const val PRESENCE_NOTIF_ID = 2
    }
}
