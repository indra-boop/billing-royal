package id.jerco.billingroyal.ui.screens

import id.jerco.billingroyal.ui.components.RefreshOnResume
import androidx.compose.ui.Alignment
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
import androidx.compose.material3.Button
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import id.jerco.billingroyal.core.FeatureFlags
import id.jerco.billingroyal.data.repo.IdempotencyKind
import id.jerco.billingroyal.data.repo.IdempotencyStore
import id.jerco.billingroyal.data.remote.ApiResult
import id.jerco.billingroyal.data.remote.AppError
import id.jerco.billingroyal.data.remote.displayMessage
import id.jerco.billingroyal.data.remote.CustomerDto
import id.jerco.billingroyal.data.remote.PriceListSummaryDto
import id.jerco.billingroyal.data.remote.isOutcomeUncertain
import id.jerco.billingroyal.data.repo.CatalogRepository
import id.jerco.billingroyal.data.repo.CustomerInput
import id.jerco.billingroyal.data.repo.CustomerRepository
import id.jerco.billingroyal.ui.components.AppScaffold
import id.jerco.billingroyal.ui.components.ConfirmDialog
import id.jerco.billingroyal.ui.components.InfoBanner
import id.jerco.billingroyal.ui.components.LabeledValue
import id.jerco.billingroyal.ui.components.Load
import id.jerco.billingroyal.ui.components.LoadStateView
import id.jerco.billingroyal.ui.components.Pager
import id.jerco.billingroyal.ui.components.SavingButton
import id.jerco.billingroyal.ui.components.UncertainDialog
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ Daftar

@OptIn(FlowPreview::class)
@HiltViewModel
class CustomerListViewModel @Inject constructor(private val repo: CustomerRepository) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val pager = Pager(viewModelScope, key = { it.id }) { page -> repo.list(_query.value, page) }
    val state = pager.state

    init {
        viewModelScope.launch {
            // Search dengan debounce 300 ms (muat awal tanpa jeda).
            _query.debounce { if (it.isEmpty()) 0L else 300L }.distinctUntilChanged().collect { pager.reload() }
        }
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    fun refresh() = pager.reload(pull = true)
    fun retry() = pager.reload()
    fun loadMore() = pager.loadMore()
}

@Composable
fun CustomerListScreen(
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onAdd: () -> Unit,
    viewModel: CustomerListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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
        title = stringResource(R.string.customer),
        onBack = onBack,
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.tambah_customer)) }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                label = { Text(stringResource(R.string.cari_nama_atau_telepon)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                trailingIcon = {
                    if (query.isNotEmpty()) TextButton(onClick = { viewModel.setQuery("") }) { Text(stringResource(R.string.hapus_teks)) }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LoadStateView(
                state = state.toLoad(),
                refreshing = state.refreshing,
                onRefresh = viewModel::refresh,
                onRetry = viewModel::retry,
                isEmpty = { it.isEmpty() },
                fromCache = state.fromCache,
                emptyMessage = if (query.isBlank()) stringResource(R.string.belum_ada_customer) else stringResource(R.string.customer_tidak_ditemukan),
                emptyAction = if (query.isBlank()) ({ Button(onClick = onAdd, modifier = Modifier.minTouch()) { Text(stringResource(R.string.tambah_customer)) } }) else null,
            ) { items ->
                // Ruang bawah agar baris terakhir tidak tertutup tombol tambah (FAB).
                LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 88.dp), modifier = Modifier.fillMaxSize()) {
                    items(items, key = { it.id }) { c ->
                        Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { onOpen(c.id) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(c.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val sub = listOfNotNull(c.phone?.takeIf { it.isNotBlank() }, c.price_list_name?.takeIf { it.isNotBlank() })
                            if (sub.isNotEmpty()) Text(sub.joinToString("  |  "), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        HorizontalDivider()
                    }
                    if (state.loadingMore) {
                        item { Box(Modifier.fillMaxWidth().padding(16.dp)) { CircularProgressIndicator(Modifier.padding(4.dp)) } }
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

// ------------------------------------------------------------------ Detail

data class CustomerDetailState(
    val load: Load<CustomerDto> = Load.Loading,
    val refreshing: Boolean = false,
    val deleting: Boolean = false,
    val deleteError: String? = null,
    val deleted: Boolean = false,
)

@HiltViewModel
class CustomerDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: CustomerRepository,
    val flags: FeatureFlags,
) : ViewModel() {
    private val id: Long = checkNotNull(savedState["id"])
    private val _state = MutableStateFlow(CustomerDetailState())
    val state: StateFlow<CustomerDetailState> = _state

    init {
        load(pull = false)
    }

    fun load(pull: Boolean) {
        _state.update { if (pull) it.copy(refreshing = true) else it.copy(load = Load.Loading) }
        viewModelScope.launch {
            _state.update {
                when (val r = repo.get(id)) {
                    is ApiResult.Success -> it.copy(load = Load.Content(r.data), refreshing = false)
                    is ApiResult.Failure -> it.copy(load = Load.Error(r.error.message), refreshing = false)
                }
            }
        }
    }

    fun delete() {
        if (!flags.delete || _state.value.deleting) return
        _state.update { it.copy(deleting = true, deleteError = null) }
        viewModelScope.launch {
            _state.update {
                when (val r = repo.delete(id)) {
                    is ApiResult.Success -> it.copy(deleting = false, deleted = true)
                    // mis. 409 resource_in_use: pesan server ditampilkan apa adanya
                    is ApiResult.Failure -> it.copy(deleting = false, deleteError = r.error.message)
                }
            }
        }
    }

    fun clearDeleteError() = _state.update { it.copy(deleteError = null) }
}

@Composable
fun CustomerDetailScreen(onBack: () -> Unit, onEdit: (Long) -> Unit, viewModel: CustomerDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }

    AppScaffold(title = stringResource(R.string.detail_customer), onBack = onBack) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LoadStateView(
                state = state.load,
                refreshing = state.refreshing,
                onRefresh = { viewModel.load(pull = true) },
                onRetry = { viewModel.load(pull = false) },
            ) { c ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(c.name, style = MaterialTheme.typography.headlineSmall)
                    LabeledValue(stringResource(R.string.email), c.email.orEmpty())
                    LabeledValue(stringResource(R.string.telepon), c.phone.orEmpty())
                    LabeledValue(stringResource(R.string.alamat), c.address.orEmpty())
                    LabeledValue(stringResource(R.string.price_list), c.price_list_name.orEmpty())
                    state.deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
                    Button(onClick = { onEdit(c.id) }, modifier = Modifier.fillMaxWidth().minTouch()) { Text(stringResource(R.string.ubah)) }
                    if (viewModel.flags.delete) {
                        OutlinedButton(
                            onClick = { confirmDelete = true },
                            enabled = !state.deleting,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth().minTouch(),
                        ) { Text(if (state.deleting) stringResource(R.string.menghapus) else stringResource(R.string.hapus)) }
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.hapus_customer),
            message = stringResource(R.string.customer_ini_akan_dihapus_customer),
            confirmText = stringResource(R.string.hapus),
            onConfirm = { confirmDelete = false; viewModel.delete() },
            onDismiss = { confirmDelete = false },
            destructive = true,
        )
    }
}

// ------------------------------------------------------------------ Form tambah/ubah

data class CustomerFormState(
    val loading: Boolean = false,
    val loadError: String? = null,
    val name: String = "",
    val email: String = "",
    val phone: String = "",
    val address: String = "",
    val priceListId: Long? = null,
    val priceLists: List<PriceListSummaryDto> = emptyList(),
    val saving: Boolean = false,
    val error: String? = null,
    val nameError: String? = null,
    val emailError: String? = null,
    val phoneError: String? = null,
    val addressError: String? = null,
    val uncertain: Boolean = false,
    val done: Boolean = false,
    /** True setelah pengguna mengubah isian: dipakai untuk peringatan saat Kembali. */
    val dirty: Boolean = false,
)

private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

@HiltViewModel
class CustomerFormViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: CustomerRepository,
    private val catalog: CatalogRepository,
    private val keyStore: IdempotencyStore,
) : ViewModel() {
    private val editId: Long? = (savedState.get<Long>("id") ?: -1L).takeIf { it >= 0 }
    val isEdit: Boolean get() = editId != null

    private val _state = MutableStateFlow(CustomerFormState(loading = editId != null))
    val state: StateFlow<CustomerFormState> = _state

    init {
        viewModelScope.launch {
            (catalog.priceLists() as? ApiResult.Success)?.let { r -> _state.update { it.copy(priceLists = r.data) } }
        }
        if (editId != null) loadExisting(editId)
    }

    fun loadExisting(id: Long = checkNotNull(editId)) {
        _state.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            _state.update {
                when (val r = repo.get(id)) {
                    is ApiResult.Success -> it.copy(
                        loading = false,
                        name = r.data.name,
                        email = r.data.email.orEmpty(),
                        phone = r.data.phone.orEmpty(),
                        address = r.data.address.orEmpty(),
                        priceListId = r.data.price_list_id,
                        dirty = false,
                    )
                    is ApiResult.Failure -> it.copy(loading = false, loadError = r.error.message)
                }
            }
        }
    }

    fun update(block: CustomerFormState.() -> CustomerFormState) = _state.update { it.block().copy(error = null, dirty = true) }

    private fun input(s: CustomerFormState) = CustomerInput(s.name, s.email, s.phone, s.address, s.priceListId)

    /** Tombol Simpan: dikunci selama request. Idempotency-Key dibuat sekali dan dipakai ulang untuk payload yang sama. */
    fun save() {
        val s = _state.value
        if (s.saving) return
        val nameError = when {
            s.name.isBlank() -> "Nama wajib diisi."
            s.name.trim().length > 255 -> "Nama maksimal 255 karakter."
            else -> null
        }
        val emailError = when {
            s.email.isBlank() -> null
            s.email.trim().length > 255 -> "Email maksimal 255 karakter."
            !EMAIL_REGEX.matches(s.email.trim()) -> "Format email tidak valid."
            else -> null
        }
        val phoneError = if (s.phone.trim().length > 100) "Telepon maksimal 100 karakter." else null
        val addressError = if (s.address.trim().length > 5000) "Alamat maksimal 5000 karakter." else null
        if (listOf(nameError, emailError, phoneError, addressError).any { it != null }) {
            _state.update {
                it.copy(nameError = nameError, emailError = emailError, phoneError = phoneError, addressError = addressError)
            }
            return
        }
        _state.update {
            it.copy(saving = true, error = null, nameError = null, emailError = null, phoneError = null, addressError = null, uncertain = false)
        }
        val payload = input(s)
        viewModelScope.launch {
            val result = if (editId != null) {
                repo.update(editId, payload) // PUT dengan seluruh field
            } else {
                repo.create(keyStore.keyFor(IdempotencyKind.CUSTOMER_CREATE, payload.toJson()), payload)
            }
            when (result) {
                is ApiResult.Success -> {
                    if (editId == null) keyStore.clear(IdempotencyKind.CUSTOMER_CREATE)
                    _state.update { it.copy(saving = false, done = true) }
                }
                is ApiResult.Failure -> _state.update {
                    val err = result.error
                    // Create yang hasilnya tidak pasti: JANGAN auto-retry.
                    if (editId == null && err.isOutcomeUncertain) {
                        it.copy(saving = false, uncertain = true)
                    } else {
                        // 422: tampilkan rincian per field (pesan umum + garis di bawah field terkait).
                        val fields = (err as? AppError.Api)?.fieldErrors.orEmpty()
                        it.copy(
                            saving = false,
                            error = if (err is AppError.Network && err.notSent) err.message + " Data belum tersimpan." else err.displayMessage,
                            nameError = fields["name"],
                            emailError = fields["email"],
                            phoneError = fields["phone"],
                            addressError = fields["address"],
                        )
                    }
                }
            }
        }
    }

    fun dismissUncertain() = _state.update { it.copy(uncertain = false) }
}

@Composable
fun CustomerFormScreen(onBack: () -> Unit, onCheckList: () -> Unit, viewModel: CustomerFormViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    var confirmLeave by remember { mutableStateOf(false) }
    LaunchedEffect(s.done) { if (s.done) onBack() }
    val guardLeave = s.dirty && !s.done
    BackHandler(enabled = guardLeave) { confirmLeave = true }
    val showForm = !s.loading && s.loadError == null

    AppScaffold(
        title = if (viewModel.isEdit) stringResource(R.string.ubah_customer) else stringResource(R.string.tambah_customer_judul),
        onBack = { if (guardLeave) confirmLeave = true else onBack() },
        bottomBar = {
            if (showForm) {
                Surface(tonalElevation = 3.dp, modifier = Modifier.imePadding()) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
                        SavingButton(stringResource(R.string.simpan), saving = s.saving, onClick = viewModel::save)
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
                    Button(onClick = { viewModel.loadExisting() }, modifier = Modifier.minTouch()) { Text(stringResource(R.string.coba_lagi)) }
                }
                else -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = s.name, onValueChange = { v -> viewModel.update { copy(name = v, nameError = null) } },
                        label = { Text(stringResource(R.string.nama)) }, singleLine = true, isError = s.nameError != null,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                        supportingText = { s.nameError?.let { Text(it) } }, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = s.email, onValueChange = { v -> viewModel.update { copy(email = v, emailError = null) } },
                        label = { Text(stringResource(R.string.email)) }, singleLine = true, isError = s.emailError != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                        supportingText = { s.emailError?.let { Text(it) } }, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = s.phone, onValueChange = { v -> viewModel.update { copy(phone = v, phoneError = null) } },
                        label = { Text(stringResource(R.string.telepon)) }, singleLine = true, isError = s.phoneError != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                        supportingText = { s.phoneError?.let { Text(it) } }, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = s.address, onValueChange = { v -> viewModel.update { copy(address = v, addressError = null) } },
                        label = { Text(stringResource(R.string.alamat)) }, isError = s.addressError != null, minLines = 2,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        supportingText = { s.addressError?.let { Text(it) } }, modifier = Modifier.fillMaxWidth(),
                    )
                    PriceListPicker(
                        lists = s.priceLists,
                        selectedId = s.priceListId,
                        onSelect = { id -> viewModel.update { copy(priceListId = id) } },
                    )
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
            message = stringResource(R.string.customer_koneksi_terputus_pesan),
            title = stringResource(R.string.customer_mungkin_sudah_tersimpan),
            checkLabel = stringResource(R.string.periksa_daftar_customer),
            resendTitle = stringResource(R.string.kirim_ulang_customer_tanya),
            dismissHint = stringResource(R.string.isian_tetap_di_layar),
            onCheck = { viewModel.dismissUncertain(); onCheckList() },
            onResend = { viewModel.dismissUncertain(); viewModel.save() }, // sudah lewat konfirmasi kedua di dialog
            resendWarning = stringResource(R.string.peringatan_kirim_ulang_customer),
            onDismiss = viewModel::dismissUncertain,
        )
    }
}

@Composable
private fun PriceListPicker(lists: List<PriceListSummaryDto>, selectedId: Long?, onSelect: (Long?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = lists.firstOrNull { it.id == selectedId }?.name ?: stringResource(R.string.tanpa_price_list)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.price_list)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.tanpa_price_list)) }, onClick = { onSelect(null); expanded = false })
            lists.forEach { pl ->
                DropdownMenuItem(text = { Text(pl.name) }, onClick = { onSelect(pl.id); expanded = false })
            }
        }
    }
}
