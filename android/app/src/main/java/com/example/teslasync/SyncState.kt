package com.example.teslasync

import android.content.Context

/**
 * 이미 받은 클립 경로 집합. 중복 다운로드 방지.
 * ponytail: SharedPreferences Set이면 클립 수천 개까지 충분. 넘치면 SQLite로.
 */
object SyncState {
    private const val PREF = "sync_state"
    private const val KEY = "synced_paths"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun synced(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY, emptySet()) ?: emptySet()

    fun markSynced(ctx: Context, path: String) {
        val cur = HashSet(synced(ctx)); cur.add(path)
        prefs(ctx).edit().putStringSet(KEY, cur).apply()
    }
}
