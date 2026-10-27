package id.jerco.billingroyal.data.repo

import id.jerco.billingroyal.data.local.LogoStore
import id.jerco.billingroyal.data.local.ProfileDao
import id.jerco.billingroyal.data.local.ProfileEntity
import id.jerco.billingroyal.data.local.SessionManager
import id.jerco.billingroyal.data.local.StoredSession
import id.jerco.billingroyal.data.remote.ApiResult
import id.jerco.billingroyal.data.remote.BillingApi
import id.jerco.billingroyal.data.remote.LoginRequest
import id.jerco.billingroyal.data.remote.UserDto
import id.jerco.billingroyal.data.remote.apiCall
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val api: BillingApi,
    private val sessionManager: SessionManager,
    private val profileDao: ProfileDao,
    private val logoStore: LogoStore,
) {
    /** Cek token tersimpan di Keystore; null bila belum login. */
    suspend fun restoreSession(): StoredSession? = sessionManager.restore()

    suspend fun login(username: String, password: String, deviceName: String): ApiResult<StoredSession> {
        val result = apiCall { api.login(LoginRequest(username.trim(), password, deviceName)) }
        return when (result) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> {
                val session = sessionManager.start(result.data)
                // Profil + logo diambil SEKALI setelah login (best effort; tidak menggagalkan login).
                refreshProfile()
                ApiResult.Success(session)
            }
        }
    }

    suspend fun me(): ApiResult<UserDto> {
        val result = apiCall { api.me() }
        if (result is ApiResult.Success) sessionManager.updateUser(result.data)
        return result
    }

    /** POST /auth/logout lalu hapus semua data lokal, walaupun request gagal. */
    suspend fun logout() {
        try {
            apiCall { api.logout() }
        } finally {
            sessionManager.clearAll(notify = false)
        }
    }

    // ---------------------------------------------------------------- profil

    suspend fun cachedProfile(): ProfileEntity? = profileDao.get(sessionManager.scopeKey)

    /** Mengambil /profile dari server, men-decode logo ke file cache, dan menyimpan ke Room. */
    suspend fun refreshProfile(): ApiResult<ProfileEntity> {
        val scope = sessionManager.scopeKey
        return when (val result = apiCall { api.profile() }) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> {
                val p = result.data
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
                profileDao.upsert(entity)
                ApiResult.Success(entity)
            }
        }
    }

    /** Dari cache bila ada; bila belum ada, ambil dari server (sekali). */
    suspend fun profile(): ApiResult<ProfileEntity> {
        cachedProfile()?.let { return ApiResult.Success(it) }
        return refreshProfile()
    }
}
