package com.jobai.hunter.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * Tokens que Material3 no cubre.
 *
 * Todo lo que la UI necesita pintar sale de aqui. La version anterior tenia
 * hex literales (Color(0xFF111827), Color.White) repartidos por OfferCard y
 * JobHunterApp: por eso el modo oscuro salia descuadrado, porque esos valores
 * no cambian con el tema.
 */
data class JobColors(
    val dark: Boolean,
    val fondo: Color,
    val superficie: Color,
    val tarjeta: Color,
    /** Franjas y chips dentro de una tarjeta. */
    val tarjetaAlt: Color,
    val borde: Color,
    val texto: Color,
    val textoSuave: Color,
    val textoTenue: Color,
    val acento: Color,
    val acentoCian: Color,
    val chipFondo: Color,
    val sombra: Color
) {
    fun match(score: Int) = matchColor(score, dark)
    fun matchFondo(score: Int) = matchFondo(score, dark)
    fun matchTinta(score: Int) = matchTinta(score, dark)
    fun gradiente(score: Int) = matchGradient(score, dark)

    val atipico get() = Semantico.atipico(dark)
    val sinDato get() = Semantico.sinDato(dark)
    val exito get() = Semantico.exito(dark)
    val peligro get() = Semantico.peligro(dark)

    fun velo(c: Color) = Semantico.velo(c, dark)
    fun contorno(c: Color) = Semantico.contorno(c, dark)
    fun portal(p: String) = BrandColors.portalColor(p, dark)
    fun modalidad(m: String) = BrandColors.modalityColor(m, dark)
}

private val JobColorsClaro = JobColors(
    dark = false,
    fondo = PapelFondo,
    superficie = PapelSuperficie,
    tarjeta = PapelTarjeta,
    tarjetaAlt = Color(0xFFF6F9FD),
    borde = PapelBorde,
    texto = PapelTexto,
    textoSuave = PapelTextoSuave,
    textoTenue = PapelTextoTenue,
    acento = AzulElectrico,
    acentoCian = CianVivo,
    chipFondo = Color(0xFFEDF3FF),
    sombra = Color(0x1A1436C8)
)

private val JobColorsOscuro = JobColors(
    dark = true,
    fondo = NocheFondo,
    superficie = NocheSuperficie,
    tarjeta = NocheTarjeta,
    tarjetaAlt = NocheTarjetaAlt,
    borde = NocheBorde,
    texto = NocheTexto,
    textoSuave = NocheTextoSuave,
    textoTenue = NocheTextoTenue,
    acento = Color(0xFF8B5CF6),
    acentoCian = Color(0xFFEC4899),
    chipFondo = NocheTarjetaAlt,
    sombra = Color(0x80000000)
)

val LocalJobColors = staticCompositionLocalOf { JobColorsClaro }

private val EsquemaPapel = lightColorScheme(
    primary = AzulElectrico,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = AzulProfundo,
    secondary = CianVivo,
    onSecondary = Color.White,
    tertiary = VioletaVivo,
    background = PapelFondo,
    surface = PapelTarjeta,
    surfaceVariant = PapelSuperficie,
    onBackground = PapelTexto,
    onSurface = PapelTexto,
    onSurfaceVariant = PapelTextoSuave,
    outline = PapelBorde,
    error = ErrorRed
)

private val EsquemaNoche = darkColorScheme(
    primary = Color(0xFF6D9AEA),
    onPrimary = Color(0xFF08152C),
    primaryContainer = Color(0xFF1D3157),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFF52B7C4),
    onSecondary = Color(0xFF04202C),
    tertiary = Color(0xFF9E8AD6),
    background = NocheFondo,
    surface = NocheTarjeta,
    surfaceVariant = NocheSuperficie,
    onBackground = NocheTexto,
    onSurface = NocheTexto,
    onSurfaceVariant = NocheTextoSuave,
    outline = NocheBorde,
    error = DangerDark
)

val JobShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun JobHunterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) EsquemaNoche else EsquemaPapel
    val jobColors = if (darkTheme) JobColorsOscuro else JobColorsClaro

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalJobColors provides jobColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = JobTypography,
            shapes = JobShapes,
            content = content
        )
    }
}
