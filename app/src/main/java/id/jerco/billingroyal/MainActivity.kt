package id.jerco.billingroyal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BillingRoyalTheme {
                AppNavHost(sessionManager = sessionManager)
            }
        }
    }
}
