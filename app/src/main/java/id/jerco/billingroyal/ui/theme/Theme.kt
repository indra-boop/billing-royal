package id.jerco.billingroyal.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Warna merek SEMENTARA (biru #0B5FA5 adalah placeholder buatan, BUKAN identitas merek laundry). Ganti seed ini
 * (dan ikon launcher) setelah pemilik menyerahkan logo/palet merek yang asli.
 *
 * Skema terang/gelap di bawah memakai turunan tonal dari seed tersebut (Material 3), dengan primary terang = seed.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF0B5FA5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4FF),
    onPrimaryContainer = Color(0xFF001C38),
    secondary = Color(0xFF546071),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E3F8),
    onSecondaryContainer = Color(0xFF111C2B),
    tertiary = Color(0xFF635983),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE9DDFF),
    onTertiaryContainer = Color(0xFF1E1530),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF9F9FE),
    onBackground = Color(0xFF2E333A),
    surface = Color(0xFFF9F9FE),
    onSurface = Color(0xFF2E333A),
    surfaceVariant = Color(0xFFDFE2EC),
    onSurfaceVariant = Color(0xFF5B5F67),
    outline = Color(0xFF777B83),
    outlineVariant = Color(0xFFAEB2BB),
    inverseSurface = Color(0xFF2F3033),
    inverseOnSurface = Color(0xFFF1F0F4),
    inversePrimary = Color(0xFFA2C9FF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F3FA),
    surfaceContainer = Color(0xFFECEEF5),
    surfaceContainerHigh = Color(0xFFE5E8F0),
    surfaceContainerHighest = Color(0xFFDFE2EC),
    surfaceDim = Color(0xFFD6DAE4),
    surfaceBright = Color(0xFFF9F9FE),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA2C9FF),
    onPrimary = Color(0xFF00315A),
    primaryContainer = Color(0xFF004880),
    onPrimaryContainer = Color(0xFFD3E4FF),
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF253140),
    secondaryContainer = Color(0xFF3C4858),
    onSecondaryContainer = Color(0xFFD7E3F8),
    tertiary = Color(0xFFD0BFF2),
    onTertiary = Color(0xFF352B52),
    tertiaryContainer = Color(0xFF4C426A),
    onTertiaryContainer = Color(0xFFE9DDFF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0C0E12),
    onBackground = Color(0xFFE2E5EF),
    surface = Color(0xFF0C0E12),
    onSurface = Color(0xFFE2E5EF),
    surfaceVariant = Color(0xFF444850),
    onSurfaceVariant = Color(0xFFC4C7D0),
    outline = Color(0xFF8E9199),
    outlineVariant = Color(0xFF444850),
    inverseSurface = Color(0xFFE2E5EF),
    inverseOnSurface = Color(0xFF2E333A),
    inversePrimary = Color(0xFF0B5FA5),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF111418),
    surfaceContainer = Color(0xFF161A1F),
    surfaceContainerHigh = Color(0xFF1C2026),
    surfaceContainerHighest = Color(0xFF21262D),
    surfaceDim = Color(0xFF0C0E12),
    surfaceBright = Color(0xFF282C34),
)

/**
 * Dynamic color (Material You, Android 12+). Karena warna biru di atas hanya placeholder (merek belum ada), dynamic color
 * DIAKTIFKAN secara default dan hanya dipakai di API 31+. Ubah ke `false` bila pemilik ingin warna merek tetap konsisten
 * setelah palet merek yang asli tersedia.
 */
const val USE_DYNAMIC_COLOR = true

@Composable
fun BillingRoyalTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = USE_DYNAMIC_COLOR,
    content: @Composable () -> Unit,
) {
    val colors: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

/** Mode gelap yang sama dengan yang dipakai memilih skema (bukan tebakan dari luminansi surface). */
val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
@ReadOnlyComposable
private fun isDarkSurface(): Boolean = LocalDarkTheme.current

/**
 * Warna badge status invoice. Pasangan container/teks tetap (kontras sudah dihitung di review UI), varian gelap mengikuti
 * mode gelap tema. Catatan dynamic color: warna ini TIDAK ikut wallpaper (sengaja, agar status tetap dikenali).
 */
object StatusColors {
    @Composable
    @ReadOnlyComposable
    fun container(status: String): Color {
        val dark = isDarkSurface()
        return when (status) {
            "Sent" -> if (dark) Color(0xFF0D3B66) else Color(0xFFBBDEFB)
            "Paid" -> if (dark) Color(0xFF1B4D2B) else Color(0xFFC8E6C9)
            "Cancelled" -> if (dark) Color(0xFF6B1F1F) else Color(0xFFFFCDD2)
            else -> if (dark) Color(0xFF3A3D42) else Color(0xFFE0E0E0) // Draft
        }
    }

    @Composable
    @ReadOnlyComposable
    fun content(status: String): Color {
        val dark = isDarkSurface()
        return when (status) {
            "Sent" -> if (dark) Color(0xFFBBDEFB) else Color(0xFF0D47A1)
            "Paid" -> if (dark) Color(0xFFC8E6C9) else Color(0xFF1B5E20)
            "Cancelled" -> if (dark) Color(0xFFFFCDD2) else Color(0xFFB71C1C)
            else -> if (dark) Color(0xFFE2E2E6) else Color(0xFF212121)
        }
    }
}

object WarningColors {
    val container: Color
        @Composable @ReadOnlyComposable get() = if (isDarkSurface()) Color(0xFF4A3B00) else Color(0xFFFFF3C4)
    val content: Color
        @Composable @ReadOnlyComposable get() = if (isDarkSurface()) Color(0xFFFFE08A) else Color(0xFF5C4400)
}
