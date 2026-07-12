package com.example.teslasync

import org.json.JSONArray
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** pi/clipserver.py 클라이언트 (표준 HttpURLConnection, 의존성 0). */
class ClipApi(private val base: String = Config.API_BASE) {

    data class Clip(val file: String, val camera: String, val size: Long)

    data class Event(
        val type: String,       // "SavedClips" | "SentryClips"
        val name: String,       // 이벤트 폴더명 (YYYY-MM-DD_HH-MM-SS)
        val timestamp: String,
        val city: String,
        val street: String,
        val reason: String,
        val clips: List<Clip>,
    ) {
        fun relPath(clip: Clip) = "$type/$name/${clip.file}"
    }

    /** GET /api/events — SavedClips/SentryClips 이벤트 목록 (최신순). */
    fun listEvents(limit: Int = 100): List<Event> {
        val body = open("/api/events?limit=$limit").run {
            connect(); inputStream.bufferedReader().use { it.readText() }
        }
        val arr = JSONArray(body)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val clipsArr = o.getJSONArray("clips")
            Event(
                type = o.getString("type"),
                name = o.getString("name"),
                timestamp = o.optString("timestamp"),
                city = o.optString("city"),
                street = o.optString("street"),
                reason = o.optString("reason"),
                clips = (0 until clipsArr.length()).map { j ->
                    val c = clipsArr.getJSONObject(j)
                    Clip(c.getString("file"), c.optString("camera"), c.optLong("size"))
                },
            )
        }
    }

    /** 파일 바이트 스트림. fromByte > 0이면 Range 헤더로 이어받기.
     *  기대 status(전체 200 / Range 206)가 아니면 IOException — 404/416/500 을
     *  조용히 흘려 잘린·엉뚱한 바이트를 클립으로 저장하지 않게 한다. */
    fun openFile(relPath: String, fromByte: Long = 0): InputStream {
        val conn = open("/files/$relPath")
        if (fromByte > 0) conn.setRequestProperty("Range", "bytes=$fromByte-")
        conn.connect()
        val expected = if (fromByte > 0) 206 else 200
        val code = conn.responseCode
        if (code != expected) {
            conn.disconnect()
            throw IOException("clipserver HTTP $code (기대 $expected): $relPath")
        }
        return conn.inputStream
    }

    private fun open(path: String): HttpURLConnection =
        (URL(base + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 30000
        }
}
