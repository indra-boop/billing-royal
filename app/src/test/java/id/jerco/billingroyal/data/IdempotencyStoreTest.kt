package id.jerco.billingroyal.data

import id.jerco.billingroyal.core.CanonicalJson
import id.jerco.billingroyal.data.local.PendingIdempotencyDao
import id.jerco.billingroyal.data.local.PendingIdempotencyEntity
import id.jerco.billingroyal.data.repo.CustomerInput
import id.jerco.billingroyal.data.repo.ExpenseInput
import id.jerco.billingroyal.data.repo.IdempotencyKind
import id.jerco.billingroyal.data.repo.IdempotencyStore
import id.jerco.billingroyal.data.repo.ScopeProvider
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private class FakePendingDao : PendingIdempotencyDao {
    val rows = HashMap<Pair<String, String>, PendingIdempotencyEntity>()
    override suspend fun get(scope: String, kind: String) = rows[scope to kind]
    override suspend fun upsert(row: PendingIdempotencyEntity) { rows[row.scope to row.kind] = row }
    override suspend fun delete(scope: String, kind: String) { rows.remove(scope to kind) }
}

private class MutableScope(override var scopeKey: String = "5:9") : ScopeProvider {
    override var generation = 0L
}

class IdempotencyStoreTest {
    private val uuidV4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val dao = FakePendingDao()
    private val scope = MutableScope()
    private fun store() = IdempotencyStore(dao, scope)
    private val customer = CustomerInput("Customer Fiktif", "a@example.test", "0800", "Jl. Fiktif 1", 3).toJson()
    private val t0 = 1_000_000L

    @Test
    fun `key dibuat sekali (UUID v4), disimpan sebelum request, dan dipakai ulang untuk payload sama`() = runBlocking<Unit> {
        val s = store()
        val a = s.keyFor(IdempotencyKind.CUSTOMER_CREATE, customer, t0)
        assertTrue(uuidV4.matches(a))
        assertEquals(1, dao.rows.size, "harus tersimpan di Room sebelum request dikirim")
        assertEquals(a, s.keyFor(IdempotencyKind.CUSTOMER_CREATE, customer, t0 + 1000))
    }

    @Test
    fun `key bertahan setelah proses mati (instance baru, DAO sama)`() = runBlocking<Unit> {
        val a = store().keyFor(IdempotencyKind.EXPENSE_CREATE, expense("15000"), t0)
        val afterRestart = store() // simulasi proses baru: tidak ada memori, hanya Room
        assertEquals(a, afterRestart.keyFor(IdempotencyKind.EXPENSE_CREATE, expense("15000"), t0 + 60_000))
    }

    @Test
    fun `payload berubah membuat key baru`() = runBlocking<Unit> {
        val s = store()
        val a = s.keyFor(IdempotencyKind.EXPENSE_CREATE, expense("15000"), t0)
        val b = s.keyFor(IdempotencyKind.EXPENSE_CREATE, expense("16000"), t0)
        assertNotEquals(a, b)
    }

    @Test
    fun `clear setelah sukses membuat aksi berikutnya memakai key baru`() = runBlocking<Unit> {
        val s = store()
        val a = s.keyFor(IdempotencyKind.CUSTOMER_CREATE, customer, t0)
        s.clear(IdempotencyKind.CUSTOMER_CREATE)
        assertTrue(dao.rows.isEmpty())
        assertNotEquals(a, s.keyFor(IdempotencyKind.CUSTOMER_CREATE, customer, t0))
    }

    @Test
    fun `key lebih tua dari TTL tidak dipakai ulang`() = runBlocking<Unit> {
        val s = store()
        val a = s.keyFor(IdempotencyKind.EXPENSE_CREATE, expense("15000"), t0)
        assertEquals(a, s.keyFor(IdempotencyKind.EXPENSE_CREATE, expense("15000"), t0 + IdempotencyStore.TTL_MILLIS))
        assertNotEquals(a, s.keyFor(IdempotencyKind.EXPENSE_CREATE, expense("15000"), t0 + IdempotencyStore.TTL_MILLIS + 1))
    }

    @Test
    fun `terpisah per scope user-bisnis dan per jenis aksi`() = runBlocking<Unit> {
        val s = store()
        val a = s.keyFor(IdempotencyKind.CUSTOMER_CREATE, customer, t0)
        scope.scopeKey = "6:9" // user lain di perangkat yang sama
        val b = s.keyFor(IdempotencyKind.CUSTOMER_CREATE, customer, t0)
        assertNotEquals(a, b)
        scope.scopeKey = "5:9"
        assertEquals(a, s.keyFor(IdempotencyKind.CUSTOMER_CREATE, customer, t0))
        assertNotEquals(a, s.keyFor(IdempotencyKind.EXPENSE_CREATE, customer, t0))
    }

    @Test
    fun `tanpa sesi tidak ada baris yang disimpan`() = runBlocking<Unit> {
        scope.scopeKey = "none"
        val s = store()
        s.keyFor(IdempotencyKind.CUSTOMER_CREATE, customer, t0)
        s.clear(IdempotencyKind.CUSTOMER_CREATE)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `reusable menolak hash berbeda, umur negatif, dan baris kosong`() {
        val row = PendingIdempotencyEntity("5:9", "x", "k", "h1", 1000)
        assertEquals("k", IdempotencyStore.reusable(row, "h1", 1000))
        assertNull(IdempotencyStore.reusable(row, "h2", 1000))
        assertNull(IdempotencyStore.reusable(row, "h1", 999)) // jam mundur: jangan dipakai
        assertNull(IdempotencyStore.reusable(null, "h1", 1000))
    }

    private fun expense(amount: String) =
        ExpenseInput(LocalDate.of(2026, 10, 27), "Listrik", "Token listrik", BigDecimal(amount), "Cash").toJson()
}

class CanonicalJsonTest {
    @Test
    fun `urutan kunci tidak memengaruhi hash`() {
        val a = JsonObject(mapOf("b" to JsonPrimitive(1), "a" to JsonPrimitive("x")))
        val b = JsonObject(mapOf("a" to JsonPrimitive("x"), "b" to JsonPrimitive(1)))
        assertEquals(CanonicalJson.canonicalize(a), CanonicalJson.canonicalize(b))
        assertEquals(CanonicalJson.sha256Hex(a), CanonicalJson.sha256Hex(b))
        assertEquals("""{"a":"x","b":1}""", CanonicalJson.canonicalize(a))
    }

    @Test
    fun `objek bersarang, array, null, boolean, dan escape string`() {
        val o = JsonObject(
            mapOf(
                "z" to JsonArray(listOf(JsonPrimitive(2), JsonNull, JsonPrimitive(true))),
                "m" to JsonObject(mapOf("y" to JsonPrimitive("a\"b\\c\n"), "x" to JsonPrimitive("15000.00"))),
            ),
        )
        assertEquals("""{"m":{"x":"15000.00","y":"a\"b\\c\u000a"},"z":[2,null,true]}""", CanonicalJson.canonicalize(o))
    }

    @Test
    fun `string angka berbeda dari angka`() {
        assertNotEquals(
            CanonicalJson.sha256Hex(JsonObject(mapOf("a" to JsonPrimitive("1")))),
            CanonicalJson.sha256Hex(JsonObject(mapOf("a" to JsonPrimitive(1)))),
        )
    }

    @Test
    fun `hash input customer stabil terhadap spasi pinggir (dinormalkan oleh toJson) dan berubah bila isi berubah`() {
        val h1 = CanonicalJson.sha256Hex(CustomerInput("  Budi ", "", "", "", null).toJson())
        val h2 = CanonicalJson.sha256Hex(CustomerInput("Budi", "", "", "", null).toJson())
        val h3 = CanonicalJson.sha256Hex(CustomerInput("Budi", "", "081", "", null).toJson())
        assertEquals(h1, h2)
        assertNotEquals(h1, h3)
        assertEquals(64, h1.length)
    }
}
