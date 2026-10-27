package id.jerco.billingroyal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import id.jerco.billingroyal.data.local.SessionManager
import id.jerco.billingroyal.ui.nav.AppNavHost
import id.jerco.billingroyal.ui.theme.BillingRoyalTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        // Harus sebelum super.onCreate: mengganti tema peluncuran ke Theme.BillingRoyal. Layar Splash Compose tetap
        // dipakai untuk pemeriksaan sesi (butuh tampilan galat/coba lagi).
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BillingRoyalTheme {
                // Surface menyetel LocalContentColor (onBackground) dan latar mengikuti skema aktif, termasuk dynamic color,
                // sehingga teks layar Splash/Login (yang bukan Scaffold) terbaca di mode gelap.
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavHost(sessionManager = sessionManager)
                }
            }
        }
    }
}
