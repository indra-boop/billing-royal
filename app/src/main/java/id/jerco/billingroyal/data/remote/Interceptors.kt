package id.jerco.billingroyal.data.remote

import id.jerco.billingroyal.data.local.SessionEvent
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

internal fun Request.isPublicEndpoint(): Boolean {
    val path = url.encodedPath.trimEnd('/')
    return path.endsWith("/health") || path.endsWith("/auth/login")
}

/** Menambahkan "Authorization: Bearer <token>" ke semua endpoint kecuali /health dan /auth/login. */
class AuthInterceptor(private val tokenProvider: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.isPublicEndpoint()) return chain.proceed(request)
        if (request.header("Authorization") != null) return chain.proceed(request) // mis. revoke token lama
        val token = tokenProvider() ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
    }
}

internal fun Request.bearerToken(): String? =
    header("Authorization")?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Mendeteksi 401 dengan kode `unauthenticated`/`invalid_token` dan 403 `account_inactive` dari endpoint
 * terproteksi, lalu memanggil [onSessionInvalid] dengan token yang DIPAKAI request itu (penerima hanya
 * boleh menghapus sesi bila token tersebut masih aktif). 401 tanpa kode yang dikenal (body kosong,
 * bukan JSON, HTML dari proxy) tidak menghapus sesi.
 */
class SessionInterceptor(private val onSessionInvalid: (token: String?, reason: SessionEvent, call: Call) -> Unit) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (request.isPublicEndpoint()) return response
        if (response.code == 401 || response.code == 403) {
            val body = try {
                response.peekBody(PEEK_LIMIT).string()
            } catch (_: Exception) {
                null
            }
            val error = ErrorMapper.fromHttp(response.code, body)
            if (ErrorMapper.isSessionTerminating(error.status, error.code)) {
                val reason = if (response.code == 403) SessionEvent.ACCOUNT_INACTIVE else SessionEvent.EXPIRED
                onSessionInvalid(request.bearerToken(), reason, chain.call())
            }
        }
        return response
    }

    private companion object {
        const val PEEK_LIMIT = 16L * 1024
    }
}

/** Menambahkan "Content-Type: application/json" untuk request ber-body (selain yang sudah diatur converter). */
class JsonHeadersInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return chain.proceed(request.newBuilder().header("Accept", "application/json").build())
    }
}

/** Logging hanya untuk endpoint selain /auth/login. Level BASIC (tanpa header dan tanpa body). */
class LoginExcludingInterceptor(private val delegate: Interceptor) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (chain.request().url.encodedPath.trimEnd('/').endsWith("/auth/login")) return chain.proceed(chain.request())
        return delegate.intercept(chain)
    }
}
