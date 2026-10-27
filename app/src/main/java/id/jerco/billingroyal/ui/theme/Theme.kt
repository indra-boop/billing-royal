package id.jerco.billingroyal.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B5FA5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4FF),
    onPrimaryContainer = Color(0xFF001C38),
    secondary = Color(0xFF545F71),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA2C9FF),
    onPrimary = Color(0xFF00315A),
    primaryContainer = Color(0xFF004880),
    onPrimaryContainer = Color(0xFFD3E4FF),
    secondary = Color(0xFFBCC7DB),
    error = Color(0xFFFFB4AB),
)

@Composable
fun BillingRoyalTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}

/** Warna badge status invoice. */
object StatusColors {
    fun container(status: String): Color = when (status) {
        "Draft" -> Color(0xFFE0E0E0)
        "Sent" -> Color(0xFFBBDEFB)
        "Paid" -> Color(0xFFC8E6C9)
        "Cancelled" -> Color(0xFFFFCDD2)
        else -> Color(0xFFE0E0E0)
    }

    fun content(status: String): Color = when (status) {
        "Draft" -> Color(0xFF212121)
        "Sent" -> Color(0xFF0D47A1)
        "Paid" -> Color(0xFF1B5E20)
        "Cancelled" -> Color(0xFFB71C1C)
        else -> Color(0xFF212121)
    }
}

val WarningContainer = Color(0xFFFFF3C4)
val WarningContent = Color(0xFF5C4400)
