package com.example.teslasync

import org.json.JSONArray
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** teslausb-neo REST API 클라이언트 (표준 HttpURLConnection, 의존성 0). */
class NeoApi(private val base: String = Config.NEO_BASE) {

    data class Clip(val path: String, val size: Long)

    /**
     * 아카이브된 클립 목록.
     * ponytail-calibration: neo의 실제 응답 스키마를 기기에서 확인해 파싱부만 맞추면 됨.
     * 가정: GET /api/v1/files → [{ "path": "...", "size": 123 }, ...]
     */
    fun listClips(): List<Clip> {
        val body = get("/api/v1/files")
        val arr = JSONArray(body)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Clip(o.getString("path"), o.optLong("size"))
        }
    }

    /** 클립 바이트 스트림. byte-range 재개는 호출측에서 Range 헤더로. */
    fun openClip(path: String, fromByte: Long = 0): InputStream {
        val conn = open("/api/v1/files/$path")
        if (fromByte > 0) conn.setRequestProperty("Range", "bytes=$fromByte-")
        conn.connect()
        return conn.inputStream
    }

    private fun get(path: String): String =
        open(path).run { connect(); inputStream.bufferedReader().use { it.readText() } }

    private fun open(path: String): HttpURLConnection =
        (URL(base + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 15000
        }
}
