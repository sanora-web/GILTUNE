package com.sanora.giltune

import android.content.Context
import android.os.Handler
import android.os.Looper

/** `demo` flavor: no Pleos SDK on the classpath. Reports that and delivers no vehicle data. */
class PleosSdkBridge(@Suppress("UNUSED_PARAMETER") context: Context, private val cb: PleosCallbacks) {
    fun start() {
        Handler(Looper.getMainLooper()).post { cb.onStatus("SDK", "Android 데모 빌드 · Pleos SDK 제외") }
    }
    fun release() {}
}
