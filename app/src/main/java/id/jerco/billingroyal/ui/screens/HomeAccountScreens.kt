package id.jerco.billingroyal.ui.screens

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

data class HomeData(val businessName: String, val from: LocalDate, val to: LocalDate, val summary: ReportSummaryDto)

data class HomeUiState(val load: Load<HomeData> = Load.Loading, val refreshing: Boolean = false)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val reports: ReportRepository,
    private val sessionManager: SessionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    private var loadedOnce = false
    private var job: Job? = null

    /**
     * Dipanggil setiap layar Home kembali aktif (ON_RESUME). Pertama kali memuat penuh; setelah itu
     * menyegarkan diam-diam (tampilan lama tetap) agar ringkasan tidak basi setelah membuat invoice.
     */
    fun onResume() {
        if (!loadedOnce) {
            loadedOnce = true
            load(pull = false)
        } else {
            refreshSilently()
        }
    }

    private fun refreshSilently() {
        if (_state.value.load !is Load.Content) return load(pull = false)
        job?.cancel()
        job = viewModelScope.launch {
            val today = AppDates.today()
            val from = today.withDayOfMonth(1)
            val name = sessionManager.session.value?.businessName.orEmpty()
            // Gagal: data lama tetap tampil (tidak diganti layar error).
            (reports.invoices(from, today) as? ApiResult.Success)?.let { r ->
                _state.value = HomeUiState(Load.Content(HomeData(name, from, today, r.data.summary)))
            }
        }
    }

    fun load(pull: Boolean) {
        _state.update { if (pull) it.copy(refreshing = true) else HomeUiState() }
        job?.cancel()
        job = viewModelScope.launch {
            val today = AppDates.today()
            val from = today.withDayOfMonth(1)
            val name = sessionManager.session.value?.businessName.orEmpty()
            _state.value = when (val r = reports.invoices(from, today)) {
                is ApiResult.Success -> HomeUiState(Load.Content(HomeData(name, from, today, r.data.summary)))
                is ApiResult.Failure -> HomeUiState(Load.Error(r.error.message))
            }
        }
    }
}

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
    AppScaffold(title = stringResource(R.string.app_name)) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LoadStateView(
                state = state.load,
                refreshing = state.refreshing,
                onRefresh = { viewModel.load(pull = true) },
                onRetry = { viewModel.load(pull = false) },
            ) { data ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(data.businessName.ifBlank { "Laundry" }, style = MaterialTheme.typography.headlineSmall)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Ringkasan bulan ini (${AppDates.display(data.from)} - ${AppDates.display(data.to)})",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            SummaryRows(data.summary)
                        }
                    }
                    Button(onClick = onCreateInvoice, modifier = Modifier.fillMaxWidth()) { Text("Buat Invoice") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onCustomers, modifier = Modifier.weight(1f)) { Text("Customer") }
                        Button(onClick = onExpenses, modifier = Modifier.weight(1f)) { Text("Pengeluaran") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onInvoices, modifier = Modifier.weight(1f)) { Text("Daftar Invoice") }
                        OutlinedButton(onClick = onReport, modifier = Modifier.weight(1f)) { Text("Rekap") }
                    }
                    OutlinedButton(onClick = onAccount, modifier = Modifier.fillMaxWidth()) { Text("Akun") }
                }
            }
        }
    }
}

@Composable
fun SummaryRows(summary: ReportSummaryDto) {
    LabeledValue("Jumlah invoice", summary.invoice_count.toString())
    LabeledValue("Total qty", MoneyFormatter.formatQty(summary.total_quantity))
    LabeledValue("Total invoice", MoneyFormatter.format(summary.invoice_total))
    LabeledValue("Total dibayar", MoneyFormatter.format(summary.paid_total))
    LabeledValue("Total sisa", MoneyFormatter.format(summary.balance_total))
}

// ------------------------------------------------------------------ Akun

data class AccountData(val session: StoredSession, val profile: ProfileEntity?)

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
                    _load.value = if (cached != null && pull) Load.Content(AccountData(cached, auth.cachedProfile()))
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

    AppScaffold(title = "Akun", onBack = onBack) { padding ->
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
                    data.profile?.logoPath?.let { path -> LogoImage(path, Modifier.size(72.dp)) }
                    LabeledValue("Nama", data.session.fullName)
                    LabeledValue("Username", data.session.username)
                    LabeledValue("Peran", data.session.role)
                    LabeledValue("Laundry", data.session.businessName)
                    LabeledValue("Kode laundry", data.session.businessCode)
                    data.profile?.address?.let { LabeledValue("Alamat", it) }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { confirm = true }, enabled = !loggingOut, modifier = Modifier.fillMaxWidth()) {
                        Text(if (loggingOut) "Keluar..." else "Keluar")
                    }
                }
            }
        }
    }
    if (confirm) {
        ConfirmDialog(
            title = "Keluar",
            message = "Draft invoice yang belum disimpan akan hilang, dan semua data lokal dihapus dari perangkat ini. Lanjut keluar?",
            confirmText = "Keluar",
            onConfirm = { confirm = false; viewModel.logout() },
            onDismiss = { confirm = false },
        )
    }
}

/** Menampilkan logo dari file cache (didecode sekali setelah login), di-downsample. */
@Composable
fun LogoImage(path: String, modifier: Modifier = Modifier) {
    val bitmap = remember(path) {
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
        Image(bitmap = bitmap, contentDescription = stringResource(R.string.logo_description), modifier = modifier)
    }
}
