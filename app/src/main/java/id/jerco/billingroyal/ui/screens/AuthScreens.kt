package id.jerco.billingroyal.ui.screens

import id.jerco.billingroyal.ui.components.ConfirmDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import id.jerco.billingroyal.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.size
import id.jerco.billingroyal.ui.components.minTouch
import id.jerco.billingroyal.ui.components.BrandMark
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import id.jerco.billingroyal.data.local.SessionEvent
import id.jerco.billingroyal.data.local.SessionManager
import id.jerco.billingroyal.data.remote.ApiResult
import id.jerco.billingroyal.data.remote.AppError
import id.jerco.billingroyal.data.remote.requiresRelogin
import id.jerco.billingroyal.data.repo.AuthRepository
import id.jerco.billingroyal.ui.components.InfoBanner
import id.jerco.billingroyal.ui.components.SecureScreen
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ Splash

sealed interface SplashState {
    data object Checking : SplashState
    data object GoHome : SplashState
    data object GoLogin : SplashState
    data class Error(val message: String) : SplashState
}

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val sessionManager: SessionManager,
) : ViewModel() {
    private val _state = MutableStateFlow<SplashState>(SplashState.Checking)
    val state: StateFlow<SplashState> = _state

    init {
        check()
    }

    fun check() {
        _state.value = SplashState.Checking
        viewModelScope.launch {
            // 1) token di Keystore  2) GET /me
            if (auth.restoreSession() == null) {
                _state.value = SplashState.GoLogin
                return@launch
            }
            _state.value = when (val result = auth.me()) {
                is ApiResult.Success -> SplashState.GoHome
                is ApiResult.Failure ->
                    if (result.error.requiresRelogin) SplashState.GoLogin else SplashState.Error(result.error.message)
            }
        }
    }

    /** Dari layar error: keluar tanpa menunggu server. */
    fun forceLogin() {
        viewModelScope.launch {
            sessionManager.clearAll()
            _state.value = SplashState.GoLogin
        }
    }
}

@Composable
fun SplashScreen(onHome: () -> Unit, onLogin: () -> Unit, viewModel: SplashViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmWipe by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        when (state) {
            SplashState.GoHome -> onHome()
            SplashState.GoLogin -> onLogin()
            else -> Unit
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Logo bisnis tidak tersedia sebelum login (data lokal dihapus saat keluar/sesi berakhir): tampilkan tanda merek.
        BrandMark(size = 72.dp)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        when (val s = state) {
            is SplashState.Error -> {
                Text(s.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Spacer(Modifier.height(12.dp))
                Button(onClick = viewModel::check, modifier = Modifier.minTouch()) { Text(stringResource(R.string.coba_lagi)) }
                Spacer(Modifier.height(8.dp))
                // Menghapus data perangkat (termasuk draft invoice): selalu minta konfirmasi, bukan sekadar "masuk ulang".
                OutlinedButton(onClick = { confirmWipe = true }, modifier = Modifier.minTouch()) {
                    Text(stringResource(R.string.keluar_dan_hapus_data))
                }
                if (confirmWipe) {
                    ConfirmDialog(
                        title = stringResource(R.string.keluar_dan_hapus_data),
                        message = stringResource(R.string.keluar_dan_hapus_data_pesan),
                        confirmText = stringResource(R.string.ya_hapus_dan_keluar),
                        onConfirm = { confirmWipe = false; viewModel.forceLogin() },
                        onDismiss = { confirmWipe = false },
                        destructive = true,
                    )
                }
            }
            else -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.memeriksa_sesi), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
}

// ------------------------------------------------------------------ Login

data class LoginState(
    val loading: Boolean = false,
    val error: String? = null,
    /** Pesan dari strings.xml (validasi/terjemahan kode galat); didahulukan atas [error]. */
    @androidx.annotation.StringRes val errorRes: Int? = null,
    val success: Boolean = false,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val sessionManager: SessionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state

    /** Alasan kembali ke Login (sesi berakhir / akun nonaktif); null bila pengguna membuka Login biasa. */
    val notice: StateFlow<SessionEvent?> = sessionManager.notice

    fun dismissNotice() = sessionManager.consumeNotice()

    /** Password hanya diteruskan ke request; tidak disimpan dan tidak dicatat. */
    fun login(username: String, password: String) {
        if (_state.value.loading) return
        if (username.isBlank() || password.isEmpty()) {
            _state.value = LoginState(errorRes = R.string.login_wajib_diisi)
            return
        }
        _state.value = LoginState(loading = true)
        viewModelScope.launch {
            // device_name otomatis = Build.MODEL
            _state.value = when (val result = auth.login(username, password, Build.MODEL ?: "Android")) {
                is ApiResult.Success -> LoginState(success = true)
                // invalid_credentials dan 429 diterjemahkan di klien; galat lain (403, 503, ...) memakai pesan server apa adanya.
                is ApiResult.Failure -> {
                    val e = result.error
                    when {
                        e is AppError.Api && e.code == "invalid_credentials" -> LoginState(errorRes = R.string.login_salah)
                        e is AppError.Api && e.status == 429 -> LoginState(errorRes = R.string.login_terlalu_banyak)
                        else -> LoginState(error = e.message)
                    }
                }
            }
        }
    }
}

@Composable
fun LoginScreen(onLoggedIn: () -> Unit, viewModel: LoginViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    SecureScreen()
    var username by rememberSaveable { mutableStateOf("") } // password sengaja TIDAK disimpan ke state instance
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) } // tidak disimpan; kembali tersembunyi saat layar dibuat ulang

    LaunchedEffect(state.success) { if (state.success) onLoggedIn() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.masuk_sebagai_operator), style = MaterialTheme.typography.bodyMedium)
        notice?.let {
            Spacer(Modifier.height(16.dp))
            SessionNoticeCard(it, onDismiss = viewModel::dismissNotice)
        }
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(stringResource(R.string.username)) },
            singleLine = true,
            enabled = !state.loading,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.password)) },
            singleLine = true,
            enabled = !state.loading,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { viewModel.login(username, password) }),
            trailingIcon = {
                // Teks (bukan ikon): material-icons-extended tidak dipakai agar dependency tidak bertambah.
                TextButton(onClick = { showPassword = !showPassword }) {
                    Text(stringResource(if (showPassword) R.string.sembunyikan_password_pendek else R.string.tampilkan_password_pendek))
                }
            },
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
        )
        val errorText = state.errorRes?.let { stringResource(it) } ?: state.error
        errorText?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { viewModel.login(username, password) },
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth().minTouch(),
        ) {
            if (state.loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.masuk_loading))
            } else {
                Text(stringResource(R.string.masuk))
            }
        }
    }
}

/**
 * Pemberitahuan di Login saat sesi diakhiri server (UX-02): judul tebal, satu kalimat, butir petunjuk, bisa ditutup,
 * dan diumumkan ke pembaca layar. Penghapusan data lokal tetap sesuai aturan; ini hanya memperjelas komunikasinya.
 */
@Composable
fun SessionNoticeCard(event: SessionEvent, onDismiss: () -> Unit) {
    val (title, body, hint) = when (event) {
        SessionEvent.EXPIRED -> Triple(R.string.notice_expired_title, R.string.notice_expired_body, R.string.notice_expired_hint)
        SessionEvent.ACCOUNT_INACTIVE -> Triple(R.string.notice_inactive_title, R.string.notice_inactive_body, null)
    }
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
            if (hint != null) Text("• " + stringResource(hint), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).minTouch()) { Text(stringResource(R.string.mengerti)) }
        }
    }
}
