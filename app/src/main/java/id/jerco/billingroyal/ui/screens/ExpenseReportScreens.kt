package id.jerco.billingroyal.ui.screens

import id.jerco.billingroyal.ui.components.RefreshOnResume
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import id.jerco.billingroyal.core.AppDates
import id.jerco.billingroyal.core.FeatureFlags
import id.jerco.billingroyal.data.repo.IdempotencyKind
import id.jerco.billingroyal.data.repo.IdempotencyStore
import id.jerco.billingroyal.core.MoneyFormatter
import id.jerco.billingroyal.core.MoneyParser
import id.jerco.billingroyal.data.remote.ApiResult
import id.jerco.billingroyal.data.remote.AppError
import id.jerco.billingroyal.data.remote.displayMessage
import id.jerco.billingroyal.data.remote.ExpenseDto
import id.jerco.billingroyal.data.remote.InvoiceReportDto
import id.jerco.billingroyal.data.remote.PaymentMethods
import id.jerco.billingroyal.data.remote.PaymentScope
import id.jerco.billingroyal.data.remote.isModuleNotReady
import id.jerco.billingroyal.data.remote.isOutcomeUncertain
import id.jerco.billingroyal.data.repo.ExpenseInput
import id.jerco.billingroyal.data.repo.ExpenseMonth
import id.jerco.billingroyal.data.repo.ExpenseRepository
import id.jerco.billingroyal.data.repo.ReportRepository
import id.jerco.billingroyal.ui.components.AppScaffold
import id.jerco.billingroyal.ui.components.ConfirmDialog
import id.jerco.billingroyal.ui.components.DateField
import id.jerco.billingroyal.ui.components.Load
import id.jerco.billingroyal.ui.components.LoadStateView
import id.jerco.billingroyal.ui.components.SavingButton
import id.jerco.billingroyal.ui.components.UncertainDialog
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ Daftar pengeluaran

data class ExpenseListState(
    val month: YearMonth = YearMonth.from(AppDates.today()),
    val load: Load<ExpenseMonth> = Load.Loading,
    val refreshing: Boolean = false,
)

@HiltViewModel
class ExpenseListViewModel @Inject constructor(private val repo: ExpenseRepository) : ViewModel() {
    private val _state = MutableStateFlow(ExpenseListState())
    val state: StateFlow<ExpenseListState> = _state
    private var job: kotlinx.coroutines.Job? = null

    init {
        load(pull = false)
    }

    fun shiftMonth(delta: Long) {
        _state.update { it.copy(month = it.month.plusMonths(delta)) }
        load(pull = false)
    }

    fun load(pull: Boolean) {
        job?.cancel()
        _state.update { if (pull) it.copy(refreshing = true) else it.copy(load = Load.Loading, refreshing = false) }
        val month = _state.value.month
        job = viewModelScope.launch {
            val result = repo.month(month)
            _state.update {
                when (result) {
                    is ApiResult.Success -> it.copy(load = Load.Content(result.data), refreshing = false)
                    is ApiResult.Failure -> it.copy(
                        load = Load.Error(if (result.error.isModuleNotReady) "Modul pengeluaran belum aktif" else result.error.message),
                        refreshing = false,
                    )
                }
            }
        }
    }
}

@Composable
fun ExpenseListScreen(
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onAdd: () -> Unit,
    viewModel: ExpenseListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RefreshOnResume { viewModel.load(pull = true) }
    AppScaffold(
        title = stringResource(R.string.pengeluaran),
        onBack = onBack,
        floatingActionButton = { FloatingActionButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.tambah_pengeluaran)) } },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            MonthSwitcher(month = state.month, onShift = viewModel::shiftMonth)
            (state.load as? Load.Content)?.data?.let { m ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.total_bulan_ini), style = MaterialTheme.typography.labelMedium)
                        Text(MoneyFormatter.format(m.total), style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            LoadStateView(
                state = state.load,
                refreshing = state.refreshing,
                onRefresh = { viewModel.load(pull = true) },
                onRetry = { viewModel.load(pull = false) },
                isEmpty = { it.items.isEmpty() },
                emptyMessage = stringResource(R.string.belum_ada_pengeluaran_pada_bulan),
                emptyAction = { Button(onClick = onAdd, modifier = Modifier.minTouch()) { Text(stringResource(R.string.tambah_pengeluaran)) } },
            ) { m ->
                LazyColumn(contentPadding = PaddingValues(bottom = 88.dp), modifier = Modifier.fillMaxSize()) {
                    items(m.items, key = { it.id }) { e ->
                        Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { onOpen(e.id) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(e.description, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp))
                                Text(MoneyFormatter.format(e.amount), style = MaterialTheme.typography.titleMedium)
                            }
                            Text(
                                listOfNotNull(
                                    AppDates.parseApiDate(e.expense_date)?.let { AppDates.display(it) },
                                    e.category,
                                    e.payment_method,
                                ).joinToString("  |  "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Form pengeluaran

data class ExpenseFormState(
    val loading: Boolean = false,
    val loadError: String? = null,
    val date: LocalDate = AppDates.today(),
    val category: String = "",
    val description: String = "",
    val amountText: String = "",
    val method: String = PaymentMethods.ALL.first(),
    val payee: String = "",
    val notes: String = "",
    val saving: Boolean = false,
    val error: String? = null,
    val uncertain: Boolean = false,
    val done: Boolean = false,
    val deleting: Boolean = false,
    /** Expense yang sedang diubah/dihapus ternyata sudah tidak ada di server. */
    val gone: Boolean = false,
    /** True setelah pengguna mengubah isian: dipakai untuk peringatan saat Kembali. */
    val dirty: Boolean = false,
)

@HiltViewModel
class ExpenseFormViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: ExpenseRepository,
    val flags: FeatureFlags,
    private val keyStore: IdempotencyStore,
) : ViewModel() {
    private val editId: Long? = (savedState.get<Long>("id") ?: -1L).takeIf { it >= 0 }
    val isEdit: Boolean get() = editId != null

    private val _state = MutableStateFlow(ExpenseFormState(loading = editId != null))
    val state: StateFlow<ExpenseFormState> = _state

    init {
        if (editId != null) loadExisting()
    }

    fun loadExisting() {
        val id = editId ?: return
        _state.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            _state.update {
                when (val r = repo.get(id)) {
                    is ApiResult.Success -> {
                        val e: ExpenseDto = r.data
                        it.copy(
                            loading = false,
                            date = AppDates.parseApiDate(e.expense_date) ?: AppDates.today(),
                            category = e.category,
                            description = e.description,
                            amountText = e.amount.stripTrailingZeros().toPlainString().replace('.', ','),
                            method = e.payment_method.ifBlank { PaymentMethods.ALL.first() },
                            payee = e.payee.orEmpty(),
                            notes = e.notes.orEmpty(),
                            dirty = false,
                        )
                    }
                    is ApiResult.Failure -> it.copy(loading = false, loadError = r.error.message)
                }
            }
        }
    }

    fun update(block: ExpenseFormState.() -> ExpenseFormState) = _state.update { it.block().copy(error = null, dirty = true) }

    fun save() {
        val s = _state.value
        if (s.saving || s.deleting) return
        val amount = MoneyParser.parseAmount(s.amountText)
        if (amount == null) {
            _state.update { it.copy(error = "Nominal tidak valid. Contoh: 15.000 atau 15.000,50") }
            return
        }
        val input = ExpenseInput(s.date, s.category, s.description, amount, s.method, s.payee, s.notes)
        input.validate()?.let { msg ->
            _state.update { it.copy(error = msg) }
            return
        }
        _state.update { it.copy(saving = true, error = null, uncertain = false) }
        viewModelScope.launch {
            val result = if (editId != null) repo.update(editId, input) // PUT seluruh field
            else repo.create(keyStore.keyFor(IdempotencyKind.EXPENSE_CREATE, input.toJson()), input)
            when (result) {
                is ApiResult.Success -> {
                    if (editId == null) keyStore.clear(IdempotencyKind.EXPENSE_CREATE)
                    _state.update { it.copy(saving = false, done = true) }
                }
                is ApiResult.Failure -> {
                    val err = result.error
                    if (editId == null && err.isOutcomeUncertain) {
                        _state.update { it.copy(saving = false, uncertain = true) }
                    } else if (editId != null && looksLikeNotFound(err) && isGone(editId)) {
                        _state.update { it.copy(saving = false, gone = true, error = GONE_MESSAGE) }
                    } else {
                        _state.update {
                            it.copy(
                                saving = false,
                                error = when {
                                    err.isModuleNotReady -> "Modul pengeluaran belum aktif"
                                    err is AppError.Network && err.notSent -> err.message + " Data belum tersimpan."
                                    else -> err.displayMessage // 422: rincian per field
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    fun delete() {
        val id = editId ?: return
        if (!flags.delete || _state.value.deleting) return
        _state.update { it.copy(deleting = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.delete(id)) {
                is ApiResult.Success -> _state.update { it.copy(deleting = false, done = true) }
                is ApiResult.Failure ->
                    if (looksLikeNotFound(r.error) && isGone(id)) {
                        _state.update { it.copy(deleting = false, gone = true, error = GONE_MESSAGE) }
                    } else {
                        _state.update { it.copy(deleting = false, error = r.error.displayMessage) }
                    }
            }
        }
    }

    fun dismissUncertain() = _state.update { it.copy(uncertain = false) }

    /** Server mengembalikan 422 (bukan 404) untuk expense yang tidak ditemukan; 422 tanpa rincian field = kandidat. */
    private fun looksLikeNotFound(err: AppError) =
        err is AppError.Api && (err.status == 404 || (err.status == 422 && err.fieldErrors.isEmpty()))

    /** Pastikan dengan GET: bila expense memang tidak ada (404/422), formulir ditandai "sudah tidak ada". */
    private suspend fun isGone(id: Long): Boolean =
        (repo.get(id) as? ApiResult.Failure)?.error.let { it is AppError.Api && (it.status == 404 || it.status == 422) }

    private companion object {
        const val GONE_MESSAGE = "Pengeluaran ini sudah tidak ada di server (mungkin sudah dihapus pengguna lain). " +
            "Kembali ke daftar lalu muat ulang."
    }
}

@Composable
fun ExpenseFormScreen(onBack: () -> Unit, onCheckList: () -> Unit, viewModel: ExpenseFormViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    LaunchedEffect(s.done) { if (s.done) onBack() }
    val guardLeave = s.dirty && !s.done && !s.gone
    BackHandler(enabled = guardLeave) { confirmLeave = true }
    val showForm = !s.loading && s.loadError == null

    AppScaffold(
        title = if (viewModel.isEdit) stringResource(R.string.ubah_pengeluaran) else stringResource(R.string.tambah_pengeluaran_judul),
        onBack = { if (guardLeave) confirmLeave = true else onBack() },
        bottomBar = {
            if (showForm) {
                Surface(tonalElevation = 3.dp, modifier = Modifier.imePadding()) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
                        if (s.gone) {
                            Button(onClick = onBack, modifier = Modifier.fillMaxWidth().minTouch()) { Text(stringResource(R.string.kembali_ke_daftar)) }
                        } else {
                            SavingButton(stringResource(R.string.simpan), saving = s.saving, enabled = !s.deleting, onClick = viewModel::save)
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                s.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                s.loadError != null -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(s.loadError!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = viewModel::loadExisting, modifier = Modifier.minTouch()) { Text(stringResource(R.string.coba_lagi)) }
                }
                else -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DateField(stringResource(R.string.tanggal), s.date, { d -> viewModel.update { copy(date = d) } }, Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = s.category, onValueChange = { v -> viewModel.update { copy(category = v) } },
                        label = { Text(stringResource(R.string.kategori)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = s.description, onValueChange = { v -> viewModel.update { copy(description = v) } },
                        label = { Text(stringResource(R.string.deskripsi)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val parsedAmount = MoneyParser.parseAmount(s.amountText)
                    OutlinedTextField(
                        value = s.amountText, onValueChange = { v -> viewModel.update { copy(amountText = v) } },
                        label = { Text(stringResource(R.string.nominal_rp)) }, singleLine = true,
                        isError = s.amountText.isNotBlank() && parsedAmount == null,
                        supportingText = {
                            when {
                                s.amountText.isBlank() -> Text(stringResource(R.string.nominal_contoh_hint))
                                parsedAmount == null -> Text(stringResource(R.string.nominal_tidak_valid_hint))
                                // Pratinjau cara aplikasi membaca angka (titik = ribuan, koma = desimal).
                                else -> Text(stringResource(R.string.dibaca_sebagai_nilai, MoneyFormatter.format(parsedAmount)))
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PaymentMethodPicker(s.method) { m -> viewModel.update { copy(method = m) } }
                    OutlinedTextField(
                        value = s.payee, onValueChange = { v -> viewModel.update { copy(payee = v) } },
                        label = { Text(stringResource(R.string.penerima)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = s.notes, onValueChange = { v -> viewModel.update { copy(notes = v) } },
                        label = { Text(stringResource(R.string.catatan)) }, minLines = 2,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (viewModel.isEdit && viewModel.flags.delete) {
                        OutlinedButton(
                            onClick = { confirmDelete = true },
                            enabled = !s.saving && !s.deleting,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth().minTouch(),
                        ) { Text(if (s.deleting) stringResource(R.string.menghapus) else stringResource(R.string.hapus)) }
                    }
                }
            }
        }
    }

    if (confirmLeave) {
        ConfirmDialog(
            title = stringResource(R.string.buang_perubahan_judul),
            message = stringResource(R.string.buang_perubahan_pesan),
            confirmText = stringResource(R.string.buang),
            onConfirm = { confirmLeave = false; onBack() },
            onDismiss = { confirmLeave = false },
            destructive = true,
        )
    }
    if (s.uncertain) {
        UncertainDialog(
            message = stringResource(R.string.pengeluaran_koneksi_terputus_pesan),
            title = stringResource(R.string.pengeluaran_mungkin_sudah_tersimpan),
            checkLabel = stringResource(R.string.periksa_daftar_pengeluaran),
            resendTitle = stringResource(R.string.kirim_ulang_pengeluaran_tanya),
            dismissHint = stringResource(R.string.isian_tetap_di_layar),
            onCheck = { viewModel.dismissUncertain(); onCheckList() },
            onResend = { viewModel.dismissUncertain(); viewModel.save() },
            resendWarning = stringResource(R.string.peringatan_kirim_ulang_pengeluaran),
            onDismiss = viewModel::dismissUncertain,
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.hapus_pengeluaran),
            message = stringResource(R.string.pengeluaran_ini_akan_dihapus),
            confirmText = stringResource(R.string.hapus),
            onConfirm = { confirmDelete = false; viewModel.delete() },
            onDismiss = { confirmDelete = false },
            destructive = true,
        )
    }
}

@Composable
private fun PaymentMethodPicker(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.metode_bayar)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PaymentMethods.ALL.forEach { m ->
                DropdownMenuItem(text = { Text(m) }, onClick = { onSelect(m); expanded = false })
            }
        }
    }
}

// ------------------------------------------------------------------ Rekap

data class ReportState(
    val from: LocalDate = AppDates.today().withDayOfMonth(1),
    val to: LocalDate = AppDates.today(),
    val scope: String = PaymentScope.ALL,
    val load: Load<InvoiceReportDto> = Load.Loading,
    val refreshing: Boolean = false,
    val rangeError: String? = null,
)

@HiltViewModel
class ReportViewModel @Inject constructor(private val repo: ReportRepository) : ViewModel() {
    private val _state = MutableStateFlow(ReportState())
    val state: StateFlow<ReportState> = _state
    private var job: kotlinx.coroutines.Job? = null

    init {
        load(pull = false)
    }

    fun setFrom(d: LocalDate) { _state.update { it.copy(from = d) }; load(false) }
    fun setTo(d: LocalDate) { _state.update { it.copy(to = d) }; load(false) }
    fun setScope(s: String) { _state.update { it.copy(scope = s) }; load(false) }

    fun load(pull: Boolean) {
        job?.cancel()
        val s = _state.value
        // Rentang maksimal 31 hari; tidak mengirim request bila tidak valid.
        ReportRepository.validateRange(s.from, s.to)?.let { msg ->
            _state.update { it.copy(rangeError = msg, refreshing = false) }
            return
        }
        _state.update { if (pull) it.copy(refreshing = true, rangeError = null) else it.copy(load = Load.Loading, refreshing = false, rangeError = null) }
        job = viewModelScope.launch {
            val result = repo.invoices(s.from, s.to, s.scope)
            _state.update {
                when (result) {
                    is ApiResult.Success -> it.copy(load = Load.Content(result.data), refreshing = false)
                    is ApiResult.Failure -> it.copy(load = Load.Error(result.error.message), refreshing = false)
                }
            }
        }
    }
}

@Composable
fun ReportScreen(onBack: () -> Unit, onOpenInvoice: (Long) -> Unit, viewModel: ReportViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AppScaffold(title = stringResource(R.string.rekap), onBack = onBack) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateField(stringResource(R.string.dari), state.from, viewModel::setFrom, Modifier.weight(1f))
                    DateField(stringResource(R.string.sampai), state.to, viewModel::setTo, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(PaymentScope.ALL to stringResource(R.string.semua), PaymentScope.PAID to stringResource(R.string.lunas), PaymentScope.UNPAID to stringResource(R.string.belum_lunas)).forEach { (v, label) ->
                        FilterChip(selected = state.scope == v, onClick = { viewModel.setScope(v) }, label = { Text(label) })
                    }
                }
                Text(stringResource(R.string.rentang_maksimal_31_hari), style = MaterialTheme.typography.bodySmall)
                state.rangeError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
            }
            // Rentang tidak valid: jangan menampilkan hasil lama (bisa menyesatkan), tampilkan petunjuk saja.
            if (state.rangeError != null) {
                Text(stringResource(R.string.rekap_perbaiki_rentang), modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            LoadStateView(
                state = state.load,
                refreshing = state.refreshing,
                onRefresh = { viewModel.load(pull = true) },
                onRetry = { viewModel.load(pull = false) },
                isEmpty = { it.invoices.isEmpty() },
                emptyMessage = stringResource(R.string.tidak_ada_invoice_pada_rentang),
            ) { report ->
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Card(Modifier.fillMaxWidth().padding(16.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.ringkasan), style = MaterialTheme.typography.titleSmall)
                                SummaryRows(report.summary)
                            }
                        }
                    }
                    items(report.invoices, key = { it.id }) { inv ->
                        InvoiceRow(inv, onClick = { onOpenInvoice(inv.id) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
