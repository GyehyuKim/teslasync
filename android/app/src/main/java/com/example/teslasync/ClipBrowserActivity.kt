package com.example.teslasync

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * 클립 브라우저 (PLAN.md Phase 2): Pi WiFi 접속 → 이벤트 목록 → 이벤트 탭 →
 * 카메라 선택(front 우선) → 그 카메라 클립만 다운로드. 자동 풀싱크 없음.
 */
class ClipBrowserActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var list: ListView
    private var events: List<ClipApi.Event> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            text = "Pi WiFi에 연결 중…"
            setPadding(48, 48, 48, 24)
        }
        list = ListView(this)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(list, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        list.setOnItemClickListener { _, _, pos, _ -> pickCamera(events[pos]) }

        PiNet.connect(this) { ok ->
            if (ok) refresh()
            else runOnUiThread {
                status.text = "Pi WiFi 접속 실패 — 보드가 켜져 있고 근처인지 확인하세요."
            }
        }
    }

    private fun refresh() {
        runOnUiThread { status.text = "이벤트 목록 불러오는 중…" }
        Thread {
            try {
                val fetched = ClipApi().listEvents()
                runOnUiThread { show(fetched) }
            } catch (e: Exception) {
                runOnUiThread { status.text = "목록 실패: ${e.message}" }
            }
        }.start()
    }

    private fun show(fetched: List<ClipApi.Event>) {
        events = fetched
        status.text =
            if (fetched.isEmpty()) "저장된 이벤트가 없습니다."
            else "이벤트 ${fetched.size}개 — 탭해서 카메라 선택"
        list.adapter = object : ArrayAdapter<ClipApi.Event>(
            this, android.R.layout.simple_list_item_2, fetched) {
            override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(pos, convertView, parent)
                val e = getItem(pos)!!
                val typeLabel = if (e.type == "SentryClips") "Sentry" else "Saved"
                v.findViewById<TextView>(android.R.id.text1).text =
                    "${e.timestamp.replace('T', ' ')} · $typeLabel"
                val place = listOf(e.street, e.city).filter { it.isNotBlank() }
                    .joinToString(" ")
                v.findViewById<TextView>(android.R.id.text2).text =
                    listOf(place, e.reason, "클립 ${e.clips.size}개")
                        .filter { it.isNotBlank() }.joinToString(" · ")
                return v
            }
        }
    }

    /** 카메라별로 묶어 고르게 한다. 한 카메라에 분 단위 세그먼트가 여러 개일 수 있음. */
    private fun pickCamera(event: ClipApi.Event) {
        val byCam = event.clips.groupBy { it.camera }
        val cams = byCam.keys.sortedBy {
            CAMERA_ORDER.indexOf(it).let { i -> if (i < 0) CAMERA_ORDER.size else i }
        }
        val done = SyncState.synced(this)
        val labels = cams.map { cam ->
            val clips = byCam.getValue(cam)
            val mb = clips.sumOf { it.size } / (1024 * 1024)
            val mark = if (clips.all { event.relPath(it) in done }) " ✓받음" else ""
            "$cam — ${clips.size}개, ${mb}MB$mark"
        }
        AlertDialog.Builder(this)
            .setTitle("카메라 선택 — ${event.timestamp.replace('T', ' ')}")
            .setItems(labels.toTypedArray()) { _, which ->
                val clips = byCam.getValue(cams[which])
                DownloadService.start(
                    this,
                    ArrayList(clips.map { event.relPath(it) }),
                    clips.map { it.size }.toLongArray(),
                )
                Toast.makeText(
                    this, "${cams[which]} 클립 ${clips.size}개 다운로드 시작",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            .show()
    }

    companion object {
        private val CAMERA_ORDER = listOf(
            "front", "back", "left_repeater", "right_repeater",
            "left_pillar", "right_pillar",
        )
    }
}
