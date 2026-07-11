package com.example.teslasync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.util.Log

/**
 * Pi 핫스팟 연결 관리. WifiNetworkSpecifier로 SSID를 지정해 붙고, 성공하면
 * 프로세스 전체 트래픽을 그 네트워크에 바인딩한다(인터넷 없는 AP 대응).
 * ponytail: 연결은 앱 프로세스가 사는 동안 유지 — 해제는 프로세스 종료에 맡김.
 */
object PiNet {
    @Volatile private var network: Network? = null
    private var requesting = false
    private val waiters = mutableListOf<(Boolean) -> Unit>()

    /** 연결을 보장하고 결과를 콜백으로. 이미 연결돼 있으면 즉시 true. */
    fun connect(ctx: Context, onResult: (Boolean) -> Unit = {}) {
        val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
        synchronized(this) {
            if (network != null) { onResult(true); return }
            waiters.add(onResult)
            if (requesting) return
            requesting = true
        }

        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(
                WifiNetworkSpecifier.Builder()
                    .setSsid(Config.PI_SSID)
                    .setWpa2Passphrase(Config.PI_PASSPHRASE)
                    .build()
            )
            .build()
        cm.requestNetwork(req, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(net: Network) {
                cm.bindProcessToNetwork(net)
                finish(net, true)
            }

            override fun onUnavailable() {
                cm.unregisterNetworkCallback(this)
                finish(null, false)
            }

            override fun onLost(net: Network) {
                cm.bindProcessToNetwork(null)
                cm.unregisterNetworkCallback(this)
                synchronized(this@PiNet) { network = null; requesting = false }
                Log.i("TeslaSync", "Pi WiFi 끊김")
            }
        })
        Log.i("TeslaSync", "Pi SSID(${Config.PI_SSID}) 접속 요청")
    }

    private fun finish(net: Network?, ok: Boolean) {
        val toCall: List<(Boolean) -> Unit>
        synchronized(this) {
            network = net
            if (!ok) requesting = false
            toCall = waiters.toList()
            waiters.clear()
        }
        Log.i("TeslaSync", "Pi WiFi ${if (ok) "연결됨" else "접속 실패"}")
        toCall.forEach { it(ok) }
    }
}
