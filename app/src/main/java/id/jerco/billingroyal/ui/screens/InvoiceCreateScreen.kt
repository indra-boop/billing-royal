package id.jerco.billingroyal.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import id.jerco.billingroyal.core.AppDates
import id.jerco.billingroyal.core.FeatureFlags
import id.jerco.billingroyal.core.DraftCustomerCheck
import id.jerco.billingroyal.core.InvoicePreview
import id.jerco.billingroyal.core.LatestOnly
import id.jerco.billingroyal.core.MoneyFormatter
import id.jerco.billingroyal.core.MoneyParser
import id.jerco.billingroyal.data.remote.ApiResult
import id.jerco.billingroyal.data.remote.AppError
import id.jerco.billingroyal.data.remote.displayMessage
import id.jerco.billingroyal.data.remote.CustomerDto
import id.jerco.billingroyal.data.remote.InvoiceDto
import id.jerco.billingroyal.data.remote.ServiceDto
import id.jerco.billingroyal.data.remote.isOutcomeUncertain
import id.jerco.billingroyal.data.repo.CatalogRepository
import id.jerco.billingroyal.data.repo.CustomerRepository
import id.jerco.billingroyal.data.repo.DraftItem
import id.jerco.billingroyal.data.repo.InvoiceDraft
import id.jerco.billingroyal.data.repo.InvoiceRepository
import id.jerco.billingroyal.ui.components.AppScaffold
import id.jerco.billingroyal.ui.components.ConfirmDialog
import id.jerco.billingroyal.ui.components.DateField
import id.jerco.billingroyal.ui.components.Load
import id.jerco.billingroyal.ui.components.SavingButton
import id.jerco.billingroyal.ui.components.StatusBadge
import id.jerco.billingroyal.ui.components.UncertainDialog
import id.jerco.billingroyal.ui.components.WarningBox
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val DRAFT_PRICE_LIST_CHANGED =
    "Price list customer berubah sejak draft disimpan. Harga diperbarui sesuai price list terbaru; periksa kembali preview."
private const val DRAFT_CUSTOMER_GONE =
    "Customer pada draft ini tidak ditemukan lagi di server. Pilih customer lagi."
private const val DRAFT_UNVERIFIED =
    "Customer dan harga pada draft belum bisa dicocokkan dengan server (jaringan). Harga di preview mungkin tidak terbaru."

private const val RESTORED_UNCERTAIN_MESSAGE =
    "Pengiriman invoice sebelumnya belum diketahui hasilnya (koneksi terputus, waktu habis, atau aplikasi sempat " +
        "tertutup). Invoice mungkin SUDAH tersimpan di server. Periksa daftar invoice terlebih dahulu sebelum mengirim ulang."

data class UncertainUi(
    val message: String,
    val checking: Boolean = false,
    val matches: List<InvoiceDto>? = null,
    val checkError: String? = null,
)

data class InvoiceFormUi(
    val restoring: Boolean = true,
    val customerId: Long? = null,
    val customerName: String? = null,
    val priceListId: Long? = null,
    val date: LocalDate = AppDates.today(),
    val notes: String = "",
    val items: List<DraftItem> = emptyList(),
    val prices: Map<Long, BigDecimal> = emptyMap(),
    val pricesLoading: Boolean = false,
    val pricesError: String? = null,
    val saving: Boolean = false,
    val error: String? = null,
    val uncertain: UncertainUi? = null,
    /** Draft yang dipulihkan sedang dicocokkan dengan data customer/price list terbaru di server. */
    val draftChecking: Boolean = false,
    /** Catatan hasil pencocokan draft (price list berubah, customer tidak ada, tidak bisa diperiksa). */
    val draftNotice: String? = null,
    /** Pesan singkat (mis. item duplikat/batas item) yang tidak mengganggu simpan. */
    val itemNotice: String? = null,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class InvoiceCreateViewModel @Inject constructor(
    private val invoices: InvoiceRepository,
    private val customers: CustomerRepository,
    private val catalog: CatalogRepository,
    val flags: FeatureFlags,
) : ViewModel() {

    private val _ui = MutableStateFlow(InvoiceFormUi())
    val ui: StateFlow<InvoiceFormUi> = _ui

    private val _created = MutableStateFlow<Long?>(null)
    val created: StateFlow<Long?> = _created

    // Idempotency-Key + hash payload yang tersimpan bersama draft (sumber kebenaran: Room).
    private var storedKey: String? = null
    private var storedHash: String? = null
    private val draftMutex = Mutex()

    // Ada percobaan kirim sebelumnya yang hasilnya belum pasti (penanda tersimpan di Room, bertahan setelah proses mati).
    private var uncertainPending = false
    // Pengguna sudah menyetujui "Kirim ulang" (konfirmasi kedua) pada sesi layar ini.
    private var resendConfirmed = false
    // Draft sudah berhasil dikirim: autosave yang tertunda tidak boleh menghidupkan kembali draft.
    private var submitted = false
    private val latestPrices = LatestOnly(viewModelScope)

    // ---- picker customer (pencarian di server, debounce 300 ms; baru aktif saat dialog dibuka)
    private val customerQuery = MutableStateFlow<String?>(null)
    private val _customerResults = MutableStateFlow<Load<List<CustomerDto>>>(Load.Loading)
    val customerResults: StateFlow<Load<List<CustomerDto>>> = _customerResults

    // ---- picker service (katalog dimuat sekali, difilter lokal)
    private val _services = MutableStateFlow<Load<List<ServiceDto>>>(Load.Loading)
    val services: StateFlow<Load<List<ServiceDto>>> = _services
    private var servicesRequested = false

    init {
        viewModelScope.launch {
            customerQuery.filterNotNull()
                .debounce { if (it.isEmpty()) 0L else 300L }
                .distinctUntilChanged()
                .collectLatest { q ->
                    _customerResults.value = Load.Loading
                    _customerResults.value = when (val r = customers.list(q, 1)) {
                        is ApiResult.Success -> Load.Content(r.data.items)
                        is ApiResult.Failure -> Load.Error(r.error.message)
                    }
                }
        }
        viewModelScope.launch {
            val draft = invoices.loadDraft()
            if (draft != null) {
                storedKey = draft.idempotencyKey
                storedHash = draft.payloadHash
                uncertainPending = draft.uncertainSince != null
                _ui.update {
                    it.copy(
                        restoring = false,
                        uncertain = if (uncertainPending) UncertainUi(RESTORED_UNCERTAIN_MESSAGE, checking = true) else null,
                        customerId = draft.customerId,
                        customerName = draft.customerName,
                        priceListId = draft.priceListId,
                        date = draft.invoiceDate,
                        notes = draft.notes,
                        items = draft.items,
                    )
                }
                if (draft.customerId != null) {
                    loadPrices(draft.priceListId)
                    refreshRestoredCustomer(draft.customerId, draft.priceListId)
                }
                if (uncertainPending) checkExisting(draft.customerId, draft.invoiceDate)
            } else {
                _ui.update { it.copy(restoring = false) }
            }
        }
    }

    fun openCustomerPicker() {
        if (customerQuery.value == null) customerQuery.value = ""
    }

    fun searchCustomers(q: String) {
        customerQuery.value = q.trim()
    }

    fun openServicePicker() {
        if (servicesRequested && _services.value is Load.Content) return
        loadServices()
    }

    fun loadServices() {
        servicesRequested = true
        _services.value = Load.Loading
        viewModelScope.launch {
            _services.value = when (val r = catalog.services("")) {
                is ApiResult.Success -> Load.Content(r.data)
                is ApiResult.Failure -> Load.Error(r.error.message)
            }
        }
    }

    fun selectCustomer(c: CustomerDto) {
        _ui.update {
            it.copy(
                customerId = c.id, customerName = c.name, priceListId = c.price_list_id, prices = emptyMap(),
                error = null, draftNotice = null, draftChecking = false,
            )
        }
        loadPrices(c.price_list_id)
        persist()
    }

    /**
     * Draft yang dipulihkan memakai customer/price list yang tersimpan saat draft dibuat. Ambil ulang customer dari
     * server: bila price list berubah, harga lama tidak dipakai (dimuat ulang) dan operator diberi tahu; bila customer
     * hilang, operator harus memilih ulang; bila tidak bisa diperiksa, preview diberi tanda "mungkin tidak terbaru".
     */
    private fun refreshRestoredCustomer(customerId: Long, draftPriceListId: Long?) {
        _ui.update { it.copy(draftChecking = true) }
        viewModelScope.launch {
            val check = DraftCustomerCheck.evaluate(draftPriceListId, customers.get(customerId))
            // Pengguna sudah memilih customer lain selama pengecekan: abaikan hasil lama.
            if (_ui.value.customerId != customerId) {
                _ui.update { it.copy(draftChecking = false) }
                return@launch
            }
            when (check) {
                DraftCustomerCheck.Unchanged -> _ui.update { it.copy(draftChecking = false) }
                is DraftCustomerCheck.PriceListChanged -> {
                    _ui.update {
                        it.copy(
                            draftChecking = false,
                            customerName = check.customer.name,
                            priceListId = check.customer.price_list_id,
                            prices = emptyMap(),
                            draftNotice = DRAFT_PRICE_LIST_CHANGED,
                        )
                    }
                    loadPrices(check.customer.price_list_id)
                    persist()
                }
                DraftCustomerCheck.CustomerGone -> _ui.update {
                    it.copy(
                        draftChecking = false,
                        customerId = null,
                        customerName = null,
                        priceListId = null,
                        prices = emptyMap(),
                        pricesLoading = false,
                        draftNotice = DRAFT_CUSTOMER_GONE,
                    )
                }
                DraftCustomerCheck.Unverified -> _ui.update { it.copy(draftChecking = false, draftNotice = DRAFT_UNVERIFIED) }
            }
        }
    }

    fun loadPrices(priceListId: Long? = _ui.value.priceListId) {
        _ui.update { it.copy(pricesLoading = true, pricesError = null) }
        // Hanya permintaan TERBARU yang boleh menerapkan hasil: respons price list customer A tidak boleh menimpa
        // harga customer B bila operator berganti customer dengan cepat.
        latestPrices.launch({ catalog.priceMap(priceListId) }) { r ->
            when (r) {
                is ApiResult.Success -> _ui.update { it.copy(prices = r.data, pricesLoading = false) }
                is ApiResult.Failure -> _ui.update { it.copy(pricesLoading = false, pricesError = r.error.message) }
            }
        }
    }

    fun setDate(date: LocalDate) {
        _ui.update { it.copy(date = date, error = null) }
        persist()
    }

    fun setNotes(text: String) {
        _ui.update { it.copy(notes = text, error = null) }
        persist()
    }

    fun addService(s: ServiceDto) {
        _ui.update {
            when {
                it.items.any { i -> i.serviceId == s.id } ->
                    it.copy(itemNotice = "\"${s.name}\" sudah ada di invoice. Ubah qty pada item tersebut.")
                it.items.size >= InvoiceRepository.MAX_ITEMS ->
                    it.copy(itemNotice = "Maksimal ${InvoiceRepository.MAX_ITEMS} item per invoice.")
                else -> it.copy(items = it.items + DraftItem(s.id, s.name, s.unit, "1"), error = null, itemNotice = null)
            }
        }
        persist()
    }

    fun dismissItemNotice() = _ui.update { it.copy(itemNotice = null) }

    fun setQty(index: Int, text: String) = mutateItem(index) { it.copy(qtyText = text) }
    fun setOverridePrice(index: Int, text: String) = mutateItem(index) { it.copy(overridePriceText = text) }

    fun removeItem(index: Int) {
        _ui.update { it.copy(items = it.items.filterIndexed { i, _ -> i != index }) }
        persist()
    }

    private fun mutateItem(index: Int, f: (DraftItem) -> DraftItem) {
        _ui.update { s -> s.copy(items = s.items.mapIndexed { i, item -> if (i == index) f(item) else item }, error = null) }
        persist()
    }

    fun discardDraft() {
        viewModelScope.launch {
            draftMutex.withLock {
                invoices.clearDraft()
                storedKey = null
                storedHash = null
                uncertainPending = false
                resendConfirmed = false
            }
            _ui.value = InvoiceFormUi(restoring = false)
        }
    }

    private fun currentDraft(): InvoiceDraft {
        val s = _ui.value
        return InvoiceDraft(
            customerId = s.customerId,
            customerName = s.customerName,
            priceListId = s.priceListId,
            invoiceDate = s.date,
            notes = s.notes,
            items = s.items,
            idempotencyKey = storedKey,
            payloadHash = storedHash,
        )
    }

    /** Menyimpan draft lokal otomatis (bukan antrean tulis offline: tidak pernah dikirim sendiri). */
    private fun persist() {
        viewModelScope.launch {
            draftMutex.withLock {
                if (submitted) return@withLock
                val s = _ui.value
                if (s.customerId == null && s.items.isEmpty() && s.notes.isBlank()) invoices.clearDraft()
                else invoices.saveDraft(currentDraft())
            }
        }
    }

    /** Simpan: dikunci saat request berjalan; TIDAK ada auto-retry bila hasil tidak pasti. */
    fun save() {
        if (_ui.value.saving || submitted) return
        if (uncertainPending && !resendConfirmed) {
            // Ada percobaan sebelumnya yang hasilnya belum pasti: jangan kirim lagi sebelum pengguna memeriksa daftar.
            _ui.update { it.copy(uncertain = UncertainUi(RESTORED_UNCERTAIN_MESSAGE, checking = true)) }
            checkExisting()
            return
        }
        _ui.update { it.copy(saving = true, error = null, uncertain = null) }
        viewModelScope.launch {
            val submittedDate = _ui.value.date
            val submittedCustomer = _ui.value.customerId
            val result = draftMutex.withLock {
                val r = invoices.submit(currentDraft())
                // Ambil kembali key/hash yang dipakai agar pengiriman ulang payload sama memakai key yang sama.
                val saved = invoices.loadDraft()
                storedKey = saved?.idempotencyKey
                storedHash = saved?.payloadHash
                uncertainPending = saved?.uncertainSince != null
                if (r is ApiResult.Success) submitted = true
                r
            }
            when (result) {
                is ApiResult.Success -> {
                    _ui.update { it.copy(saving = false) }
                    _created.value = result.data.id
                }
                is ApiResult.Failure -> {
                    if (result.error.isOutcomeUncertain) {
                        _ui.update {
                            it.copy(
                                saving = false,
                                uncertain = UncertainUi(
                                    "Koneksi terputus atau waktu habis setelah invoice dikirim. Invoice mungkin sudah tersimpan " +
                                        "di server. Periksa daftar invoice terlebih dahulu sebelum mengirim ulang.",
                                    checking = true,
                                ),
                            )
                        }
                        checkExisting(submittedCustomer, submittedDate)
                    } else {
                        val err = result.error
                        val text = if (err is AppError.Network && err.notSent) {
                            err.message + " Invoice belum tersimpan; coba simpan lagi setelah terhubung."
                        } else {
                            err.displayMessage // 422: sertakan rincian per field
                        }
                        _ui.update { it.copy(saving = false, error = text) }
                    }
                }
            }
        }
    }

    /** Refresh daftar invoice (filter customer + tanggal hari ini) agar pengguna bisa memeriksa dulu. */
    fun checkExisting(customerId: Long? = _ui.value.customerId, date: LocalDate = _ui.value.date) {
        _ui.update { s -> s.uncertain?.let { s.copy(uncertain = it.copy(checking = true, checkError = null)) } ?: s }
        viewModelScope.launch {
            val today = AppDates.today()
            val result = invoices.list("", YearMonth.from(date), customerId, null, 1, 50)
            _ui.update { s ->
                val u = s.uncertain ?: return@update s
                when (result) {
                    is ApiResult.Success -> s.copy(
                        uncertain = u.copy(
                            checking = false,
                            matches = result.data.items.filter {
                                AppDates.parseApiDate(it.invoice_date) == date ||
                                    AppDates.parseServerDateTime(it.created_at)?.toLocalDate() == today
                            },
                        ),
                    )
                    is ApiResult.Failure -> s.copy(uncertain = u.copy(checking = false, checkError = result.error.message))
                }
            }
        }
    }

    fun dismissUncertain() = _ui.update { it.copy(uncertain = null) }

    /** Konfirmasi KEDUA "Kirim ulang" sudah diberikan pengguna: kirim lagi dengan Idempotency-Key yang sama. */
    fun confirmResend() {
        resendConfirmed = true
        dismissUncertain()
        save()
    }
    fun consumeCreated() { _created.value = null }
}

@Composable
fun InvoiceCreateScreen(
    onBack: () -> Unit,
    onCheckList: () -> Unit,
    onCreated: (Long) -> Unit,
    viewModel: InvoiceCreateViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val created by viewModel.created.collectAsStateWithLifecycle()
    var pickCustomer by remember { mutableStateOf(false) }
    var pickService by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(created) {
        created?.let { viewModel.consumeCreated(); onCreated(it) }
    }

    val flags = viewModel.flags
    // Harga dianggap diketahui hanya bila sudah selesai dimuat tanpa error (atau belum ada customer).
    val pricesKnown = ui.customerId == null || (!ui.pricesLoading && ui.pricesError == null)
    val preview = remember(ui.items, ui.prices, flags, pricesKnown) {
        InvoicePreview.compute(ui.items, ui.prices, flags, pricesKnown)
    }

    AppScaffold(title = "Buat Invoice", onBack = onBack) { padding ->
        if (ui.restoring) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@AppScaffold
        }
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { viewModel.openCustomerPicker(); pickCustomer = true },
                enabled = !ui.saving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(ui.customerName?.let { "Customer: $it" } ?: "Pilih customer") }

            DateField("Tanggal", ui.date, viewModel::setDate, Modifier.fillMaxWidth(), enabled = !ui.saving)

            ui.draftNotice?.let { WarningBox(it) }
            if (ui.draftChecking) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("  Mencocokkan draft dengan data terbaru...", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (ui.customerId != null && ui.priceListId == null) {
                WarningBox("Customer ini belum memiliki price list, sehingga harga item tidak ditemukan (preview Rp 0).")
            }
            ui.pricesError?.let { msg ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Harga tidak dapat dimuat: $msg", color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.loadPrices() }) { Text("Coba lagi") }
                }
            }

            Text("Item (${ui.items.size}/${InvoiceRepository.MAX_ITEMS})", style = MaterialTheme.typography.titleMedium)
            if (ui.items.isEmpty()) Text("Belum ada item.", style = MaterialTheme.typography.bodyMedium)
            preview.lines.forEachIndexed { index, line ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(line.item.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Harga: " + when (line.state) {
                                        InvoicePreview.PriceState.UNKNOWN -> "belum dimuat"
                                        InvoicePreview.PriceState.NOT_IN_PRICE_LIST -> "tidak ada di price list (Rp 0)"
                                        InvoicePreview.PriceState.ZERO_PRICE -> "Rp 0"
                                        InvoicePreview.PriceState.OK -> MoneyFormatter.format(line.unitPrice)
                                    } + (if (line.item.unit.isNotBlank() && line.state != InvoicePreview.PriceState.UNKNOWN) " / ${line.item.unit}" else ""),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            IconButton(onClick = { viewModel.removeItem(index) }, enabled = !ui.saving) {
                                Icon(Icons.Default.Delete, contentDescription = "Hapus item")
                            }
                        }
                        OutlinedTextField(
                            value = line.item.qtyText,
                            onValueChange = { viewModel.setQty(index, it) },
                            label = { Text("Qty" + if (line.item.unit.isNotBlank()) " (${line.item.unit})" else "") },
                            singleLine = true,
                            isError = line.qty == null,
                            supportingText = { if (line.qty == null) Text("Lebih dari 0, maksimal 2 desimal") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (flags.priceOverride) {
                            OutlinedTextField(
                                value = line.item.overridePriceText,
                                onValueChange = { viewModel.setOverridePrice(index, it) },
                                label = { Text("Harga satuan khusus (opsional)") },
                                singleLine = true,
                                isError = line.item.overridePriceText.isNotBlank() && MoneyParser.parseAmount(line.item.overridePriceText) == null,
                                supportingText = {
                                    val parsed = MoneyParser.parseAmount(line.item.overridePriceText)
                                    when {
                                        line.item.overridePriceText.isBlank() -> Unit
                                        parsed == null -> Text("Nominal tidak valid. Contoh: 15.000 atau 15.000,50")
                                        else -> Text("Dibaca sebagai " + MoneyFormatter.format(parsed))
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Text("Perkiraan jumlah (belum final): " + MoneyFormatter.format(line.amount), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            OutlinedButton(
                onClick = { viewModel.openServicePicker(); pickService = true },
                enabled = !ui.saving && ui.items.size < InvoiceRepository.MAX_ITEMS,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Tambah item dari katalog") }
            ui.itemNotice?.let { msg ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::dismissItemNotice) { Text("Tutup") }
                }
            }

            // Hanya bila harga sudah diketahui: jangan menyalahkan "tanpa harga" saat harga masih dimuat/gagal dimuat
            // (penyebab sebenarnya sudah ditampilkan sebagai galat di atas).
            if (ui.items.isNotEmpty() && pricesKnown && preview.problemCount > 0) {
                WarningBox(
                    "${preview.problemCount} item berharga Rp 0 atau tidak ada di price list customer. " +
                        "Nilai ini dikirim apa adanya; periksa sebelum menyimpan.",
                )
            }

            OutlinedTextField(
                value = ui.notes,
                onValueChange = viewModel::setNotes,
                label = { Text("Catatan") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Perkiraan subtotal (preview)", style = MaterialTheme.typography.labelMedium)
                    Text(MoneyFormatter.format(preview.subtotal), style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Hanya perkiraan: server yang menentukan subtotal dan total final (termasuk pembulatan).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (ui.draftChecking || ui.pricesLoading || ui.pricesError != null || ui.draftNotice == DRAFT_UNVERIFIED) {
                        Text(
                            "Harga belum final atau mungkin tidak terbaru; angka di atas bisa berubah.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Text("Invoice baru selalu disimpan dengan status Draft.", style = MaterialTheme.typography.bodySmall)
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            SavingButton(
                text = "Simpan",
                saving = ui.saving,
                enabled = ui.customerId != null && ui.items.isNotEmpty(),
                onClick = viewModel::save,
            )
            TextButton(onClick = { confirmDiscard = true }, enabled = !ui.saving) { Text("Kosongkan draft") }
        }
    }

    if (pickCustomer) {
        val results by viewModel.customerResults.collectAsStateWithLifecycle()
        SearchPickerDialog(
            title = "Pilih customer",
            state = results,
            onQuery = viewModel::searchCustomers,
            serverSearch = true,
            label = { it.name },
            sub = { it.phone?.takeIf { p -> p.isNotBlank() } },
            matches = { _, _ -> true },
            onRetry = { viewModel.searchCustomers("") },
            onPick = { viewModel.selectCustomer(it); pickCustomer = false },
            onDismiss = { pickCustomer = false },
        )
    }
    if (pickService) {
        val services by viewModel.services.collectAsStateWithLifecycle()
        SearchPickerDialog(
            title = "Pilih item",
            state = services,
            onQuery = {},
            serverSearch = false,
            label = { it.name },
            sub = { s ->
                val price = ui.prices[s.id]?.let { MoneyFormatter.format(it) } ?: if (ui.customerId != null) "harga tidak ada" else null
                listOfNotNull(s.category.takeIf { it.isNotBlank() }, price).joinToString("  |  ").ifBlank { null }
            },
            matches = { s, q -> s.name.contains(q, true) || s.category.contains(q, true) || (s.item_code?.contains(q, true) == true) },
            onRetry = viewModel::loadServices,
            onPick = { viewModel.addService(it); pickService = false },
            onDismiss = { pickService = false },
        )
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = "Kosongkan draft",
            message = "Draft invoice lokal akan dihapus.",
            confirmText = "Kosongkan",
            onConfirm = { confirmDiscard = false; viewModel.discardDraft() },
            onDismiss = { confirmDiscard = false },
        )
    }
    ui.uncertain?.let { u ->
        UncertainDialog(
            message = u.message,
            onCheck = { viewModel.dismissUncertain(); onCheckList() },
            onResend = viewModel::confirmResend,
            resendWarning = "Invoice mungkin sudah tersimpan di server. Mengirim ulang dapat membuat invoice GANDA. " +
                "Lanjutkan hanya bila Anda sudah memeriksa daftar invoice dan invoice ini belum ada.",
            onDismiss = viewModel::dismissUncertain,
            extra = {
                when {
                    u.checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                        Text("  Memeriksa daftar invoice...")
                    }
                    u.checkError != null -> Column {
                        Text(u.checkError, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { viewModel.checkExisting() }) { Text("Periksa lagi") }
                    }
                    u.matches != null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (u.matches.isEmpty()) {
                            Text("Tidak ada invoice customer ini pada tanggal tersebut.")
                        } else {
                            Text("Invoice customer ini hari ini/pada tanggal tersebut:")
                            Text(
                                "Bila salah satunya adalah invoice yang barusan Anda simpan, hapus draft ini agar tidak terkirim dua kali.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = { viewModel.dismissUncertain(); viewModel.discardDraft() }) {
                                Text("Invoice sudah ada, hapus draft")
                            }
                            u.matches.take(5).forEach { inv ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("${inv.invoice_number} - ${MoneyFormatter.format(inv.subtotal)}", Modifier.weight(1f))
                                    StatusBadge(inv.status)
                                }
                            }
                        }
                    }
                }
            },
        )
    }
}

/** Dialog pilih-dengan-pencarian. Bila [serverSearch] maka [onQuery] memicu pencarian di server; selain itu difilter lokal. */
@Composable
fun <T> SearchPickerDialog(
    title: String,
    state: Load<List<T>>,
    onQuery: (String) -> Unit,
    serverSearch: Boolean,
    label: (T) -> String,
    sub: (T) -> String?,
    matches: (T, String) -> Boolean,
    onRetry: () -> Unit,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; if (serverSearch) onQuery(it) },
                    label = { Text("Cari") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp)) {
                    when (state) {
                        Load.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        is Load.Error -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(state.message, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = onRetry) { Text("Coba lagi") }
                        }
                        is Load.Content -> {
                            val shown = if (serverSearch || query.isBlank()) state.data else state.data.filter { matches(it, query.trim()) }
                            if (shown.isEmpty()) {
                                Text("Tidak ada hasil.", Modifier.align(Alignment.Center))
                            } else {
                                LazyColumn {
                                    items(shown) { item ->
                                        Column(Modifier.fillMaxWidth().clickable { onPick(item) }.padding(vertical = 10.dp)) {
                                            Text(label(item), style = MaterialTheme.typography.bodyLarge)
                                            sub(item)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                        }
                                        HorizontalDivider()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
    )
}
