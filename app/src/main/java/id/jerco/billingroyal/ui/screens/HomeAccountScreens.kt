package id.jerco.billingroyal.ui.screens

import id.jerco.billingroyal.ui.components.OfflineBanner
import id.jerco.billingroyal.data.repo.InvoiceRepository
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import id.jerco.billingroyal.ui.components.minTouch
import id.jerco.billingroyal.ui.components.BrandMark
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import id.jerco.billingroyal.R
import id.jerco.billingroyal.core.AppDates
import id.jerco.billingroyal.core.MoneyFormatter
import id.jerco.billingroyal.data.local.ProfileEntity
import id.jerco.billingroyal.data.local.SessionManager
import id.jerco.billingroyal.data.local.StoredSession
import id.jerco.billingroyal.data.remote.ApiResult
import id.jerco.billingroyal.data.remote.ReportSummaryDto
import id.jerco.billingroyal.data.repo.AuthRepository
import id.jerco.billingroyal.data.repo.ReportRepository
import id.jerco.billingroyal.ui.components.AppScaffold
import id.jerco.billingroyal.ui.components.ConfirmDialog
import id.jerco.billingroyal.ui.components.Load
import id.jerco.billingroyal.ui.components.LoadStateView
import id.jerco.billingroyal.ui.components.LabeledValue
import id.jerco.billingroyal.ui.components.SecureScreen
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ Home

/** Ringkasan bulan ini; null di [HomeUiState.summary] berarti belum/gagal dimuat. */
data class HomeSummary(val from: LocalDate, val to: LocalDate, val summary: ReportSummaryDto)

/**
 * Menu Home TIDAK bergantung pada ringkasan (UX-01): nama bisnis, logo, dan draft lokal tersedia walau ringkasan gagal
 * dimuat. Hanya kartu ringkasan yang punya state memuat/galat sendiri.
 */
data class HomeUiState(
    val businessName: String = "",
    val logoPath: String? = null,
    /** Ringkasan draft lokal, mis. "Customer X · 3 item"; null bila tidak ada draft. */
    val draftSummary: String? = null,
    val summaryLoad: Load<HomeSummary> = Load.Loading,
    val refreshing: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val reports: ReportRepository,
    private val auth: AuthRepository,
    private val invoices: InvoiceRepository,
    private val sessionManager: SessionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState(businessName = sessionManager.session.value?.businessName.orEmpty()))
    val state: StateFlow<HomeUiState> = _state

    private var loadedOnce = false
    private var job: Job? = null

    /**
     * Dipanggil setiap layar Home kembali aktif (ON_RESUME). Pertama kali memuat penuh; setelah itu
     * menyegarkan diam-diam (tampilan lama tetap) agar ringkasan tidak basi setelah membuat invoice.
     */
    fun onResume() {
        viewModelScope.launch { refreshLocal() }
        if (!loadedOnce) {
            loadedOnce = true
            load(pull = false)
        } else {
            refreshSilently()
        }
    }

    /** Nama bisnis, logo, dan draft: semuanya data lokal, tidak butuh jaringan. */
    private suspend fun refreshLocal() {
        val draft = invoices.loadDraft()
        val summary = draft?.let { d -> "${d.customerName ?: "-"} · ${d.items.size} item" }
        _state.update {
            it.copy(
                businessName = sessionManager.session.value?.businessName.orEmpty(),
                logoPath = auth.cachedProfile()?.logoPath,
                draftSummary = summary,
            )
        }
    }

    private fun refreshSilently() {
        if (_state.value.summaryLoad !is Load.Content) return load(pull = false)
        job?.cancel()
        job = viewModelScope.launch {
            val today = AppDates.today()
            val from = today.withDayOfMonth(1)
            // Gagal: data lama tetap tampil (tidak diganti layar error).
            (reports.invoices(from, today) as? ApiResult.Success)?.let { r ->
                _state.update { it.copy(summaryLoad = Load.Content(HomeSummary(from, today, r.data.summary))) }
            }
        }
    }

    fun load(pull: Boolean) {
        _state.update { if (pull) it.copy(refreshing = true) else it.copy(summaryLoad = Load.Loading, refreshing = false) }
        job?.cancel()
        job = viewModelScope.launch {
            refreshLocal()
            val today = AppDates.today()
            val from = today.withDayOfMonth(1)
            val result = reports.invoices(from, today)
            _state.update {
                it.copy(
                    refreshing = false,
                    summaryLoad = when (result) {
                        is ApiResult.Success -> Load.Content(HomeSummary(from, today, result.data.summary))
                        is ApiResult.Failure -> Load.Error(result.error.message)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onCreateInvoice: () -> Unit,
    onCustomers: () -> Unit,
    onExpenses: () -> Unit,
    onInvoices: () -> Unit,
    onReport: () -> Unit,
    onAccount: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    AppScaffold(
        title = stringResource(R.string.app_name),
        actions = { TextButton(onClick = onAccount, modifier = Modifier.minTouch()) { Text(stringResource(R.string.akun)) } },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { viewModel.load(pull = true) },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Logo bisnis bila ada; bila tidak (atau gagal didecode) tampil tanda merek placeholder.
                    LogoImage(
                        path = state.logoPath,
                        modifier = Modifier.size(56.dp),
                        contentDescription = null, // nama bisnis tampil di sebelahnya
                        fallback = { BrandMark(size = 56.dp) },
                    )
                    Text(
                        state.businessName.ifBlank { stringResource(R.string.laundry) },
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                state.draftSummary?.let { summary ->
                    Card(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onCreateInvoice)) {
                        Column(Modifier.padding(16.dp).heightIn(min = 48.dp), verticalArrangement = Arrangement.Center) {
                            Text(stringResource(R.string.lanjutkan_draft_invoice), style = MaterialTheme.typography.titleSmall)
                            Text(summary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Button(onClick = onCreateInvoice, modifier = Modifier.fillMaxWidth().minTouch()) { Text(stringResource(R.string.buat_invoice)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onCustomers, modifier = Modifier.weight(1f).minTouch()) { Text(stringResource(R.string.customer)) }
                    FilledTonalButton(onClick = onExpenses, modifier = Modifier.weight(1f).minTouch()) { Text(stringResource(R.string.pengeluaran)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onInvoices, modifier = Modifier.weight(1f).minTouch()) { Text(stringResource(R.string.daftar_invoice)) }
                    FilledTonalButton(onClick = onReport, modifier = Modifier.weight(1f).minTouch()) { Text(stringResource(R.string.rekap)) }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (val load = state.summaryLoad) {
                            Load.Loading -> {
                                Text(stringResource(R.string.ringkasan_bulan_ini), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Text(stringResource(R.string.memuat_ringkasan), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                }
                            }
                            is Load.Error -> {
                                Text(stringResource(R.string.ringkasan_bulan_ini), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                                Text(
                                    stringResource(R.string.ringkasan_gagal),
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                )
                                OutlinedButton(onClick = { viewModel.load(pull = false) }, modifier = Modifier.minTouch()) {
                                    Text(stringResource(R.string.coba_lagi))
                                }
                            }
                            is Load.Content -> {
                                Text(
                                    stringResource(R.string.ringkasan_bulan_ini_rentang, AppDates.display(load.data.from), AppDates.display(load.data.to)),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.semantics { heading() },
                                )
                                SummaryRows(load.data.summary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SummaryRows(summary: ReportSummaryDto) {
    LabeledValue(stringResource(R.string.jumlah_invoice), summary.invoice_count.toString())
    LabeledValue(stringResource(R.string.total_qty), MoneyFormatter.formatQty(summary.total_quantity))
    LabeledValue(stringResource(R.string.total_invoice), MoneyFormatter.format(summary.invoice_total))
    LabeledValue(stringResource(R.string.total_dibayar), MoneyFormatter.format(summary.paid_total))
    LabeledValue(stringResource(R.string.total_sisa), MoneyFormatter.format(summary.balance_total))
}

// ------------------------------------------------------------------ Akun

data class AccountData(val session: StoredSession, val profile: ProfileEntity?, val fromCache: Boolean = false)

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val sessionManager: SessionManager,
) : ViewModel() {
    private val _load = MutableStateFlow<Load<AccountData>>(Load.Loading)
    val load: StateFlow<Load<AccountData>> = _load
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing
    private val _loggingOut = MutableStateFlow(false)
    val loggingOut: StateFlow<Boolean> = _loggingOut
    private val _loggedOut = MutableStateFlow(false)
    val loggedOut: StateFlow<Boolean> = _loggedOut

    init {
        load(pull = false)
    }

    fun load(pull: Boolean) {
        if (pull) _refreshing.value = true else _load.value = Load.Loading
        viewModelScope.launch {
            when (val me = auth.me()) {
                is ApiResult.Failure -> {
                    val cached = sessionManager.session.value
                    // Gagal memuat (offline): tetap tampilkan sesi lokal agar operator masih bisa Keluar (UX-19).
                    _load.value = if (cached != null) Load.Content(AccountData(cached, auth.cachedProfile(), fromCache = true))
                    else Load.Error(me.error.message)
                }
                is ApiResult.Success -> {
                    val profile = auth.profile().let { (it as? ApiResult.Success)?.data }
                    val session = sessionManager.session.value
                    _load.value = if (session != null) Load.Content(AccountData(session, profile))
                    else Load.Error("Sesi tidak ditemukan.")
                }
            }
            _refreshing.value = false
        }
    }

    /** POST /auth/logout lalu hapus semua data lokal walaupun request gagal. */
    fun logout() {
        if (_loggingOut.value) return
        _loggingOut.value = true
        viewModelScope.launch {
            auth.logout()
            _loggingOut.value = false
            _loggedOut.value = true
        }
    }
}

@Composable
fun AccountScreen(onBack: () -> Unit, onLoggedOut: () -> Unit, viewModel: AccountViewModel = hiltViewModel()) {
    val load by viewModel.load.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val loggingOut by viewModel.loggingOut.collectAsStateWithLifecycle()
    val loggedOut by viewModel.loggedOut.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    SecureScreen()

    LaunchedEffect(loggedOut) { if (loggedOut) onLoggedOut() }

    AppScaffold(title = stringResource(R.string.akun), onBack = onBack) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LoadStateView(
                state = load,
                refreshing = refreshing,
                onRefresh = { viewModel.load(pull = true) },
                onRetry = { viewModel.load(pull = false) },
            ) { data ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (data.fromCache) OfflineBanner()
                    data.profile?.logoPath?.let { path -> LogoImage(path, Modifier.size(72.dp)) }
                    LabeledValue(stringResource(R.string.nama_2), data.session.fullName)
                    LabeledValue(stringResource(R.string.username), data.session.username)
                    LabeledValue(stringResource(R.string.peran), data.session.role)
                    LabeledValue(stringResource(R.string.laundry), data.session.businessName)
                    LabeledValue(stringResource(R.string.kode_laundry), data.session.businessCode)
                    data.profile?.address?.let { LabeledValue(stringResource(R.string.alamat), it) }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { confirm = true }, enabled = !loggingOut, modifier = Modifier.fillMaxWidth().minTouch()) {
                        Text(stringResource(if (loggingOut) R.string.keluar_loading else R.string.keluar))
                    }
                }
            }
        }
    }
    if (confirm) {
        ConfirmDialog(
            title = stringResource(R.string.keluar),
            message = stringResource(R.string.draft_invoice_yang_belum_disimpan),
            confirmText = stringResource(R.string.keluar),
            onConfirm = { confirm = false; viewModel.logout() },
            onDismiss = { confirm = false },
            destructive = true,
        )
    }
}

/** Menampilkan logo dari file cache (didecode sekali setelah login), di-downsample. */
@Composable
fun LogoImage(
    path: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = stringResource(R.string.logo_description),
    fallback: (@Composable () -> Unit)? = null,
) {
    val bitmap = remember(path) {
        if (path == null) return@remember null
        try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            android.graphics.BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = contentDescription, modifier = modifier)
    } else {
        fallback?.invoke()
    }
}
