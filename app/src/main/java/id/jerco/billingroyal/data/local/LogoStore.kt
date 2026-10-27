package id.jerco.billingroyal.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decode logo (data URL base64) ke file cache sekali saja, agar tidak dipanggil/didecode di tiap layar. */
@Singleton
class LogoStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val dir: File get() = File(context.cacheDir, "logo")

    suspend fun saveFromDataUrl(scopeKey: String, dataUrl: String?): String? = withContext(Dispatchers.IO) {
        if (dataUrl.isNullOrBlank()) return@withContext null
        try {
            val base64 = dataUrl.substringAfter("base64,", missingDelimiterValue = dataUrl)
            val bytes = Base64.getMimeDecoder().decode(base64)
            if (bytes.isEmpty()) return@withContext null
            dir.mkdirs()
            val file = File(dir, "logo_" + scopeKey.replace(':', '_'))
            file.writeBytes(bytes)
            file.absolutePath
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        dir.deleteRecursively()
        Unit
    }
}
