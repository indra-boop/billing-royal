package id.jerco.billingroyal.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Menghitung layar yang meminta FLAG_SECURE (mencegah screenshot, rekam layar, dan thumbnail recents).
 * Pakai penghitung agar transisi antar layar aman tidak sempat melepas flag (layar baru masuk sebelum
 * layar lama keluar dari komposisi).
 */
internal class SecureWindowCounter(private val setSecure: (Boolean) -> Unit) {
    private var count = 0

    fun acquire() {
        count++
        if (count == 1) setSecure(true)
    }

    fun release() {
        if (count > 0) count--
        if (count == 0) setSecure(false)
    }
}

private val counters = java.util.WeakHashMap<Window, SecureWindowCounter>()

private fun counterFor(window: Window): SecureWindowCounter = counters.getOrPut(window) {
    SecureWindowCounter { secure ->
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Pasang di layar yang menampilkan data sensitif (Login, Akun, Detail invoice).
 * FLAG_SECURE aktif selama layar ini ada di komposisi.
 */
@Composable
fun SecureScreen() {
    val window = LocalContext.current.findActivity()?.window ?: return
    DisposableEffect(window) {
        val counter = counterFor(window)
        counter.acquire()
        onDispose { counter.release() }
    }
}
