package com.example.teslasync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 포그라운드 서비스: Pi 핫스팟에 접속 → 그 네트워크에 바인딩 → neo에서 새 클립을
 * MediaStore로 다운로드. WiFi가 끊기거나 받을 게 없으면 스스로 종료.
 */
class SyncService : Service() {

    private val running = AtomicBoolean(false)
    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, notif("Pi에 연결 중…"))
        if (running.compareAndSet(false, true)) connectToPi()
        return START_NOT_STICKY
    }

    /** Pi SSID를 명시해 접속 요청. 승인되면 그 네트워크에서만 동기화한다. */
    private fun connectToPi() {
        val spec = WifiNetworkSpecifier.Builder()
            .setSsid(Config.PI_SSID)
            .setWpa2Passphrase(Config.PI_PASSPHRASE)
            .build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(spec)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // 이 프로세스의 트래픽을 Pi 네트워크로 고정 (인터넷 없는 AP라도 사용)
                cm.bindProcessToNetwork(network)
                Thread { runSync() }.start()
            }
            override fun onUnavailable() {
                Log.w("TeslaSync", "Pi SSID 접속 실패"); stopSelf()
            }
        }
        netCallback = cb
        cm.requestNetwork(req, cb)
    }

    private fun runSync() {
        var saved = 0
        try {
            val api = NeoApi()
            val done = SyncState.synced(this)
            // Tesla 이벤트 폴더엔 카메라별 .mp4 외에 event.json(메타데이터)·thumb.png(썸네일)도
            // 섞여있음 — 갤러리엔 영상만 넣는다. json/png를 video/mp4로 잘못 저장하면 깨진다.
            val pending = api.listClips()
                .filter { it.path.endsWith(".mp4", ignoreCase = true) }
                .filter { it.path !in done }
            for ((i, clip) in pending.withIndex()) {
                startForeground(NOTIF_ID, notif("동기화 ${i + 1}/${pending.size}"))
                api.openClip(clip.path).use { input -> saveToGallery(clip.path, input) }
                SyncState.markSynced(this, clip.path)
                saved++
            }
            Log.i("TeslaSync", "동기화 완료: ${saved}개")
        } catch (e: Exception) {
            Log.e("TeslaSync", "동기화 오류", e)
        } finally {
            cm.bindProcessToNetwork(null)
            netCallback?.let { cm.unregisterNetworkCallback(it) }
            running.set(false)
            stopSelf()
        }
    }

    private fun saveToGallery(path: String, input: java.io.InputStream) {
        val name = path.substringAfterLast('/')
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/TeslaSync")
        }
        val uri = contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
        ) ?: return
        contentResolver.openOutputStream(uri)?.use { out -> input.copyTo(out, 1 shl 16) }
    }

    private fun notif(text: String): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CH, "Tesla 동기화", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CH)
            .setContentTitle("TeslaSync")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CH = "sync"
        private const val NOTIF_ID = 1
        fun start(ctx: Context) =
            ctx.startForegroundService(Intent(ctx, SyncService::class.java))
    }
}
