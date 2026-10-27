package id.jerco.billingroyal.data.local

import id.jerco.billingroyal.data.remote.LoginData
import id.jerco.billingroyal.data.repo.ScopeProvider
import id.jerco.billingroyal.data.remote.UserDto
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class SessionEvent { EXPIRED }

/**
 * Pusat sesi: token (Keystore + DataStore), cache Room, dan file logo.
 * Saat 401/403 account_inactive: [invalidateFromInterceptor] menghapus semuanya lalu memberi sinyal ke UI.
 */
@Singleton
class SessionManager @Inject constructor(
    private val tokenStore: TokenStore,
    private val database: AppDatabase,
    private val logoStore: LogoStore,
) : ScopeProvider {
    private val _session = MutableStateFlow<StoredSession?>(null)
    val session: StateFlow<StoredSession?> = _session

    private val eventChannel = Channel<SessionEvent>(Channel.BUFFERED)
    val events: Flow<SessionEvent> = eventChannel.receiveAsFlow()

    private val clearMutex = Mutex()

    /** Kunci cache saat ini ("user_id:business_id"). */
    override val scopeKey: String get() = _session.value?.scopeKey ?: NO_SCOPE

    fun currentToken(): String? = tokenStore.currentToken()

    suspend fun restore(): StoredSession? {
        val stored = tokenStore.load()
        _session.value = stored
        return stored
    }

    /** Mulai sesi baru setelah login. Seluruh data akun sebelumnya dihapus lebih dulu. */
    suspend fun start(data: LoginData): StoredSession {
        clearAll(notify = false)
        val session = tokenStore.save(data.token, data.expires_at, data.user)
        _session.value = session
        return session
    }

    suspend fun updateUser(user: UserDto) {
        _session.value = tokenStore.updateUser(user) ?: _session.value
    }

    /** Hapus token, seluruh tabel Room, DataStore, dan logo. */
    suspend fun clearAll(notify: Boolean) {
        clearMutex.withLock {
            val hadSession = _session.value != null || tokenStore.currentToken() != null
            _session.value = null
            tokenStore.clear()
            withContext(Dispatchers.IO) { database.clearAllTables() }
            logoStore.clear()
            if (notify && hadSession) eventChannel.trySend(SessionEvent.EXPIRED)
        }
    }

    /** Dipanggil dari thread OkHttp saat server menolak token. */
    fun invalidateFromInterceptor() {
        runBlocking(Dispatchers.IO) { clearAll(notify = true) }
    }

    companion object {
        const val NO_SCOPE = "none"
    }
}
