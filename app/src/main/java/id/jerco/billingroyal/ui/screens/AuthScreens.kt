package id.jerco.billingroyal.ui.screens

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
import id.jerco.billingroyal.data.local.SessionManager
import id.jerco.billingroyal.data.remote.ApiResult
import id.jerco.billingroyal.data.remote.requiresRelogin
import id.jerco.billingroyal.data.repo.AuthRepository
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
            sessionManager.clearAll(notify = false)
            _state.value = SplashState.GoLogin
        }
    }
}

@Composable
fun SplashScreen(onHome: () -> Unit, onLogin: () -> Unit, viewModel: SplashViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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
        Text("Billing Royal", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        when (val s = state) {
            is SplashState.Error -> {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Button(onClick = viewModel::check) { Text("Coba lagi") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = viewModel::forceLogin) { Text("Masuk ulang") }
            }
            else -> CircularProgressIndicator()
        }
    }
}

// ------------------------------------------------------------------ Login

data class LoginState(val loading: Boolean = false, val error: String? = null, val success: Boolean = false)

@HiltViewModel
class LoginViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state

    /** Password hanya diteruskan ke request; tidak disimpan dan tidak dicatat. */
    fun login(username: String, password: String) {
        if (_state.value.loading) return
        if (username.isBlank() || password.isEmpty()) {
            _state.value = LoginState(error = "Username dan password wajib diisi.")
            return
        }
        _state.value = LoginState(loading = true)
        viewModelScope.launch {
            // device_name otomatis = Build.MODEL
            _state.value = when (val result = auth.login(username, password, Build.MODEL ?: "Android")) {
                is ApiResult.Success -> LoginState(success = true)
                // 401 invalid_credentials, 403, 429, 503: tampilkan pesan server apa adanya.
                is ApiResult.Failure -> LoginState(error = result.error.message)
            }
        }
    }
}

@Composable
fun LoginScreen(onLoggedIn: () -> Unit, viewModel: LoginViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

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
        Text("Billing Royal", style = MaterialTheme.typography.headlineMedium)
        Text("Masuk sebagai operator", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            singleLine = true,
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            enabled = !state.loading,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        state.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { viewModel.login(username, password) },
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.loading) CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp) else Text("Masuk")
        }
    }
}
