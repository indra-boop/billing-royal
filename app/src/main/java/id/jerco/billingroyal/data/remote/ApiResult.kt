package id.jerco.billingroyal.data.remote

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.HttpException

/** Error aplikasi yang sudah dipetakan; [message] aman ditampilkan ke pengguna. */
sealed interface AppError {
    val message: String

    data class Api(
        val status: Int,
        val code: String,
        override val message: String,
        val fieldErrors: Map<String, String> = emptyMap(),
    ) : AppError

    data class Network(override val message: String, val timeout: Boolean = false) : AppError

    data class Unexpected(override val message: String) : AppError
}

/** 401 (unauthenticated / invalid_token) atau 403 account_inactive: sesi harus dihapus. */
val AppError.requiresRelogin: Boolean
    get() = this is AppError.Api && ErrorMapper.isSessionTerminating(status, code)

/**
 * True bila request tulis mungkin SUDAH diproses server, tetapi hasilnya tidak diketahui
 * (timeout, jaringan putus, respons tak terbaca, atau 5xx selain 503). Tidak boleh di-retry otomatis.
 */
val AppError.isOutcomeUncertain: Boolean
    get() = when (this) {
        is AppError.Network, is AppError.Unexpected -> true
        is AppError.Api -> status >= 500 && status != 503
    }

val AppError.isModuleNotReady: Boolean
    get() = this is AppError.Api && status == 503 && code == "module_not_ready"

sealed interface ApiResult<out T> {
    data class Success<T>(val data: T, val meta: Meta? = null) : ApiResult<T>
    data class Failure(val error: AppError) : ApiResult<Nothing>
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(data), meta)
    is ApiResult.Failure -> this
}

/** Menjalankan panggilan Retrofit dan memetakan semua kegagalan menjadi [AppError]. */
suspend fun <T> apiCall(block: suspend () -> Envelope<T>): ApiResult<T> = try {
    val envelope = block()
    ApiResult.Success(envelope.data, envelope.meta)
} catch (e: CancellationException) {
    throw e
} catch (t: Throwable) {
    ApiResult.Failure(ErrorMapper.map(t))
}

object ErrorMapper {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    const val CODE_INVALID_CREDENTIALS = "invalid_credentials"
    const val CODE_ACCOUNT_INACTIVE = "account_inactive"

    fun isSessionTerminating(status: Int, code: String): Boolean =
        (status == 401 && code != CODE_INVALID_CREDENTIALS) ||
            (status == 403 && code == CODE_ACCOUNT_INACTIVE)

    /** Memetakan body error envelope { "error": { code, message, details } }. */
    fun fromHttp(status: Int, body: String?): AppError.Api {
        val parsed = try {
            if (body.isNullOrBlank()) null else json.decodeFromString<ErrorEnvelope>(body).error
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        if (parsed == null) {
            return AppError.Api(status, "", fallbackMessage(status))
        }
        val message = parsed.message.ifBlank { fallbackMessage(status) }
        return AppError.Api(status, parsed.code, message, fieldErrors(parsed.details))
    }

    fun map(t: Throwable): AppError = when (t) {
        is HttpException -> {
            val body = try {
                t.response()?.errorBody()?.string()
            } catch (_: IOException) {
                null
            }
            fromHttp(t.code(), body)
        }
        is SocketTimeoutException -> AppError.Network("Permintaan melebihi batas waktu. Periksa koneksi internet Anda.", timeout = true)
        is UnknownHostException -> AppError.Network("Server tidak ditemukan. Periksa koneksi internet Anda.")
        is IOException -> AppError.Network("Tidak dapat terhubung ke server. Periksa koneksi internet Anda.")
        is SerializationException -> AppError.Unexpected("Respons server tidak dapat dibaca.")
        is IllegalArgumentException -> AppError.Unexpected("Respons server tidak dapat dibaca.")
        else -> AppError.Unexpected("Terjadi kesalahan tak terduga.")
    }

    private fun fallbackMessage(status: Int) = "Terjadi kesalahan pada server (kode $status)."

    private fun fieldErrors(details: kotlinx.serialization.json.JsonElement?): Map<String, String> {
        if (details !is JsonObject) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((key, value) in details) {
            val text = when (value) {
                is JsonPrimitive -> value.content
                is JsonArray -> (value.firstOrNull() as? JsonPrimitive)?.content
                else -> null
            }
            if (!text.isNullOrBlank()) out[key] = text
        }
        return out
    }
}
