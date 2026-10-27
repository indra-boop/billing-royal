package id.jerco.billingroyal.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Penyimpanan logo hasil decode (dipisah sebagai interface agar bisa diganti di unit test). */
interface LogoStore {
    suspend fun saveFromDataUrl(scopeKey: String, dataUrl: String?): String?
    suspend fun clear()
}

/** Decode logo (data URL base64) ke file cache sekali saja, agar tidak dipanggil/didecode di tiap layar. */
@Singleton
class FileLogoStore @Inject constructor(@ApplicationContext private val context: Context) : LogoStore {

    private val dir: File get() = File(context.cacheDir, "logo")

    override suspend fun saveFromDataUrl(scopeKey: String, dataUrl: String?): String? = withContext(Dispatchers.IO) {
        if (dataUrl.isNullOrBlank()) return@withContext null
        // Batas ukuran: string base64 sangat besar tidak didecode (hardening; server dipercaya).
        if (dataUrl.length > MAX_DATA_URL_CHARS) return@withContext null
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

    override suspend fun clear() = withContext(Dispatchers.IO) {
        dir.deleteRecursively()
        Unit
    }

    private companion object {
        const val MAX_DATA_URL_CHARS = 3_000_000
    }
}
