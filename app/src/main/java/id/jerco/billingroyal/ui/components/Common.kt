package id.jerco.billingroyal.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import id.jerco.billingroyal.R
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import id.jerco.billingroyal.data.remote.InvoiceStatus
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.jerco.billingroyal.core.AppDates
import id.jerco.billingroyal.ui.theme.StatusColors
import id.jerco.billingroyal.ui.theme.WarningColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Kerangka layar standar: app bar + isi + snackbar. */
@Composable
fun AppScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    snackbarHostState: SnackbarHostState? = null,
    actions: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.kembali))
                        }
                    }
                },
                actions = { actions() },
            )
        },
        snackbarHost = { snackbarHostState?.let { SnackbarHost(it) } },
        floatingActionButton = floatingActionButton,
        bottomBar = bottomBar,
        content = content,
    )
}

/**
 * Menampilkan empat keadaan layar: Loading, Empty, Error (tombol Coba lagi), Content,
 * semuanya di dalam PullToRefreshBox sehingga tarik-untuk-segarkan selalu tersedia.
 */
@Composable
fun <T> LoadStateView(
    state: Load<T>,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    isEmpty: (T) -> Boolean = { false },
    emptyMessage: String = stringResource(R.string.belum_ada_data),
    /** Tombol ajakan (CTA) di bawah teks kosong, mis. "Tambah customer". */
    emptyAction: (@Composable () -> Unit)? = null,
    /** True bila data yang tampil berasal dari cache (offline): banner konsisten di semua layar berdaftar. */
    fromCache: Boolean = false,
    content: @Composable (T) -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        if (fromCache && state is Load.Content) OfflineBanner()
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            when (state) {
                Load.Loading -> CenteredScroll {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.memuat), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                is Load.Error -> CenteredScroll {
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRetry, modifier = Modifier.minTouch()) { Text(stringResource(R.string.coba_lagi)) }
                }
                is Load.Content -> if (isEmpty(state.data)) {
                    CenteredScroll {
                        Text(emptyMessage, style = MaterialTheme.typography.bodyLarge)
                        if (emptyAction != null) {
                            Spacer(Modifier.height(12.dp))
                            emptyAction()
                        }
                    }
                } else {
                    content(state.data)
                }
            }
        }
    }
}

/**
 * Muat ulang (tanpa menghapus daftar yang tampil) saat layar kembali aktif, mis. setelah membuat/mengubah data di layar lain.
 * Pemanggilan pertama saat layar baru dibuka dilewati karena ViewModel sudah memuat sendiri.
 */
@Composable
fun RefreshOnResume(onRefresh: () -> Unit) {
    var first by remember { mutableStateOf(true) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if (first) first = false else onRefresh()
    }
}

/** Banner offline yang sama di semua layar yang melayani data cache; diumumkan ke pembaca layar saat muncul. */
@Composable
fun OfflineBanner(modifier: Modifier = Modifier) {
    InfoBanner(stringResource(R.string.tidak_ada_koneksi_menampilkan_data), modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun CenteredScroll(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

@Composable
fun StatusBadge(status: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = StatusColors.container(status),
        contentColor = StatusColors.content(status),
    ) {
        Text(
            text = InvoiceStatus.label(status),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Peringatan kuning (mis. harga item bernilai 0). */
@Composable
fun WarningBox(message: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = WarningColors.container, contentColor = WarningColors.content) {
        Text(message, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun InfoBanner(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(message, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    /** Aksi merusak (hapus/keluar): tombol konfirmasi berwarna error agar tidak tertukar dengan konfirmasi biasa. */
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors(),
            ) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.batal)) } },
    )
}

/**
 * Dialog hasil tidak pasti setelah timeout/network error pada request tulis. Tidak ada auto-retry.
 * Aksi utama (tombol terisi) adalah "Periksa ...". "Kirim ulang" butuh konfirmasi kedua yang
 * menjelaskan risiko data ganda; pada konfirmasi itu "Batal" adalah tombol terisi (pilihan aman).
 */
@Composable
fun UncertainDialog(
    message: String,
    onCheck: () -> Unit,
    onResend: () -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.data_mungkin_sudah_tersimpan),
    checkLabel: String = stringResource(R.string.periksa_daftar),
    resendTitle: String = stringResource(R.string.kirim_ulang),
    /** Penjelasan kecil di bawah isi tentang apa yang terjadi bila dialog ditutup ("Nanti"). */
    dismissHint: String? = null,
    resendWarning: String = stringResource(R.string.peringatan_kirim_ulang_umum),
    extra: @Composable () -> Unit = {},
) {
    var confirmResend by remember { mutableStateOf(false) }
    if (confirmResend) {
        AlertDialog(
            onDismissRequest = { confirmResend = false },
            title = { Text(resendTitle) },
            text = { Text(resendWarning) },
            confirmButton = { TextButton(onClick = { confirmResend = false; onResend() }) { Text(stringResource(R.string.ya_kirim_ulang)) } },
            dismissButton = { Button(onClick = { confirmResend = false }) { Text(stringResource(R.string.batal)) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(message)
                extra()
                if (dismissHint != null) {
                    Text(dismissHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { Button(onClick = onCheck, modifier = Modifier.minTouch()) { Text(checkLabel) } },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmResend = true }) { Text(stringResource(R.string.kirim_ulang_dots)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.nanti)) }
            }
        },
    )
}

/** Tombol tanggal yang membuka DatePicker Material 3. */
@Composable
fun DateField(
    label: String,
    date: LocalDate,
    onDateChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier.minTouch(), enabled = enabled) {
        Text("$label: ${AppDates.display(date)}")
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        onDateChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    open = false
                }) { Text(stringResource(R.string.pilih)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.batal)) } },
        ) { DatePicker(state = state) }
    }
}

@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "-" }, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Tombol simpan. Saat [saving] tombol TIDAK dinonaktifkan secara visual (hanya klik yang dikunci), supaya spinner tetap
 * tampil di atas warna primary dengan kontras cukup (UI-02).
 */
@Composable
fun SavingButton(text: String, saving: Boolean, enabled: Boolean = true, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = { if (!saving) onClick() },
        enabled = enabled,
        modifier = modifier.fillMaxWidth().minTouch().semantics { if (saving) stateDescription = "Sedang menyimpan" },
    ) {
        if (saving) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.menyimpan))
        } else {
            Text(text)
        }
    }
}

@Composable
fun CenteredBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}


/** Tinggi minimum 48dp untuk aksi utama (target sentuh Material/Android a11y). */
fun Modifier.minTouch(): Modifier = this.heightIn(min = 48.dp)

/**
 * Tanda merek PLACEHOLDER (huruf "B" di atas warna seed). Dipakai di Login dan sebagai cadangan bila bisnis belum punya logo.
 * Bukan logo laundry asli: ganti drawable `ic_launcher_foreground`/warna `brand_seed` setelah pemilik menyerahkan asetnya.
 * Dekoratif (contentDescription null) karena nama aplikasi/bisnis selalu tampil di dekatnya.
 */
@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = 72.dp) {
    Box(modifier.size(size).clip(CircleShape).background(colorResource(R.color.brand_seed))) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(size))
    }
}
