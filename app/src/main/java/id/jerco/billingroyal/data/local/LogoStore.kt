package id.jerco.billingroyal.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import id.jerco.billingroyal.core.LogoDataUrl
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
        // Hanya data:image/png|jpeg;base64, maksimal 2 MiB dan magic bytes cocok (T14); selain itu logo diabaikan.
        val bytes = LogoDataUrl.decode(dataUrl) ?: return@withContext null
        try {
            dir.mkdirs()
            val file = File(dir, "logo_" + scopeKey.replace(':', '_'))
            file.writeBytes(bytes)
            file.absolutePath
        } catch (_: java.io.IOException) {
            null
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        dir.deleteRecursively()
        Unit
    }
}
