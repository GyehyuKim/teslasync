package com.example.teslasync

import android.Manifest
import android.app.Activity
import androidx.core.app.ActivityCompat

/** 런타임 권한 일괄 요청. MVP 단계 — 거부 시 흐름은 추후 다듬음. */
object Permissions {
    fun requestAll(activity: Activity) {
        val perms = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        ActivityCompat.requestPermissions(activity, perms, 100)
    }
}
