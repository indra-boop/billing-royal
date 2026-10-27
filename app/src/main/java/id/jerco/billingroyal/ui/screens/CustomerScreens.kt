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
import id.jerco.billingroyal.core.IdempotencyKeyHolder
import id.jerco.billingroyal.data.remote.ApiResult
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

    private val pager = Pager(viewModelScope) { page -> repo.list(_query.value, page) }
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

    AppScaffold(
        title = "Customer",
        onBack = onBack,
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = "Tambah customer") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                label = { Text("Cari nama atau telepon") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (state.fromCache) InfoBanner("Tidak ada koneksi. Menampilkan data tersimpan.")
            LoadStateView(
                state = state.toLoad(),
                refreshing = state.refreshing,
                onRefresh = viewModel::refresh,
                onRetry = viewModel::retry,
                isEmpty = { it.isEmpty() },
                emptyMessage = if (query.isBlank()) "Belum ada customer." else "Customer tidak ditemukan.",
            ) { items ->
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(items, key = { it.id }) { c ->
                        Column(Modifier.fillMaxWidth().clickable { onOpen(c.id) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(c.name, style = MaterialTheme.typography.titleMedium)
                            val sub = listOfNotNull(c.phone?.takeIf { it.isNotBlank() }, c.price_list_name?.takeIf { it.isNotBlank() })
                            if (sub.isNotEmpty()) Text(sub.joinToString("  |  "), style = MaterialTheme.typography.bodySmall)
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
                                TextButton(onClick = viewModel::loadMore) { Text("Coba lagi") }
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

    AppScaffold(title = "Detail Customer", onBack = onBack) { padding ->
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
                    LabeledValue("Email", c.email.orEmpty())
                    LabeledValue("Telepon", c.phone.orEmpty())
                    LabeledValue("Alamat", c.address.orEmpty())
                    LabeledValue("Price list", c.price_list_name.orEmpty())
                    state.deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(onClick = { onEdit(c.id) }, modifier = Modifier.fillMaxWidth()) { Text("Ubah") }
                    if (viewModel.flags.delete) {
                        OutlinedButton(
                            onClick = { confirmDelete = true },
                            enabled = !state.deleting,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (state.deleting) "Menghapus..." else "Hapus") }
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Hapus customer",
            message = "Customer ini akan dihapus. Customer yang sudah punya invoice tidak dapat dihapus.",
            confirmText = "Hapus",
            onConfirm = { confirmDelete = false; viewModel.delete() },
            onDismiss = { confirmDelete = false },
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
)

private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

@HiltViewModel
class CustomerFormViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: CustomerRepository,
    private val catalog: CatalogRepository,
) : ViewModel() {
    private val editId: Long? = (savedState.get<Long>("id") ?: -1L).takeIf { it >= 0 }
    val isEdit: Boolean get() = editId != null

    private val keys = IdempotencyKeyHolder()
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
                    )
                    is ApiResult.Failure -> it.copy(loading = false, loadError = r.error.message)
                }
            }
        }
    }

    fun update(block: CustomerFormState.() -> CustomerFormState) = _state.update { it.block().copy(error = null) }

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
                repo.create(keys.keyFor(payload.toString()), payload)
            }
            when (result) {
                is ApiResult.Success -> {
                    keys.reset()
                    _state.update { it.copy(saving = false, done = true) }
                }
                is ApiResult.Failure -> _state.update {
                    // Create yang hasilnya tidak pasti: JANGAN auto-retry.
                    if (editId == null && result.error.isOutcomeUncertain) it.copy(saving = false, uncertain = true)
                    else it.copy(saving = false, error = result.error.message)
                }
            }
        }
    }

    fun dismissUncertain() = _state.update { it.copy(uncertain = false) }
}

@Composable
fun CustomerFormScreen(onBack: () -> Unit, onCheckList: () -> Unit, viewModel: CustomerFormViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.done) { if (s.done) onBack() }

    AppScaffold(title = if (viewModel.isEdit) "Ubah Customer" else "Tambah Customer", onBack = onBack) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                s.loading -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
                s.loadError != null -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                ) {
                    Text(s.loadError!!, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.material3.Button(onClick = { viewModel.loadExisting() }) { Text("Coba lagi") }
                }
                else -> Column(
                    Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = s.name, onValueChange = { v -> viewModel.update { copy(name = v, nameError = null) } },
                        label = { Text("Nama *") }, singleLine = true, isError = s.nameError != null,
                        supportingText = { s.nameError?.let { Text(it) } }, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = s.email, onValueChange = { v -> viewModel.update { copy(email = v, emailError = null) } },
                        label = { Text("Email") }, singleLine = true, isError = s.emailError != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        supportingText = { s.emailError?.let { Text(it) } }, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = s.phone, onValueChange = { v -> viewModel.update { copy(phone = v, phoneError = null) } },
                        label = { Text("Telepon") }, singleLine = true, isError = s.phoneError != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        supportingText = { s.phoneError?.let { Text(it) } }, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = s.address, onValueChange = { v -> viewModel.update { copy(address = v, addressError = null) } },
                        label = { Text("Alamat") }, isError = s.addressError != null, minLines = 2,
                        supportingText = { s.addressError?.let { Text(it) } }, modifier = Modifier.fillMaxWidth(),
                    )
                    PriceListPicker(
                        lists = s.priceLists,
                        selectedId = s.priceListId,
                        onSelect = { id -> viewModel.update { copy(priceListId = id) } },
                    )
                    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    SavingButton("Simpan", saving = s.saving, onClick = viewModel::save)
                }
            }
        }
    }

    if (s.uncertain) {
        UncertainDialog(
            message = "Koneksi terputus atau waktu habis setelah data dikirim. Customer mungkin sudah tersimpan. " +
                "Periksa daftar customer terlebih dahulu sebelum mengirim ulang.",
            onCheck = { viewModel.dismissUncertain(); onCheckList() },
            onResend = { viewModel.dismissUncertain(); viewModel.save() },
            onDismiss = viewModel::dismissUncertain,
        )
    }
}

@Composable
private fun PriceListPicker(lists: List<PriceListSummaryDto>, selectedId: Long?, onSelect: (Long?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = lists.firstOrNull { it.id == selectedId }?.name ?: "Tanpa price list"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Price list") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Tanpa price list") }, onClick = { onSelect(null); expanded = false })
            lists.forEach { pl ->
                DropdownMenuItem(text = { Text(pl.name) }, onClick = { onSelect(pl.id); expanded = false })
            }
        }
    }
}
