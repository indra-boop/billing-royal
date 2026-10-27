package id.jerco.billingroyal.data.local

import id.jerco.billingroyal.data.remote.LoginData
import id.jerco.billingroyal.data.remote.UserDto
import id.jerco.billingroyal.data.repo.ScopeProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call

/** Alasan sesi berakhir karena keputusan server; dipakai untuk pesan di layar Login. */
enum class SessionEvent { EXPIRED, ACCOUNT_INACTIVE }

/** Data lokal selain token yang harus dihapus saat sesi berakhir (Room + file logo). */
interface LocalData {
    suspend fun clearDatabase()
    suspend fun clearLogo()
}

/** Membatalkan request OkHttp yang masih berjalan saat sesi berakhir. */
fun interface RequestCanceller {
    /** Batalkan semua call yang masih berjalan kecuali [except] (call yang sedang membawa respons 401). */
    fun cancelAllExcept(except: Call?)
}

/**
 * Pusat sesi: token (Keystore + DataStore), cache Room, dan file logo.
 *
 * Aturan:
 * - [invalidateIfCurrent] hanya bekerja bila token pada request yang ditolak masih token AKTIF
 *   (respons 401 basi dari sesi lama tidak boleh menghapus sesi baru).
 * - Setiap akhir sesi menaikkan [generation]; repository membuang penulisan cache dari request
 *   yang dimulai pada generasi lama (mencegah data akun lama tertulis kembali setelah wipe).
 * - Penghapusan lokal tidak bergantung pada jaringan dan tidak bisa dibatalkan ([NonCancellable]);
 *   tiap langkah fail-safe (satu langkah gagal tidak menghentikan langkah lain).
 */
@Singleton
class SessionManager @Inject constructor(
    private val tokenStore: TokenStore,
    private val localData: LocalData,
    private val canceller: RequestCanceller,
    @ApplicationScope private val appScope: CoroutineScope,
) : ScopeProvider {
    private val _session = MutableStateFlow<StoredSession?>(null)
    val session: StateFlow<StoredSession?> = _session

    private val eventChannel = Channel<SessionEvent>(Channel.BUFFERED)
    val events: Flow<SessionEvent> = eventChannel.receiveAsFlow()

    /** Pesan untuk layar Login setelah sesi diakhiri server; dihapus setelah ditampilkan/login. */
    private val _notice = MutableStateFlow<SessionEvent?>(null)
    val notice: StateFlow<SessionEvent?> = _notice

    private val clearMutex = Mutex()
    private val stateLock = Any()

    @Volatile
    private var generationValue = 0L

    /** Kunci cache saat ini ("user_id:business_id"). */
    override val scopeKey: String get() = _session.value?.scopeKey ?: NO_SCOPE
    override val generation: Long get() = generationValue

    fun currentToken(): String? = tokenStore.currentToken()

    fun consumeNotice() {
        _notice.value = null
    }

    suspend fun restore(): StoredSession? {
        val stored = tokenStore.load()
        _session.value = stored
        if (stored == null) {
            // Tidak ada sesi: sisa cache (mis. proses mati di tengah wipe) dibersihkan.
            withContext(NonCancellable) {
                clearMutex.withLock {
                    safeStep { localData.clearDatabase() }
                    safeStep { localData.clearLogo() }
                }
            }
        }
        return stored
    }

    /**
     * Mulai sesi baru setelah login. Seluruh data akun sebelumnya dihapus lebih dulu.
     * Bila penyimpanan aman (Keystore/DataStore) gagal, exception diteruskan ke pemanggil
     * setelah sisa data dibersihkan.
     */
    suspend fun start(data: LoginData): StoredSession {
        clearAll()
        return try {
            val session = tokenStore.save(data.token, data.expires_at, data.user)
            _notice.value = null
            _session.value = session
            session
        } catch (e: Exception) {
            clearAll()
            throw e
        }
    }

    suspend fun updateUser(user: UserDto) {
        _session.value = tokenStore.updateUser(user) ?: _session.value
    }

    /**
     * Akhiri sesi di perangkat (logout/force login): batalkan request berjalan, lalu hapus token,
     * Room, DataStore, dan logo. Tidak menunggu jaringan.
     */
    suspend fun clearAll() {
        synchronized(stateLock) {
            generationValue++
            _session.value = null
            tokenStore.forgetInMemory()
        }
        canceller.cancelAllExcept(null)
        wipe()
    }

    /**
     * Dipanggil dari thread OkHttp saat server menolak token ([token] = token pada request yang ditolak).
     * Tidak melakukan apa pun bila [token] bukan token aktif. Bagian sinkron hanya menandai sesi tidak
     * valid; penghapusan data berjalan di scope aplikasi (tidak memblokir thread jaringan).
     */
    fun invalidateIfCurrent(token: String?, reason: SessionEvent, failingCall: Call? = null) {
        if (token.isNullOrEmpty()) return
        val expected: Long
        synchronized(stateLock) {
            if (tokenStore.currentToken() != token) return
            generationValue++
            expected = generationValue
            _session.value = null
            tokenStore.forgetInMemory()
        }
        canceller.cancelAllExcept(failingCall)
        _notice.value = reason
        eventChannel.trySend(reason)
        appScope.launch(Dispatchers.IO) {
            withContext(NonCancellable) {
                clearMutex.withLock {
                    // Bila login baru sudah terjadi (generasi berubah), jangan hapus sesi baru itu.
                    if (generationValue == expected) wipeLocked()
                }
            }
        }
    }

    private suspend fun wipe() = withContext(NonCancellable) { clearMutex.withLock { wipeLocked() } }

    /** Urutan: token dulu (sesi hilang), lalu Room, lalu logo; tiap langkah fail-safe. */
    private suspend fun wipeLocked() {
        safeStep { tokenStore.clear() }
        safeStep { withContext(Dispatchers.IO) { localData.clearDatabase() } }
        safeStep { localData.clearLogo() }
    }

    private inline fun safeStep(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // Satu langkah gagal tidak boleh menghentikan langkah lain. Sisa data dibersihkan
            // lagi oleh restore() (tanpa sesi) atau start() (login berikutnya).
        }
    }

    companion object {
        const val NO_SCOPE = "none"
    }
}
