package id.jerco.billingroyal.ui.screens

import id.jerco.billingroyal.ui.components.RefreshOnResume
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.semantics.Role
import id.jerco.billingroyal.R
import androidx.compose.ui.res.stringResource
import id.jerco.billingroyal.ui.components.minTouch
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import id.jerco.billingroyal.core.AppDates
import id.jerco.billingroyal.core.FeatureFlags
import id.jerco.billingroyal.core.InvoiceActions
import id.jerco.billingroyal.core.InvoiceShareText
import id.jerco.billingroyal.core.MoneyFormatter
import id.jerco.billingroyal.data.local.ProfileEntity
import id.jerco.billingroyal.data.remote.ApiResult
import id.jerco.billingroyal.data.remote.InvoiceDto
import id.jerco.billingroyal.data.remote.InvoiceStatus
import id.jerco.billingroyal.data.repo.AuthRepository
import id.jerco.billingroyal.data.repo.InvoiceRepository
import id.jerco.billingroyal.ui.components.AppScaffold
import id.jerco.billingroyal.ui.components.ConfirmDialog
import id.jerco.billingroyal.ui.components.InfoBanner
import id.jerco.billingroyal.ui.components.LabeledValue
import id.jerco.billingroyal.ui.components.Load
import id.jerco.billingroyal.ui.components.LoadStateView
import id.jerco.billingroyal.ui.components.Pager
import id.jerco.billingroyal.ui.components.SecureScreen
import id.jerco.billingroyal.ui.components.StatusBadge
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ Daftar invoice

data class InvoiceFilter(val month: YearMonth, val status: String?, val query: String)

@OptIn(FlowPreview::class)
@HiltViewModel
class InvoiceListViewModel @Inject constructor(private val repo: InvoiceRepository) : ViewModel() {
    private val _month = MutableStateFlow(YearMonth.from(AppDates.today()))
    private val _status = MutableStateFlow<String?>(null)
    private val _query = MutableStateFlow("")
    val month: StateFlow<YearMonth> = _month
    val status: StateFlow<String?> = _status
    val query: StateFlow<String> = _query

    private val pager = Pager(viewModelScope, key = { it.id }) { page ->
        repo.list(_query.value, _month.value, null, _status.value, page)
    }
    val state = pager.state

    init {
        viewModelScope.launch {
            combine(_month, _status, _query.debounce { if (it.isEmpty()) 0L else 300L }) { m, s, q -> InvoiceFilter(m, s, q) }
                .distinctUntilChanged()
                .collect { pager.reload() }
        }
    }

    fun shiftMonth(delta: Long) { _month.value = _month.value.plusMonths(delta) }
    fun setStatus(status: String?) { _status.value = status }
    fun setQuery(q: String) { _query.value = q }
    fun refresh() = pager.reload(pull = true)
    fun retry() = pager.reload()
    fun loadMore() = pager.loadMore()
}

@Composable
fun InvoiceListScreen(
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onCreate: () -> Unit,
    viewModel: InvoiceListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val month by viewModel.month.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, state.items.size) { if (nearEnd) viewModel.loadMore() }
    RefreshOnResume { viewModel.refresh() }

    AppScaffold(
        title = stringResource(R.string.daftar_invoice),
        onBack = onBack,
        floatingActionButton = { FloatingActionButton(onClick = onCreate) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.buat_invoice_2)) } },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            MonthSwitcher(month = month, onShift = viewModel::shiftMonth)
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                label = { Text(stringResource(R.string.cari_nomor_invoice_atau_customer)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                trailingIcon = {
                    if (query.isNotEmpty()) TextButton(onClick = { viewModel.setQuery("") }) { Text(stringResource(R.string.hapus_teks)) }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScrollCompat().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = status == null, onClick = { viewModel.setStatus(null) }, label = { Text(stringResource(R.string.semua)) })
                InvoiceStatus.ALL.forEach { s ->
                    FilterChip(selected = status == s, onClick = { viewModel.setStatus(s) }, label = { Text(InvoiceStatus.label(s)) })
                }
            }
            LoadStateView(
                state = state.toLoad(),
                refreshing = state.refreshing,
                onRefresh = viewModel::refresh,
                onRetry = viewModel::retry,
                isEmpty = { it.isEmpty() },
                fromCache = state.fromCache,
                emptyMessage = if (query.isBlank() && status == null) stringResource(R.string.belum_ada_invoice_bulan_ini) else stringResource(R.string.tidak_ada_invoice_pada_filter),
                emptyAction = if (query.isBlank() && status == null) ({ Button(onClick = onCreate, modifier = Modifier.minTouch()) { Text(stringResource(R.string.buat_invoice_2)) } }) else null,
            ) { items ->
                // Ruang bawah agar baris terakhir tidak tertutup FAB.
                LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 88.dp), modifier = Modifier.fillMaxSize()) {
                    items(items, key = { it.id }) { inv ->
                        InvoiceRow(inv, onClick = { onOpen(inv.id) })
                        HorizontalDivider()
                    }
                    if (state.loadingMore) {
                        item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                    }
                    state.loadMoreError?.let { msg ->
                        item {
                            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(msg, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                                TextButton(onClick = viewModel::loadMore) { Text(stringResource(R.string.coba_lagi)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Modifier.horizontalScrollCompat(): Modifier =
    this.then(Modifier.horizontalScroll(rememberScrollState()))

@Composable
fun MonthSwitcher(month: YearMonth, onShift: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onShift(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.bulan_sebelumnya)) }
        Text(AppDates.displayMonth(month), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = { onShift(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.bulan_berikutnya)) }
    }
}

@Composable
fun InvoiceRow(inv: InvoiceDto, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(inv.invoice_number, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            StatusBadge(inv.status)
        }
        Text(inv.customer_name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(AppDates.parseApiDate(inv.invoice_date)?.let { AppDates.display(it) } ?: inv.invoice_date, style = MaterialTheme.typography.bodySmall)
            Text(MoneyFormatter.format(inv.subtotal), style = MaterialTheme.typography.bodyMedium)
        }
        if (inv.balance.signum() > 0) Text(stringResource(R.string.sisa, MoneyFormatter.format(inv.balance)), style = MaterialTheme.typography.bodySmall)
    }
}

// ------------------------------------------------------------------ Detail invoice

data class InvoiceDetailData(val invoice: InvoiceDto, val profile: ProfileEntity?)

data class InvoiceDetailState(
    val load: Load<InvoiceDetailData> = Load.Loading,
    val refreshing: Boolean = false,
    val pendingStatus: String? = null,
    val updating: Boolean = false,
    val statusError: String? = null,
)

@HiltViewModel
class InvoiceDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: InvoiceRepository,
    private val auth: AuthRepository,
    val flags: FeatureFlags,
) : ViewModel() {
    private val id: Long = checkNotNull(savedState["id"])
    private val _state = MutableStateFlow(InvoiceDetailState())
    val state: StateFlow<InvoiceDetailState> = _state

    init {
        load(pull = false)
    }

    fun load(pull: Boolean) {
        _state.update { if (pull) it.copy(refreshing = true) else it.copy(load = Load.Loading) }
        viewModelScope.launch {
            when (val r = repo.get(id)) {
                is ApiResult.Success -> {
                    // Profil (laundry + rekening) dari cache; diambil dari server hanya bila belum ada.
                    val profile = (auth.profile() as? ApiResult.Success)?.data
                    _state.update { it.copy(load = Load.Content(InvoiceDetailData(r.data, profile)), refreshing = false) }
                }
                is ApiResult.Failure -> _state.update { it.copy(load = Load.Error(r.error.message), refreshing = false) }
            }
        }
    }

    fun askStatus(status: String) = _state.update { it.copy(pendingStatus = status, statusError = null) }
    fun cancelAsk() = _state.update { it.copy(pendingStatus = null) }

    fun confirmStatus() {
        val target = _state.value.pendingStatus ?: return
        if (_state.value.updating) return
        _state.update { it.copy(pendingStatus = null, updating = true, statusError = null) }
        viewModelScope.launch {
            when (val r = repo.updateStatus(id, target)) {
                is ApiResult.Success -> _state.update { s ->
                    val profile = (s.load as? Load.Content)?.data?.profile
                    s.copy(updating = false, load = Load.Content(InvoiceDetailData(r.data, profile)))
                }
                is ApiResult.Failure -> _state.update { it.copy(updating = false, statusError = r.error.message) }
            }
        }
    }
}

@Composable
fun InvoiceDetailScreen(onBack: () -> Unit, viewModel: InvoiceDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val content = (state.load as? Load.Content)?.data
    SecureScreen()
    var shareRequest by remember { mutableStateOf<InvoiceDetailData?>(null) }

    fun shareNow(data: InvoiceDetailData, includeBank: Boolean) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Invoice ${data.invoice.invoice_number}")
            putExtra(Intent.EXTRA_TEXT, InvoiceShareText.build(data.invoice, data.profile, includeBank))
        }
        context.startActivity(Intent.createChooser(send, "Bagikan invoice"))
    }
    // Bagikan selalu lewat dialog konfirmasi (data customer keluar dari aplikasi): peringatan untuk Draft/Dibatalkan dan
    // pilihan "sertakan rekening pembayaran" bila profil punya rekening.
    fun requestShare(data: InvoiceDetailData) {
        shareRequest = data
    }

    AppScaffold(
        title = stringResource(R.string.detail_invoice),
        onBack = onBack,
        actions = {
            if (content != null) {
                IconButton(onClick = { requestShare(content) }) { Icon(Icons.Default.Share, contentDescription = stringResource(R.string.bagikan)) }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LoadStateView(
                state = state.load,
                refreshing = state.refreshing,
                onRefresh = { viewModel.load(pull = true) },
                onRetry = { viewModel.load(pull = false) },
            ) { data ->
                InvoiceDetailContent(data, state, viewModel, onShare = { requestShare(data) })
            }
        }
    }

    shareRequest?.let { data ->
        ShareInvoiceDialog(
            data = data,
            onShare = { includeBank -> shareRequest = null; shareNow(data, includeBank) },
            onDismiss = { shareRequest = null },
        )
    }

    state.pendingStatus?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.ubah_status),
            message = "Ubah status invoice ke \"${InvoiceStatus.label(target)}\"? Tindakan ini dikirim ke server.",
            confirmText = stringResource(R.string.ya_ubah),
            onConfirm = viewModel::confirmStatus,
            onDismiss = viewModel::cancelAsk,
        )
    }
}

@Composable
private fun InvoiceDetailContent(
    data: InvoiceDetailData,
    state: InvoiceDetailState,
    viewModel: InvoiceDetailViewModel,
    onShare: () -> Unit,
) {
    val inv = data.invoice
    val p = data.profile
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (p != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                p.logoPath?.let { LogoImage(it, Modifier.size(56.dp), contentDescription = null) } // nama bisnis tampil di sebelahnya
                Column {
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                    p.address?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (!p.bankAccount.isNullOrBlank()) {
                        Text(
                            "Rekening: ${p.bankName.orEmpty()} ${p.bankAccount}" + (p.bankHolder?.takeIf { it.isNotBlank() }?.let { " a.n. $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            HorizontalDivider()
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(inv.invoice_number, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            StatusBadge(inv.status)
        }
        LabeledValue(stringResource(R.string.customer), inv.customer_name)
        LabeledValue(stringResource(R.string.tanggal), AppDates.parseApiDate(inv.invoice_date)?.let { AppDates.display(it) } ?: inv.invoice_date)
        inv.notes?.takeIf { it.isNotBlank() }?.let { LabeledValue(stringResource(R.string.catatan), it) }
        HorizontalDivider()
        Text(stringResource(R.string.item), style = MaterialTheme.typography.titleMedium)
        inv.items.forEach { item ->
            Column {
                Text(item.description, style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "${MoneyFormatter.formatQty(item.qty)} ${item.unit} x ${MoneyFormatter.format(item.unit_price)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(MoneyFormatter.format(item.amount), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        HorizontalDivider()
        LabeledValue(stringResource(R.string.subtotal), MoneyFormatter.format(inv.subtotal))
        LabeledValue(stringResource(R.string.dibayar), MoneyFormatter.format(inv.paid_amount))
        LabeledValue(stringResource(R.string.sisa_2), MoneyFormatter.format(inv.balance))

        state.statusError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
        if (state.updating) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.memperbarui_status), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }

        Button(onClick = onShare, modifier = Modifier.fillMaxWidth().minTouch()) { Text(stringResource(R.string.bagikan)) }

        // Tombol status hanya muncul bila feature flag aktif.
        InvoiceActions.availableTargets(inv.status, viewModel.flags).forEach { target ->
            val label = InvoiceStatus.actionLabel(target)
            OutlinedButton(onClick = { viewModel.askStatus(target) }, enabled = !state.updating, modifier = Modifier.fillMaxWidth().minTouch()) { Text(label) }
        }
    }
}

/**
 * Konfirmasi sebelum teks invoice dikirim ke aplikasi lain. Menampilkan peringatan untuk Draft/Dibatalkan dan, bila profil
 * punya rekening, pilihan "Sertakan rekening pembayaran" (default aktif untuk invoice yang masih perlu dibayar).
 */
@Composable
fun ShareInvoiceDialog(data: InvoiceDetailData, onShare: (includeBank: Boolean) -> Unit, onDismiss: () -> Unit) {
    val status = data.invoice.status
    val hasBank = !data.profile?.bankAccount.isNullOrBlank()
    var includeBank by remember { mutableStateOf(status != InvoiceStatus.PAID && status != InvoiceStatus.CANCELLED) }
    val warning = when (status) {
        InvoiceStatus.DRAFT -> "Invoice ini masih berstatus Draft (belum final). "
        InvoiceStatus.CANCELLED -> "Invoice ini berstatus Dibatalkan, sehingga tidak perlu dibayar. "
        else -> ""
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bagikan invoice?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(warning + "Teks yang dibagikan terkirim ke aplikasi pilihan Anda dan memuat data customer dan rincian tagihan.")
                if (hasBank) {
                    Row(
                        Modifier.fillMaxWidth().minTouch().toggleable(
                            value = includeBank,
                            role = Role.Checkbox,
                            onValueChange = { includeBank = it },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = includeBank, onCheckedChange = null)
                        Text("Sertakan rekening pembayaran", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onShare(hasBank && includeBank) }) { Text("Bagikan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}
