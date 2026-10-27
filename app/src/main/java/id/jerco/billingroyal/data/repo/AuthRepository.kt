package id.jerco.billingroyal.data.repo

import id.jerco.billingroyal.data.local.ApplicationScope
import id.jerco.billingroyal.data.local.LogoStore
import id.jerco.billingroyal.data.local.ProfileDao
import id.jerco.billingroyal.data.local.ProfileEntity
import id.jerco.billingroyal.data.local.SessionManager
import id.jerco.billingroyal.data.local.StoredSession
import id.jerco.billingroyal.data.remote.ApiResult
import id.jerco.billingroyal.data.remote.AppError
import id.jerco.billingroyal.data.remote.BillingApi
import id.jerco.billingroyal.data.remote.LoginRequest
import id.jerco.billingroyal.data.remote.UserDto
import id.jerco.billingroyal.data.remote.apiCall
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class AuthRepository @Inject constructor(
    private val api: BillingApi,
    private val sessionManager: SessionManager,
    private val profileDao: ProfileDao,
    private val logoStore: LogoStore,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    /** Cek token tersimpan di Keystore; null bila belum login. */
    suspend fun restoreSession(): StoredSession? = sessionManager.restore()

    suspend fun login(username: String, password: String, deviceName: String): ApiResult<StoredSession> {
        val result = apiCall { api.login(LoginRequest(username.trim(), password, deviceName)) }
        return when (result) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> {
                val session = try {
                    // Tidak boleh terbatalkan di tengah: token sudah diterbitkan server.
                    withContext(NonCancellable) { sessionManager.start(result.data) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Keystore/DataStore gagal menyimpan token. Bersihkan sisa data dan CABUT token yang baru
                    // diterbitkan agar tidak menjadi token yatim di server.
                    revokeInBackground(result.data.token)
                    return ApiResult.Failure(AppError.Unexpected(SECURE_STORAGE_MESSAGE))
                }
                // Profil + logo diambil SEKALI setelah login (best effort; tidak menggagalkan login).
                refreshProfile()
                ApiResult.Success(session)
            }
        }
    }

    suspend fun me(): ApiResult<UserDto> {
        val ticket = sessionManager.ticket()
        val result = apiCall { api.me() }
        if (result is ApiResult.Success && sessionManager.isCurrent(ticket)) sessionManager.updateUser(result.data)
        return result
    }

    /**
     * Logout: data lokal dihapus LEBIH DULU (tidak menunggu jaringan dan tidak bisa dibatalkan),
     * baru token dicabut di server dengan best-effort (timeout pendek, di scope aplikasi).
     */
    suspend fun logout() {
        val token = sessionManager.currentToken()
        withContext(NonCancellable) { sessionManager.clearAll() }
        if (token != null) revokeInBackground(token)
    }

    private fun revokeInBackground(token: String): Job = appScope.launch {
        withTimeoutOrNull(REVOKE_TIMEOUT_MS) { apiCall { api.logoutToken("Bearer $token") } }
    }

    // ---------------------------------------------------------------- profil

    suspend fun cachedProfile(): ProfileEntity? = profileDao.get(sessionManager.scopeKey)

    /** Mengambil /profile dari server, men-decode logo ke file cache, dan menyimpan ke Room. */
    suspend fun refreshProfile(): ApiResult<ProfileEntity> {
        val ticket = sessionManager.ticket()
        val scope = ticket.key
        return when (val result = apiCall { api.profile() }) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> {
                val p = result.data
                // Sesi berakhir selama request: jangan menulis profil/logo akun lama.
                if (!sessionManager.isCurrent(ticket)) return ApiResult.Failure(AppError.Unexpected("Sesi berakhir."))
                val logoPath = logoStore.saveFromDataUrl(scope, p.logo_data)
                val entity = ProfileEntity(
                    scope = scope,
                    businessId = p.id,
                    name = p.name,
                    code = p.code,
                    address = p.address,
                    bankName = p.bank_name,
                    bankAccount = p.bank_account,
                    bankHolder = p.bank_holder,
                    logoPath = logoPath,
                )
                if (sessionManager.isCurrent(ticket)) profileDao.upsert(entity) else logoStore.clear()
                ApiResult.Success(entity)
            }
        }
    }

    /** Dari cache bila ada; bila belum ada, ambil dari server (sekali). */
    suspend fun profile(): ApiResult<ProfileEntity> {
        cachedProfile()?.let { return ApiResult.Success(it) }
        return refreshProfile()
    }

    companion object {
        const val REVOKE_TIMEOUT_MS = 5_000L
        const val SECURE_STORAGE_MESSAGE =
            "Tidak dapat menyimpan sesi dengan aman di perangkat ini. Coba masuk lagi, atau hubungi admin " +
                "bila masalah berulang."
    }
}
