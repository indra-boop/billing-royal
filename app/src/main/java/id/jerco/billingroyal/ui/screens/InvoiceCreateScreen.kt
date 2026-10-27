package id.jerco.billingroyal.ui.screens

import java.util.Locale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.activity.compose.BackHandler
import androidx.compose.ui.semantics.Role
import id.jerco.billingroyal.ui.components.minTouch
import id.jerco.billingroyal.R
import androidx.compose.ui.res.stringResource
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
        "tertutup), jadi invoice mungkin sudah tersimpan. Periksa dulu agar tidak membuat invoice ganda."

private const val SENT_UNCERTAIN_MESSAGE =
    "Koneksi terputus saat invoice dikirim, jadi kami belum tahu hasilnya. Periksa dulu agar tidak membuat invoice ganda."

data class UncertainUi(
    val message: String,
    val checking: Boolean = false,
    val matches: List<InvoiceDto>? = null,
    val checkError: String? = null,
    /** Perkiraan subtotal draft saat dikirim: pembanding untuk mengenali invoice yang barusan dikirim. */
    val estimate: BigDecimal? = null,
    val customerName: String? = null,
    val date: LocalDate? = null,
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

    /** Urungkan hapus item (Snackbar "Urungkan"): kembalikan item ke posisi semula bila service-nya belum ada lagi. */
    fun restoreItem(index: Int, item: DraftItem) {
        _ui.update {
            if (it.items.any { i -> i.serviceId == item.serviceId }) it
            else it.copy(items = it.items.toMutableList().also { l -> l.add(index.coerceIn(0, l.size), item) })
        }
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
                                uncertain = UncertainUi(SENT_UNCERTAIN_MESSAGE, checking = true),
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
        val now = _ui.value
        val estimate = InvoicePreview.compute(now.items, now.prices, flags, pricesKnown = true).subtotal
        _ui.update { s ->
            s.uncertain?.let {
                s.copy(uncertain = it.copy(checking = true, checkError = null, estimate = estimate, customerName = now.customerName, date = date))
            } ?: s
        }
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
    var confirmDiscardInvoice by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var slowSave by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val itemRemovedText = stringResource(R.string.item_dihapus)
    val undoText = stringResource(R.string.urungkan)

    LaunchedEffect(created) {
        created?.let { viewModel.consumeCreated(); onCreated(it) }
    }
    // Koneksi lambat: setelah 8 detik menyimpan, beri tahu agar layar tidak ditutup (UX-09).
    LaunchedEffect(ui.saving) {
        slowSave = false
        if (ui.saving) { delay(8_000); slowSave = true }
    }
    // Keluar saat request berjalan membuat hasil tidak pasti: minta konfirmasi dulu.
    BackHandler(enabled = ui.saving) { confirmLeave = true }

    val flags = viewModel.flags
    // Harga dianggap diketahui hanya bila sudah selesai dimuat tanpa error (atau belum ada customer).
    val pricesKnown = ui.customerId == null || (!ui.pricesLoading && ui.pricesError == null)
    val preview = remember(ui.items, ui.prices, flags, pricesKnown) {
        InvoicePreview.compute(ui.items, ui.prices, flags, pricesKnown)
    }
    val canSave = ui.customerId != null && ui.items.isNotEmpty()

    AppScaffold(
        title = stringResource(R.string.buat_invoice),
        onBack = { if (ui.saving) confirmLeave = true else onBack() },
        snackbarHostState = snackbarHostState,
        // Simpan tetap terlihat (satu tangan, UX-06): bilah bawah berisi perkiraan, galat, dan tombol Simpan.
        bottomBar = {
            if (!ui.restoring) {
                Surface(tonalElevation = 3.dp, modifier = Modifier.imePadding()) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ui.error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                        }
                        if (slowSave) {
                            Text(
                                stringResource(R.string.koneksi_lambat_tunggu),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                        if (!canSave && !ui.saving) {
                            Text(stringResource(R.string.simpan_syarat), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            stringResource(R.string.perkiraan_dan_jumlah_item, MoneyFormatter.format(preview.subtotal), ui.items.size),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        SavingButton(
                            text = stringResource(R.string.simpan),
                            saving = ui.saving,
                            enabled = canSave,
                            onClick = viewModel::save,
                        )
                    }
                }
            }
        },
    ) { padding ->
        if (ui.restoring) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@AppScaffold
        }
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { viewModel.openCustomerPicker(); pickCustomer = true },
                enabled = !ui.saving,
                modifier = Modifier.fillMaxWidth().minTouch(),
            ) { Text(ui.customerName?.let { stringResource(R.string.customer_dipilih, it) } ?: stringResource(R.string.pilih_customer)) }

            DateField(stringResource(R.string.tanggal), ui.date, viewModel::setDate, Modifier.fillMaxWidth(), enabled = !ui.saving)

            ui.draftNotice?.let { WarningBox(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            if (ui.draftChecking) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.mencocokkan_draft_dengan_data_terbaru), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (ui.customerId != null && ui.priceListId == null) {
                WarningBox(stringResource(R.string.customer_ini_belum_memiliki_price))
            }
            ui.pricesError?.let { msg ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.harga_tidak_dapat_dimuat, msg),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    TextButton(onClick = { viewModel.loadPrices() }) { Text(stringResource(R.string.coba_lagi)) }
                }
            }

            Text(
                stringResource(R.string.item_hitung, ui.items.size, InvoiceRepository.MAX_ITEMS),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            if (ui.items.isEmpty()) Text(stringResource(R.string.belum_ada_item), style = MaterialTheme.typography.bodyMedium)
            preview.lines.forEachIndexed { index, line ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(line.item.name, style = MaterialTheme.typography.titleSmall)
                                val priceText = when (line.state) {
                                    InvoicePreview.PriceState.UNKNOWN -> stringResource(R.string.harga_belum_dimuat)
                                    InvoicePreview.PriceState.NOT_IN_PRICE_LIST -> stringResource(R.string.harga_tidak_ada_di_price_list)
                                    InvoicePreview.PriceState.ZERO_PRICE -> stringResource(R.string.harga_nol)
                                    InvoicePreview.PriceState.OK -> MoneyFormatter.format(line.unitPrice)
                                }
                                val unitText = if (line.item.unit.isNotBlank() && line.state != InvoicePreview.PriceState.UNKNOWN) " / ${line.item.unit}" else ""
                                Text(stringResource(R.string.harga_label, priceText + unitText), style = MaterialTheme.typography.bodySmall)
                            }
                            val removeLabel = stringResource(R.string.hapus_item_nama, line.item.name)
                            IconButton(
                                onClick = {
                                    val removed = line.item
                                    viewModel.removeItem(index)
                                    scope.launch {
                                        snackbarHostState.currentSnackbarData?.dismiss()
                                        val r = snackbarHostState.showSnackbar(itemRemovedText, actionLabel = undoText, duration = SnackbarDuration.Long)
                                        if (r == SnackbarResult.ActionPerformed) viewModel.restoreItem(index, removed)
                                    }
                                },
                                enabled = !ui.saving,
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = removeLabel)
                            }
                        }
                        OutlinedTextField(
                            value = line.item.qtyText,
                            onValueChange = { viewModel.setQty(index, it) },
                            label = { Text(stringResource(R.string.qty) + if (line.item.unit.isNotBlank()) " (${line.item.unit})" else "") },
                            singleLine = true,
                            isError = line.qty == null,
                            supportingText = { if (line.qty == null) Text(stringResource(R.string.lebih_dari_0_maksimal_2)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = if (flags.priceOverride) ImeAction.Next else ImeAction.Done),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (flags.priceOverride) {
                            OutlinedTextField(
                                value = line.item.overridePriceText,
                                onValueChange = { viewModel.setOverridePrice(index, it) },
                                label = { Text(stringResource(R.string.harga_satuan_khusus_opsional)) },
                                singleLine = true,
                                isError = line.item.overridePriceText.isNotBlank() && MoneyParser.parseAmount(line.item.overridePriceText) == null,
                                supportingText = {
                                    val parsed = MoneyParser.parseAmount(line.item.overridePriceText)
                                    when {
                                        line.item.overridePriceText.isBlank() -> Unit
                                        parsed == null -> Text(stringResource(R.string.nominal_tidak_valid_contoh_15000))
                                        else -> Text(stringResource(R.string.dibaca_sebagai_nilai, MoneyFormatter.format(parsed)))
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Text(stringResource(R.string.perkiraan_jumlah_nilai, MoneyFormatter.format(line.amount)), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            OutlinedButton(
                onClick = { viewModel.openServicePicker(); pickService = true },
                enabled = !ui.saving && ui.items.size < InvoiceRepository.MAX_ITEMS,
                modifier = Modifier.fillMaxWidth().minTouch(),
            ) { Text(stringResource(R.string.tambah_item_dari_katalog)) }
            ui.itemNotice?.let { msg ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                    TextButton(onClick = viewModel::dismissItemNotice) { Text(stringResource(R.string.tutup)) }
                }
            }

            // Hanya bila harga sudah diketahui: jangan menyalahkan "tanpa harga" saat harga masih dimuat/gagal dimuat
            // (penyebab sebenarnya sudah ditampilkan sebagai galat di atas).
            if (ui.items.isNotEmpty() && pricesKnown && preview.problemCount > 0) {
                WarningBox(stringResource(R.string.peringatan_harga_nol, preview.problemCount))
            }

            OutlinedTextField(
                value = ui.notes,
                onValueChange = viewModel::setNotes,
                label = { Text(stringResource(R.string.catatan)) },
                minLines = 2,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.perkiraan_subtotal_preview), style = MaterialTheme.typography.labelMedium, modifier = Modifier.semantics { heading() })
                    Text(MoneyFormatter.format(preview.subtotal), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.hanya_perkiraan_server_menentukan), style = MaterialTheme.typography.bodySmall)
                    if (ui.draftChecking || ui.pricesLoading || ui.pricesError != null || ui.draftNotice == DRAFT_UNVERIFIED) {
                        // Peringatan (bukan galat): bukan warna error agar galat sungguhan tetap menonjol.
                        Text(
                            stringResource(R.string.harga_belum_final),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            Text(stringResource(R.string.invoice_baru_selalu_disimpan_dengan), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { confirmDiscard = true }, enabled = !ui.saving, modifier = Modifier.minTouch()) {
                Text(stringResource(R.string.hapus_draft), color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (pickCustomer) {
        val results by viewModel.customerResults.collectAsStateWithLifecycle()
        SearchPickerDialog(
            title = stringResource(R.string.pilih_customer),
            state = results,
            onQuery = viewModel::searchCustomers,
            serverSearch = true,
            label = { it.name },
            sub = { it.phone?.takeIf { p -> p.isNotBlank() } },
            matches = { _, _ -> true },
            onRetry = { viewModel.searchCustomers("") },
            onPick = { viewModel.selectCustomer(it); pickCustomer = false },
            onDismiss = { pickCustomer = false },
            footer = stringResource(R.string.picker_customer_terbatas),
        )
    }
    if (pickService) {
        val services by viewModel.services.collectAsStateWithLifecycle()
        val noPrice = stringResource(R.string.harga_tidak_ada_singkat)
        SearchPickerDialog(
            title = stringResource(R.string.pilih_item),
            state = services,
            onQuery = {},
            serverSearch = false,
            label = { it.name },
            sub = { s ->
                val price = ui.prices[s.id]?.let { MoneyFormatter.format(it) } ?: if (ui.customerId != null) noPrice else null
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
            title = stringResource(R.string.hapus_draft_tanya),
            message = stringResource(R.string.draft_invoice_lokal_akan_dihapus),
            confirmText = stringResource(R.string.hapus_draft),
            onConfirm = { confirmDiscard = false; viewModel.discardDraft() },
            onDismiss = { confirmDiscard = false },
            destructive = true,
        )
    }
    if (confirmLeave) {
        ConfirmDialog(
            title = stringResource(R.string.keluar_saat_menyimpan_judul),
            message = stringResource(R.string.keluar_saat_menyimpan_pesan),
            confirmText = stringResource(R.string.tetap_keluar),
            onConfirm = { confirmLeave = false; onBack() },
            onDismiss = { confirmLeave = false },
            destructive = true,
        )
    }
    confirmDiscardInvoice?.let { number ->
        ConfirmDialog(
            title = stringResource(R.string.hapus_draft_tanya),
            message = stringResource(R.string.hapus_draft_pastikan, number),
            confirmText = stringResource(R.string.hapus_draft),
            onConfirm = { confirmDiscardInvoice = null; viewModel.dismissUncertain(); viewModel.discardDraft() },
            onDismiss = { confirmDiscardInvoice = null },
            destructive = true,
        )
    }
    ui.uncertain?.let { u ->
        UncertainDialog(
            message = u.message,
            title = stringResource(R.string.invoice_mungkin_sudah_tersimpan),
            checkLabel = stringResource(R.string.periksa_invoice),
            resendTitle = stringResource(R.string.kirim_ulang_invoice_tanya),
            dismissHint = stringResource(R.string.draft_tetap_tersimpan),
            onCheck = { viewModel.dismissUncertain(); onCheckList() },
            onResend = viewModel::confirmResend,
            resendWarning = stringResource(R.string.peringatan_kirim_ulang_invoice),
            onDismiss = viewModel::dismissUncertain,
            extra = {
                when {
                    u.checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.memeriksa_daftar_invoice), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    u.checkError != null -> Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        Text(u.checkError, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { viewModel.checkExisting() }) { Text(stringResource(R.string.periksa_lagi)) }
                    }
                    u.matches != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        val who = u.customerName ?: "-"
                        val whenText = u.date?.let { AppDates.display(it) } ?: "-"
                        if (u.matches.isEmpty()) {
                            Text(stringResource(R.string.belum_ada_invoice_untuk, who, whenText))
                            // Pencarian hanya mencakup 50 invoice terbaru bulan itu: hasil kosong belum tentu pasti.
                            Text(stringResource(R.string.pencarian_terbatas_50), style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(stringResource(R.string.ada_invoice_untuk, u.matches.size, who, whenText))
                            Text(stringResource(R.string.pencarian_terbatas_50), style = MaterialTheme.typography.bodySmall)
                            u.matches.take(5).forEach { inv ->
                                val sameAsEstimate = u.estimate != null && inv.subtotal.compareTo(u.estimate) == 0
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(inv.invoice_number, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                            StatusBadge(inv.status)
                                        }
                                        val time = AppDates.parseServerDateTime(inv.created_at)?.let { String.format(Locale.ROOT, "%02d.%02d", it.hour, it.minute) }
                                        Text(
                                            listOfNotNull(
                                                time?.let { stringResource(R.string.dibuat_jam, it) },
                                                MoneyFormatter.format(inv.subtotal),
                                            ).joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        if (sameAsEstimate) {
                                            Text(stringResource(R.string.kemungkinan_besar_ini), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                        }
                                        TextButton(onClick = { confirmDiscardInvoice = inv.invoice_number }, modifier = Modifier.minTouch()) {
                                            Text(stringResource(R.string.ini_invoicenya_hapus_draft))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
        )
    }
}

/**
 * Dialog pilih-dengan-pencarian. Bila [serverSearch] maka [onQuery] memicu pencarian di server; selain itu difilter lokal.
 * [footer] = catatan kecil di bawah daftar (mis. daftar terpotong).
 */
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
    footer: String? = null,
) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; if (serverSearch) onQuery(it) },
                    label = { Text(stringResource(R.string.cari)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            TextButton(onClick = { query = ""; if (serverSearch) onQuery("") }) { Text(stringResource(R.string.hapus_teks)) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp)) {
                    when (state) {
                        Load.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        is Load.Error -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(state.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            TextButton(onClick = onRetry) { Text(stringResource(R.string.coba_lagi)) }
                        }
                        is Load.Content -> {
                            val shown = if (serverSearch || query.isBlank()) state.data else state.data.filter { matches(it, query.trim()) }
                            if (shown.isEmpty()) {
                                Text(stringResource(R.string.tidak_ada_hasil), Modifier.align(Alignment.Center))
                            } else {
                                LazyColumn {
                                    items(shown) { item ->
                                        // Tinggi minimum 48dp untuk target sentuh (UI-06).
                                        Column(
                                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onPick(item) }.padding(vertical = 10.dp),
                                            verticalArrangement = Arrangement.Center,
                                        ) {
                                            Text(label(item), style = MaterialTheme.typography.bodyLarge)
                                            sub(item)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                        }
                                        HorizontalDivider()
                                    }
                                    if (footer != null && query.isBlank()) {
                                        item { Text(footer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tutup)) } },
    )
}
