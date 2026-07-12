package com.example.teslasync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 포그라운드 다운로드 서비스: 사용자가 고른 클립(들)만 순차 다운로드해
 * MediaStore(Movies/TeslaSync)에 저장. 옛 SyncService(자동 풀싱크)의 후속 —
 * 분당 266MB 실측 이후 "골라서 단건 다운로드"로 요구사항이 바뀌었다(PLAN.md).
 */
class DownloadService : Service() {

    private data class Job(val relPath: String, val size: Long)

    private val queue = ConcurrentLinkedQueue<Job>()
    private val working = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, notif("다운로드 준비 중…"))
        val paths = intent?.getStringArrayListExtra(EXTRA_PATHS) ?: arrayListOf()
        val sizes = intent?.getLongArrayExtra(EXTRA_SIZES) ?: LongArray(paths.size)
        paths.forEachIndexed { i, p -> queue.add(Job(p, sizes.getOrElse(i) { 0 })) }
        if (working.compareAndSet(false, true)) Thread { drainQueue() }.start()
        return START_NOT_STICKY
    }

    private fun drainQueue() {
        val api = ClipApi()
        var saved = 0
        var failed = 0
        var index = 0
        while (true) {
            val job = queue.poll() ?: break
            index++
            val name = job.relPath.substringAfterLast('/')
            var ok = false
            var attempt = 0
            while (attempt < MAX_ATTEMPTS && !ok) {
                attempt++
                try {
                    api.openFile(job.relPath).use { input ->
                        saveToGallery(name, input, job.size, "($index) $name")
                    }
                    ok = true
                } catch (e: Exception) {
                    // status 오류·연결 끊김·불완전 다운로드 전부 여기로 — 부분 파일은
                    // saveToGallery가 이미 지웠으니, 통째로 다시 받는다(백오프).
                    Log.w("TeslaSync", "다운로드 실패(시도 $attempt/$MAX_ATTEMPTS): ${job.relPath}", e)
                    if (attempt < MAX_ATTEMPTS)
                        try { Thread.sleep(1000L * attempt) } catch (_: InterruptedException) {}
                }
            }
            if (ok) { SyncState.markSynced(this, job.relPath); saved++ } else failed++
        }
        val summary = "저장 ${saved}개" + if (failed > 0) " · 실패 ${failed}개" else ""
        Log.i("TeslaSync", "다운로드 종료: $summary")
        getSystemService(NotificationManager::class.java)
            .notify(DONE_NOTIF_ID, notif(summary, done = true))
        working.set(false)
        stopSelf()
    }

    private fun saveToGallery(name: String, input: InputStream, total: Long, label: String) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/TeslaSync")
            put(MediaStore.Video.Media.IS_PENDING, 1) // 완료 전엔 갤러리에 안 보이게
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert 실패")
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                val buf = ByteArray(1 shl 16)
                var done = 0L
                var lastStep = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val step = (done * 20 / total).toInt() // 5% 단위로만 알림 갱신
                        if (step != lastStep) {
                            lastStep = step
                            startForeground(NOTIF_ID, notif(label, (done * 100 / total).toInt()))
                        }
                    }
                }
                // 연결이 중간에 끊기면 read()가 -1을 조기 반환할 수 있다 —
                // 잘린 파일을 '완료'로 저장하지 않도록 받은 바이트 수를 검증.
                if (total > 0 && done != total)
                    throw IOException("불완전 다운로드: $done/$total bytes")
            } ?: throw IOException("출력 스트림 열기 실패")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null) // 부분 파일은 갤러리에 남기지 않음
            throw e
        }
    }

    private fun notif(text: String, progress: Int = -1, done: Boolean = false): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH, "클립 다운로드", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CH)
            .setContentTitle("TeslaSync")
            .setContentText(text)
            .setSmallIcon(
                if (done) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_sys_download
            )
            .apply { if (progress in 0..100) setProgress(100, progress, false) }
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CH = "download"
        private const val NOTIF_ID = 1
        private const val DONE_NOTIF_ID = 3
        private const val MAX_ATTEMPTS = 3  // 클립당 재시도(백오프)
        private const val EXTRA_PATHS = "paths"
        private const val EXTRA_SIZES = "sizes"

        fun start(ctx: Context, relPaths: ArrayList<String>, sizes: LongArray) {
            ctx.startForegroundService(
                Intent(ctx, DownloadService::class.java)
                    .putStringArrayListExtra(EXTRA_PATHS, relPaths)
                    .putExtra(EXTRA_SIZES, sizes)
            )
        }
    }
}
