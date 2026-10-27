package id.jerco.billingroyal.data

import id.jerco.billingroyal.core.FeatureFlags
import id.jerco.billingroyal.data.local.DraftDao
import id.jerco.billingroyal.data.local.InvoiceDraftEntity
import id.jerco.billingroyal.data.remote.ApiResult
import id.jerco.billingroyal.data.remote.AppError
import id.jerco.billingroyal.data.remote.AuthInterceptor
import id.jerco.billingroyal.data.remote.BillingApi
import id.jerco.billingroyal.data.remote.NetworkFactory
import id.jerco.billingroyal.data.remote.SessionInterceptor
import id.jerco.billingroyal.data.remote.isOutcomeUncertain
import id.jerco.billingroyal.data.repo.DraftItem
import id.jerco.billingroyal.data.repo.InvoiceDraft
import id.jerco.billingroyal.data.repo.InvoiceRepository
import id.jerco.billingroyal.data.repo.ScopeProvider
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy

private class FakeDraftDao : DraftDao {
    val rows = HashMap<String, InvoiceDraftEntity>()
    override suspend fun get(scope: String) = rows[scope]
    override suspend fun upsert(draft: InvoiceDraftEntity) { rows[draft.scope] = draft }
    override suspend fun delete(scope: String) { rows.remove(scope) }
}

class InvoiceRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var dao: FakeDraftDao
    private val scope = object : ScopeProvider { override val scopeKey = "5:9" }
    private val uuidV4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        dao = FakeDraftDao()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun client(readTimeoutMs: Long = 5_000, vararg extra: okhttp3.Interceptor): OkHttpClient =
        OkHttpClient.Builder()
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .apply { extra.forEach(::addInterceptor) }
            .build()

    private fun repo(flags: FeatureFlags = FeatureFlags(), client: OkHttpClient = client()): InvoiceRepository {
        val api: BillingApi = NetworkFactory.api(server.url("/billing/api/v1/").toString(), client)
        return InvoiceRepository(api, dao, scope, flags)
    }

    private fun draft(vararg items: DraftItem, notes: String = "") = InvoiceDraft(
        customerId = 42,
        customerName = "Customer Fiktif",
        priceListId = 3,
        invoiceDate = LocalDate.of(2026, 10, 27),
        notes = notes,
        items = items.toList().ifEmpty { listOf(DraftItem(1, "Cuci kiloan", "kg", "2,5", overridePriceText = "9.000")) },
    )

    private val createdJson = """
        {"data":{"id":101,"invoice_number":"INV-2026-0101","invoice_date":"2026-10-27","customer_id":42,
        "customer_name":"Customer Fiktif","status":"Draft","notes":null,"quantity":"2.50","subtotal":17500,
        "paid_amount":"0.00","balance":17500.00,"created_at":"2026-10-27 20:30:00","updated_at":"2026-10-27 20:30:00",
        "items":[{"id":1,"service_id":1,"description":"Cuci kiloan","qty":2.5,"unit":"kg","unit_price":"7000.00","amount":17500}]}}
    """.trimIndent()

    private fun created() = MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json").setBody(createdJson)

    private fun bodyOf(request: okhttp3.mockwebserver.RecordedRequest): JsonObject =
        Json.parseToJsonElement(request.body.readUtf8()).jsonObject

    @Test
    fun `create mengirim Draft, Idempotency-Key UUID v4, tanpa business_id dan tanpa unit_price saat override nonaktif`() = runBlocking<Unit> {
        server.enqueue(created())
        val result = repo().submit(draft(notes = "Catatan fiktif"))

        val success = assertIs<ApiResult.Success<*>>(result)
        assertNotNull(success.data)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/billing/api/v1/invoices", request.path)
        assertTrue(request.getHeader("Idempotency-Key")!!.matches(uuidV4))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))

        val body = bodyOf(request)
        assertEquals("Draft", body["status"]!!.let { (it as JsonPrimitive).content })
        assertEquals("42", (body["customer_id"] as JsonPrimitive).content)
        assertEquals("2026-10-27", (body["invoice_date"] as JsonPrimitive).content)
        assertEquals("Catatan fiktif", (body["notes"] as JsonPrimitive).content)
        assertFalse(body.containsKey("business_id"))
        val item = body["items"]!!.jsonArray[0].jsonObject
        assertEquals("1", (item["service_id"] as JsonPrimitive).content)
        assertEquals("2.5", (item["qty"] as JsonPrimitive).content)
        assertFalse(item.containsKey("unit_price"), "unit_price tidak boleh terkirim saat ENABLE_PRICE_OVERRIDE=false")
        assertFalse((item["qty"] as JsonPrimitive).isString, "qty harus berupa JSON number")
    }

    @Test
    fun `unit_price terkirim hanya bila ENABLE_PRICE_OVERRIDE aktif`() = runBlocking<Unit> {
        server.enqueue(created())
        repo(FeatureFlags(priceOverride = true)).submit(draft())
        val item = bodyOf(server.takeRequest())["items"]!!.jsonArray[0].jsonObject
        assertEquals("9000", (item["unit_price"] as JsonPrimitive).content)
    }

    @Test
    fun `respons didecode sebagai BigDecimal dan draft lokal dihapus setelah sukses`() = runBlocking<Unit> {
        server.enqueue(created())
        val result = repo().submit(draft())
        val invoice = (result as ApiResult.Success).data
        assertEquals(101L, invoice.id)
        assertEquals(BigDecimal("2.50"), invoice.quantity)
        assertEquals(BigDecimal("17500"), invoice.subtotal)
        assertEquals(BigDecimal("0.00"), invoice.paid_amount)
        assertEquals(BigDecimal("17500.00"), invoice.balance)
        assertEquals(BigDecimal("7000.00"), invoice.items.single().unit_price)
        assertNull(dao.rows[scope.scopeKey], "draft harus dihapus setelah sukses")
    }

    @Test
    fun `key disimpan bersama draft sebelum request dan dipakai ulang pada retry payload sama`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(created())
        val repository = repo()
        val original = draft()

        // Percobaan 1: koneksi putus setelah request terkirim -> hasil tidak pasti, TIDAK ada retry otomatis.
        val first = repository.submit(original)
        val failure = assertIs<ApiResult.Failure>(first)
        assertTrue(failure.error.isOutcomeUncertain)
        assertEquals(1, server.requestCount, "tidak boleh ada auto-retry")
        val stored = repository.loadDraft()!!
        assertNotNull(stored.idempotencyKey)
        val firstKey = server.takeRequest().getHeader("Idempotency-Key")
        assertEquals(firstKey, stored.idempotencyKey)

        // Percobaan 2 (pengguna menekan Simpan lagi, payload sama): key yang sama.
        val second = repository.submit(stored)
        assertIs<ApiResult.Success<*>>(second)
        assertEquals(firstKey, server.takeRequest().getHeader("Idempotency-Key"))
    }

    @Test
    fun `payload berubah menghasilkan key baru`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(created())
        val repository = repo()
        repository.submit(draft())
        val stored = repository.loadDraft()!!
        val firstKey = server.takeRequest().getHeader("Idempotency-Key")

        repository.submit(stored.copy(items = listOf(DraftItem(1, "Cuci kiloan", "kg", "3"))))
        val secondKey = server.takeRequest().getHeader("Idempotency-Key")
        assertNotEquals(firstKey, secondKey)
        assertTrue(secondKey!!.matches(uuidV4))
    }

    @Test
    fun `timeout baca menghasilkan hasil tidak pasti tanpa retry`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val result = repo(client = client(readTimeoutMs = 300)).submit(draft())
        val failure = assertIs<ApiResult.Failure>(result)
        val error = assertIs<AppError.Network>(failure.error)
        assertTrue(error.timeout)
        assertTrue(error.isOutcomeUncertain)
        assertEquals(1, server.requestCount)
        assertNotNull(dao.rows[scope.scopeKey]?.idempotencyKey, "draft + key harus tetap tersimpan")
    }

    @Test
    fun `422 validation_error menampilkan pesan server dan bukan hasil tidak pasti`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().setResponseCode(422).setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"code":"validation_error","message":"Item wajib diisi.","details":{"items":"Item wajib diisi."}}}"""),
        )
        val failure = assertIs<ApiResult.Failure>(repo().submit(draft()))
        val error = assertIs<AppError.Api>(failure.error)
        assertEquals(422, error.status)
        assertEquals("validation_error", error.code)
        assertEquals("Item wajib diisi.", error.message)
        assertFalse(error.isOutcomeUncertain)
    }

    @Test
    fun `validasi sisi klien tidak mengirim request`() = runBlocking<Unit> {
        val repository = repo()
        val noCustomer = draft().copy(customerId = null)
        assertIs<ApiResult.Failure>(repository.submit(noCustomer))
        assertIs<ApiResult.Failure>(repository.submit(draft().copy(items = emptyList())))
        assertIs<ApiResult.Failure>(repository.submit(draft(DraftItem(1, "x", "kg", "0"))))
        assertIs<ApiResult.Failure>(repository.submit(draft(DraftItem(1, "x", "kg", "1.234"))))
        val tooMany = List(201) { DraftItem(it.toLong() + 1, "x", "kg", "1") }
        assertIs<ApiResult.Failure>(repository.submit(draft().copy(items = tooMany)))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `list invoice mengirim filter dan membaca meta paginasi`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"data":[{"id":1,"invoice_number":"INV-1","invoice_date":"2026-10-27","customer_id":42,"customer_name":"A",
                "status":"Sent","quantity":1,"subtotal":"1000.00","paid_amount":0,"balance":1000}],
                "meta":{"page":1,"per_page":20,"total":45,"last_page":3}}""",
            ),
        )
        val result = repo().list("abc", YearMonth.of(2026, 10), 42, "Sent", 1)
        val page = (result as ApiResult.Success).data
        assertEquals(1, page.items.size)
        assertTrue(page.hasMore)
        assertEquals(3, page.lastPage)
        val request = server.takeRequest()
        val url = request.requestUrl!!
        assertEquals("abc", url.queryParameter("q"))
        assertEquals("2026-10", url.queryParameter("month"))
        assertEquals("42", url.queryParameter("customer_id"))
        assertEquals("Sent", url.queryParameter("status"))
        assertEquals("1", url.queryParameter("page"))
    }

    @Test
    fun `perubahan status ditolak di sisi klien saat flag nonaktif`() = runBlocking<Unit> {
        val repository = repo()
        listOf("Sent", "Paid", "Cancelled", "Draft").forEach {
            assertIs<ApiResult.Failure>(repository.updateStatus(1, it))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `perubahan status dikirim bila flag aktif`() = runBlocking<Unit> {
        server.enqueue(created())
        val result = repo(FeatureFlags(markPaid = true)).updateStatus(101, "Paid")
        assertIs<ApiResult.Success<*>>(result)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/billing/api/v1/invoices/101/status", request.path)
        assertEquals("Paid", (bodyOf(request)["status"] as JsonPrimitive).content)
        // Status Cancelled tetap ditolak karena flag cancel nonaktif.
        assertIs<ApiResult.Failure>(repo(FeatureFlags(markPaid = true)).updateStatus(101, "Cancelled"))
    }

    @Test
    fun `AuthInterceptor menambahkan bearer kecuali health dan login`() = runBlocking<Unit> {
        server.enqueue(created())
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"data":{"status":"ok"}}"""))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"data":{"status":"ok"}}"""))
        val client = client(5_000, AuthInterceptor { "tok-abc" })
        val api = NetworkFactory.api(server.url("/billing/api/v1/").toString(), client)
        api.invoice(1)
        api.health()
        assertEquals("Bearer tok-abc", server.takeRequest().getHeader("Authorization"))
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `SessionInterceptor memicu hapus sesi pada 401 invalid_token dan 403 account_inactive saja`() = runBlocking<Unit> {
        var invalidated = 0
        val api = NetworkFactory.api(
            server.url("/billing/api/v1/").toString(),
            client(5_000, SessionInterceptor { invalidated++ }),
        )
        fun error(status: Int, code: String) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json")
            .setBody("""{"error":{"code":"$code","message":"m","details":{}}}""")

        server.enqueue(error(401, "invalid_token"))
        runCatching { api.invoice(1) }
        assertEquals(1, invalidated)

        server.enqueue(error(403, "account_inactive"))
        runCatching { api.invoice(1) }
        assertEquals(2, invalidated)

        server.enqueue(error(403, "forbidden"))
        runCatching { api.invoice(1) }
        server.enqueue(error(422, "validation_error"))
        runCatching { api.invoice(1) }
        assertEquals(2, invalidated)

        // login salah (401 invalid_credentials) tidak menghapus apa pun
        server.enqueue(error(401, "invalid_credentials"))
        runCatching { api.login(id.jerco.billingroyal.data.remote.LoginRequest("u", "p", "d")) }
        assertEquals(2, invalidated)
    }
}
