package id.jerco.billingroyal.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import id.jerco.billingroyal.core.FeatureFlags
import id.jerco.billingroyal.data.local.LocalData
import id.jerco.billingroyal.data.local.LogoStore
import id.jerco.billingroyal.data.local.ProfileDao
import id.jerco.billingroyal.data.local.ProfileEntity
import id.jerco.billingroyal.data.local.RequestCanceller
import id.jerco.billingroyal.data.local.SessionEvent
import id.jerco.billingroyal.data.local.SessionManager
import id.jerco.billingroyal.data.local.TokenCipher
import id.jerco.billingroyal.data.local.TokenStore
import id.jerco.billingroyal.data.remote.ApiResult
import id.jerco.billingroyal.data.remote.AppError
import id.jerco.billingroyal.data.remote.AuthInterceptor
import id.jerco.billingroyal.data.remote.BillingApi
import id.jerco.billingroyal.data.remote.BusinessRef
import id.jerco.billingroyal.data.remote.LoginData
import id.jerco.billingroyal.data.remote.NetworkFactory
import id.jerco.billingroyal.data.remote.SessionInterceptor
import id.jerco.billingroyal.data.remote.UserDto
import id.jerco.billingroyal.data.repo.AuthRepository
import id.jerco.billingroyal.data.repo.isCurrent
import id.jerco.billingroyal.data.repo.ticket
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

private class FakeCipher(var failEncrypt: Boolean = false) : TokenCipher {
    var keyDeleted = 0
    override fun encrypt(plain: ByteArray): ByteArray {
        if (failEncrypt) throw IllegalStateException("Keystore rusak")
        return plain.reversedArray()
    }
    override fun decrypt(data: ByteArray): ByteArray = data.reversedArray()
    override fun deleteKey() { keyDeleted++ }
}

private class FakeLocalData(var failDatabase: Boolean = false, var failLogo: Boolean = false) : LocalData {
    var dbCleared = 0
    var logoCleared = 0
    override suspend fun clearDatabase() { if (failDatabase) error("DB gagal") else dbCleared++ }
    override suspend fun clearLogo() { if (failLogo) error("logo gagal") else logoCleared++ }
}

private class FakeLogoStore : LogoStore {
    override suspend fun saveFromDataUrl(scopeKey: String, dataUrl: String?): String? = null
    override suspend fun clear() {}
}

private class FakeProfileDao : ProfileDao {
    val rows = HashMap<String, ProfileEntity>()
    override suspend fun get(scope: String) = rows[scope]
    override suspend fun upsert(profile: ProfileEntity) { rows[profile.scope] = profile }
}

private fun loginData(token: String, userId: Long = 5, businessId: Long = 9) =
    LoginData(token, "2026-12-31 00:00:00", UserDto(userId, "operator", "Operator Fiktif", "operator", BusinessRef(businessId, "Laundry Fiktif", "LF")))

class SessionManagerTest {
    private lateinit var dir: File
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val cipher = FakeCipher()
    private val local = FakeLocalData()
    private var cancelled = 0
    private lateinit var ds: DataStore<Preferences>
    private lateinit var tokenStore: TokenStore
    private lateinit var manager: SessionManager

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("br-session").toFile()
        ds = PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { File(dir, "s.preferences_pb") }
        tokenStore = TokenStore(ds, cipher)
        manager = SessionManager(tokenStore, local, RequestCanceller { cancelled++ }, appScope)
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `401 basi dari token lama TIDAK menghapus sesi baru`() = runBlocking<Unit> {
        manager.start(loginData("tok-OLD"))
        manager.start(loginData("tok-NEW")) // login ulang saat request lama masih berjalan

        manager.invalidateIfCurrent("tok-OLD", SessionEvent.EXPIRED)

        assertEquals("tok-NEW", manager.currentToken())
        assertNotNull(manager.session.value)
        assertNull(manager.notice.value)
    }

    @Test
    fun `token aktif yang ditolak menghapus sesi, menaikkan generasi, dan membatalkan request lain`() = runBlocking<Unit> {
        manager.start(loginData("tok-A"))
        val ticket = manager.ticket()
        val dbBefore = local.dbCleared

        manager.invalidateIfCurrent("tok-A", SessionEvent.ACCOUNT_INACTIVE)

        assertNull(manager.currentToken())
        assertNull(manager.session.value)
        assertEquals(SessionEvent.ACCOUNT_INACTIVE, manager.notice.value)
        assertFalse(manager.isCurrent(ticket), "penulisan cache dari request lama harus ditolak")
        assertTrue(cancelled >= 1)
        // appScope Unconfined: wipe sudah selesai
        eventually { local.dbCleared == dbBefore + 1 }
        assertEquals(dbBefore + 1, local.dbCleared)
    }

    @Test
    fun `token null atau kosong diabaikan`() = runBlocking<Unit> {
        manager.start(loginData("tok-A"))
        manager.invalidateIfCurrent(null, SessionEvent.EXPIRED)
        manager.invalidateIfCurrent("", SessionEvent.EXPIRED)
        assertEquals("tok-A", manager.currentToken())
    }

    @Test
    fun `wipe tertunda tidak menghapus sesi baru yang dimulai sesudahnya`() = runBlocking<Unit> {
        withTimeout(20_000) {
            val gate = CompletableDeferred<Unit>()
            var block = false
            val gated = object : LocalData {
                override suspend fun clearDatabase() { if (block) gate.await() }
                override suspend fun clearLogo() {}
            }
            val m = SessionManager(tokenStore, gated, RequestCanceller { }, appScope)
            m.start(loginData("tok-A"))
            block = true
            m.invalidateIfCurrent("tok-A", SessionEvent.EXPIRED) // wipe async tertahan di langkah DB
            val login = async { m.start(loginData("tok-B")) }    // login baru menunggu mutex
            gate.complete(Unit)
            login.await()
            eventually { m.currentToken() == "tok-B" }
            delay(200) // beri kesempatan wipe tertunda (bila salah) menghapus sesi baru
            assertEquals("tok-B", m.currentToken())
            assertNotNull(m.session.value)
        }
    }

    @Test
    fun `clearAll fail-safe per langkah, DB gagal tidak menghentikan token dan logo`() = runBlocking<Unit> {
        manager.start(loginData("tok-A"))
        val failing = FakeLocalData(failDatabase = true)
        val m = SessionManager(tokenStore, failing, RequestCanceller { }, appScope)
        m.restore()
        m.clearAll()
        assertNull(m.currentToken(), "token tetap terhapus")
        assertEquals(1, failing.logoCleared, "langkah logo tetap jalan")
        assertTrue(cipher.keyDeleted >= 1)
    }

    @Test
    fun `restore tanpa sesi membersihkan sisa cache yatim`() = runBlocking<Unit> {
        val before = local.dbCleared
        assertNull(manager.restore())
        assertEquals(before + 1, local.dbCleared)
        assertEquals(1, local.logoCleared)
    }

    @Test
    fun `data sesi tidak lengkap atau kunci hilang dibersihkan saat load`() = runBlocking<Unit> {
        manager.start(loginData("tok-A"))
        // 1) data sesi tidak lengkap (user_id hilang) -> ciphertext yatim dihapus
        ds.edit { it.remove(androidx.datastore.preferences.core.longPreferencesKey("user_id")) }
        assertNull(tokenStore.load())
        assertTrue(ds.data.first().asMap().isEmpty(), "tidak boleh ada sisa token")
        assertTrue(cipher.keyDeleted >= 1)
    }
}

private suspend fun eventually(timeoutMs: Long = 5_000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!cond() && System.currentTimeMillis() < end) delay(10)
}

class SessionInterceptorProductionClientTest {
    private lateinit var server: MockWebServer
    private val calls = mutableListOf<Triple<String?, SessionEvent, Call>>()

    @BeforeTest fun setUp() { server = MockWebServer().also { it.start() } }
    @AfterTest fun tearDown() { server.shutdown() }

    private fun api(token: String?): BillingApi {
        // Klien PRODUKSI (NetworkFactory) dengan urutan interceptor yang sama seperti DI.
        val client = NetworkFactory.okHttpClient(
            listOf(AuthInterceptor { token }, SessionInterceptor { t, r, c -> calls += Triple(t, r, c) }),
        )
        return NetworkFactory.api(server.url("/billing/api/v1/").toString(), client)
    }

    private fun json(status: Int, code: String) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"error":{"code":"$code","message":"m"}}""")

    @Test
    fun `klien produksi - 401 body kosong TIDAK memanggil invalidasi`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(401))
        runCatching { api("tok-1").invoice(1) }
        server.enqueue(MockResponse().setResponseCode(401).setBody("<html>Unauthorized</html>"))
        runCatching { api("tok-1").invoice(1) }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `klien produksi - 401 unauthenticated membawa token request dan alasan EXPIRED`() = runBlocking<Unit> {
        server.enqueue(json(401, "unauthenticated"))
        runCatching { api("tok-OLD").invoice(1) }
        assertEquals(1, calls.size)
        assertEquals("tok-OLD", calls[0].first, "token yang DIPAKAI request, bukan token aktif saat respons tiba")
        assertEquals(SessionEvent.EXPIRED, calls[0].second)
    }

    @Test
    fun `klien produksi - 403 account_inactive membawa alasan ACCOUNT_INACTIVE`() = runBlocking<Unit> {
        server.enqueue(json(403, "account_inactive"))
        runCatching { api("tok-1").invoice(1) }
        assertEquals(SessionEvent.ACCOUNT_INACTIVE, calls.single().second)
    }

    @Test
    fun `klien produksi - endpoint publik dan 401 invalid_credentials tidak memicu invalidasi`() = runBlocking<Unit> {
        server.enqueue(json(401, "invalid_credentials"))
        runCatching { api(null).login(id.jerco.billingroyal.data.remote.LoginRequest("u", "p", "d")) }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `klien produksi tidak mengikuti redirect dan tidak retry`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/elsewhere"))
        val r = runCatching { api("tok").invoice(1) }
        assertTrue(r.isFailure)
        assertEquals(1, server.requestCount)
        val client = NetworkFactory.okHttpClient(emptyList())
        assertFalse(client.followRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(30_000, client.readTimeoutMillis)
    }

    @Test
    fun `AuthInterceptor tidak menimpa header Authorization eksplisit (revoke token lama)`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"data":{"revoked":true}}"""))
        api("tok-CURRENT").logoutToken("Bearer tok-OLD")
        assertEquals("Bearer tok-OLD", server.takeRequest().getHeader("Authorization"))
    }
}

class AuthRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cipher = FakeCipher()
    private val local = FakeLocalData()
    private lateinit var manager: SessionManager
    private lateinit var repo: AuthRepository

    @BeforeTest
    fun setUp() {
        server = MockWebServer().also { it.start() }
        dir = Files.createTempDirectory("br-auth").toFile()
        val ds = PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { File(dir, "s.preferences_pb") }
        manager = SessionManager(TokenStore(ds, cipher), local, RequestCanceller { }, appScope)
        val client = NetworkFactory.okHttpClient(listOf(AuthInterceptor { manager.currentToken() }))
        val api = NetworkFactory.api(server.url("/billing/api/v1/").toString(), client)
        repo = AuthRepository(api, manager, FakeProfileDao(), FakeLogoStore(), appScope)
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun loginResponse(token: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(
        """{"data":{"token":"$token","expires_at":"2026-12-31 00:00:00","user":{"id":5,"username":"operator","full_name":"Operator Fiktif","role":"operator","business":{"id":9,"name":"Laundry Fiktif","code":"LF"}}}}""",
    )

    private val profileJson = """{"data":{"id":9,"name":"Laundry Fiktif","code":"LF"}}"""

    @Test
    fun `kegagalan Keystore saat login memberi pesan jelas, membersihkan sisa, dan mencabut token di server`() = runBlocking<Unit> {
        cipher.failEncrypt = true
        server.enqueue(loginResponse("tok-ORPHAN"))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"data":{"revoked":true}}"""))

        val result = repo.login("operator", "rahasia-fiktif", "Perangkat Uji")

        val failure = assertIs<ApiResult.Failure>(result)
        assertEquals(AuthRepository.SECURE_STORAGE_MESSAGE, failure.error.message)
        assertIs<AppError.Unexpected>(failure.error)
        assertNull(manager.currentToken())
        assertNull(manager.session.value)

        assertEquals("/billing/api/v1/auth/login", server.takeRequest().path)
        val revoke = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
        assertNotNull(revoke, "token yang baru diterbitkan harus dicabut")
        assertEquals("/billing/api/v1/auth/logout", revoke.path)
        assertEquals("Bearer tok-ORPHAN", revoke.getHeader("Authorization"))
    }

    @Test
    fun `login sukses menyimpan sesi`() = runBlocking<Unit> {
        server.enqueue(loginResponse("tok-OK"))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(profileJson))
        assertIs<ApiResult.Success<*>>(repo.login("operator", "rahasia-fiktif", "Perangkat Uji"))
        assertEquals("tok-OK", manager.currentToken())
    }

    @Test
    fun `logout menghapus data lokal DULU lalu mencabut token lama di server`() = runBlocking<Unit> {
        server.enqueue(loginResponse("tok-OUT"))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(profileJson))
        repo.login("operator", "rahasia-fiktif", "Perangkat Uji")
        server.takeRequest(); server.takeRequest()
        // Server revoke sengaja lambat: logout lokal TIDAK boleh menunggunya.
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody("""{"data":{"revoked":true}}""")
                .setBodyDelay(1500, java.util.concurrent.TimeUnit.MILLISECONDS),
        )
        val started = System.nanoTime()
        repo.logout()
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertNull(manager.currentToken())
        assertTrue(tookMs < 1_000, "logout lokal tidak boleh menunggu jaringan ($tookMs ms)")
        val revoke = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
        assertNotNull(revoke)
        assertEquals("Bearer tok-OUT", revoke.getHeader("Authorization"))
    }

    @Test
    fun `logout tanpa jaringan tetap menghapus data lokal`() = runBlocking<Unit> {
        server.enqueue(loginResponse("tok-X"))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(profileJson))
        repo.login("operator", "rahasia-fiktif", "Perangkat Uji")
        server.shutdown()
        repo.logout()
        assertNull(manager.currentToken())
        assertNull(manager.session.value)
        assertTrue(local.dbCleared >= 1)
    }
}
