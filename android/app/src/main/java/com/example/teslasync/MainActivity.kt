package com.example.teslasync

import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import java.util.regex.Pattern

/**
 * 1회성 페어링 화면: Pi(BLE)와 association을 맺고 근접 관찰을 등록한다.
 * 이후엔 앱을 켤 필요 없이, Pi가 근처에 오면 CarCompanionService가 자동으로 깨어난다.
 */
class MainActivity : ComponentActivity() {

    private val cdm by lazy { getSystemService(CompanionDeviceManager::class.java) }
    private lateinit var status: TextView

    private val associate =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) { res ->
            // 결과의 association 정보는 observePresence()에서 myAssociations로 다시 조회한다.
            if (res.resultCode == RESULT_OK) {
                status.text = "association 완료. 근접 관찰 등록 중…"
                observePresence()
            } else {
                status.text = "association 취소/실패"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { text = "Pi와 페어링이 필요합니다." }
        val btn = Button(this).apply {
            text = "Pi 페어링 시작"
            setOnClickListener { startAssociation() }
        }
        val browse = Button(this).apply {
            text = "클립 브라우저 열기"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, ClipBrowserActivity::class.java))
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(status); addView(btn); addView(browse)
        })
        Permissions.requestAll(this)
    }

    private fun startAssociation() {
        // 크래시 대신 실패 이유를 화면에 표시 — 권한/BT 상태 등 진단용.
        try {
            val filter = BluetoothLeDeviceFilter.Builder()
                .setNamePattern(Pattern.compile(Config.CAR_BLE_NAME))
                .build()
            val request = AssociationRequest.Builder()
                .addDeviceFilter(filter)
                .setSingleDevice(true)
                .build()

            cdm.associate(request, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) {
                    try {
                        associate.launch(IntentSenderRequest.Builder(intentSender).build())
                    } catch (e: Exception) {
                        status.text = "다이얼로그 실행 실패: ${e.message}"
                        Log.e("TeslaSync", "launch 실패", e)
                    }
                }
                override fun onFailure(error: CharSequence?) {
                    status.text = "페어링 실패(onFailure): $error"
                }
            }, null)
            status.text = "주변에서 Pi(BLE) 검색 중… (Pi가 없으면 빈 채로 도는 게 정상)"
        } catch (e: Exception) {
            status.text = "오류: ${e.javaClass.simpleName}: ${e.message}"
            Log.e("TeslaSync", "associate 실패", e)
        }
    }

    /** Pi 근접 시 CarCompanionService가 bind되도록 등록. */
    private fun observePresence() {
        val macs = cdm.myAssociations.mapNotNull { it.deviceMacAddress?.toString() }
        if (macs.isEmpty()) { status.text = "association 없음"; return }
        @Suppress("DEPRECATION")  // API 34의 ObservingDevicePresenceRequest는 추후 전환
        macs.forEach { cdm.startObservingDevicePresence(it) }
        status.text = "준비 완료. 이제 Pi가 근처에 오면 자동으로 동기화됩니다."
        Log.i("TeslaSync", "observing presence for ${macs.size} association(s)")
    }
}
