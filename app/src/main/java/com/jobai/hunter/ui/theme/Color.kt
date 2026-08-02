package com.jobai.hunter.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

// ===========================================================================
// JOB HUNTER — Paleta
//
// El problema del modo oscuro anterior: se reusaban los mismos colores
// saturados del modo claro como RELLENO. Un disco cian #4DF3FF de 54dp sobre
// una tarjeta #16203A quema la vista y compite con el titulo de la oferta.
//
// Regla de esta paleta: en oscuro los colores saturados solo se usan como
// TINTA (texto, bordes, iconos). Los rellenos son el mismo color al 14-18%
// de opacidad sobre la tarjeta. En claro se mantiene el relleno solido, que
// ahi si tiene contraste suficiente.
// ===========================================================================

// --- Marca ---------------------------------------------------------------
val AzulProfundo  = Color(0xFF1436C8)
val AzulElectrico = Color(0xFF2563EB)
val AzulBrillante = Color(0xFF4F8DFF)
val CianVivo      = Color(0xFF0891B2)
val VioletaVivo   = Color(0xFF7C3AED)
val NaranjaVivo   = Color(0xFFEA7317)
val RosaVivo      = Color(0xFFE11D6B)
val VerdeVivo     = Color(0xFF059669)

// ---------------------------------------------------------------------------
// PAPEL (modo claro)
// ---------------------------------------------------------------------------
val PapelFondo      = Color(0xFFF4F7FC)
val PapelSuperficie = Color(0xFFEAF0F9)
val PapelTarjeta    = Color(0xFFFFFFFF)
val PapelBorde      = Color(0xFFE2E8F0)
val PapelTexto      = Color(0xFF0F172A)
val PapelTextoSuave = Color(0xFF475569)
val PapelTextoTenue = Color(0xFF94A3B8)

// ---------------------------------------------------------------------------
// NOCHE (modo oscuro AMOLED)
// Fondo negro absoluto (AMOLED #000000) con tarjetas de azul-pizarra profundo
// ---------------------------------------------------------------------------
val NocheFondo      = Color(0xFF000000)   // Negro AMOLED Puro
val NocheSuperficie = Color(0xFF080D1A)   // Superficie ultra oscura
val NocheTarjeta    = Color(0xFF0F172A)   // Tarjeta de alto contraste
val NocheTarjetaAlt = Color(0xFF162035)   // Franjas, chips, estados hover
val NocheBorde      = Color(0xFF222F47)
val NocheTexto      = Color(0xFFF1F5F9)
val NocheTextoSuave = Color(0xFF94A3B8)
val NocheTextoTenue = Color(0xFF64748B)

// ---------------------------------------------------------------------------
// GRADIENTES MULTICOLOR (Inspirados en Sunset / Instagram palette)
// Combina Indigo -> Violeta -> Magenta -> Naranja cálido
// ---------------------------------------------------------------------------
object GradientPalette {
    val Sunset = listOf(
        Color(0xFF6366F1), // Indigo
        Color(0xFF8B5CF6), // Violeta
        Color(0xFFEC4899), // Magenta / Rosa
        Color(0xFFF97316)  // Naranja cálido
    )

    val SunsetSoft = listOf(
        Color(0xFF818CF8),
        Color(0xFFC084FC),
        Color(0xFFF472B6),
        Color(0xFFFB923C)
    )

    val TabIndicator = listOf(
        Color(0xFF6366F1),
        Color(0xFFA855F7),
        Color(0xFFEC4899),
        Color(0xFFF97316)
    )

    val HeroButton = listOf(
        Color(0xFF4F46E5),
        Color(0xFF7C3AED),
        Color(0xFFDB2777),
        Color(0xFFEA580C)
    )
}

// ---------------------------------------------------------------------------
// Rampa de match: Gris -> Indigo -> Violeta -> Magenta -> Naranja (Multicolor)
// ---------------------------------------------------------------------------
private val RAMPA_CLARO = listOf(
    0 to Color(0xFF94A3B8),
    45 to Color(0xFF6087E8),
    70 to AzulElectrico,
    88 to Color(0xFF0E9BB8),
    100 to Color(0xFF0E8C99)
)

private val RAMPA_OSCURO = listOf(
    0 to Color(0xFF64748B),   // Gris
    45 to Color(0xFF6366F1),  // Indigo
    70 to Color(0xFF8B5CF6),  // Violeta
    85 to Color(0xFFEC4899),  // Magenta
    100 to Color(0xFFF97316)  // Naranja / Ámbar cálido
)

fun matchColor(score: Int, dark: Boolean): Color {
    val rampa = if (dark) RAMPA_OSCURO else RAMPA_CLARO
    val s = score.coerceIn(0, 100)
    for (i in 0 until rampa.size - 1) {
        val (p0, c0) = rampa[i]
        val (p1, c1) = rampa[i + 1]
        if (s in p0..p1) {
            val t = if (p1 == p0) 0f else (s - p0).toFloat() / (p1 - p0)
            return lerp(c0, c1, t)
        }
    }
    return rampa.last().second
}

/**
 * Relleno del disco de match.
 * Oscuro: velo del propio color, para que el disco no sea un foco de luz.
 * Claro: color solido, que ahi tiene el contraste justo.
 */
fun matchFondo(score: Int, dark: Boolean): Color =
    if (dark) matchColor(score, true).copy(alpha = 0.16f) else matchColor(score, false)

/** Tinta del numero dentro del disco. */
fun matchTinta(score: Int, dark: Boolean): Color =
    if (dark) matchColor(score, true) else Color.White

fun matchGradient(score: Int, dark: Boolean): List<Color> {
    if (dark) {
        val base = matchColor(score, true)
        val siguiente = matchColor((score + 20).coerceAtMost(100), true)
        return listOf(base, siguiente)
    }
    val base = matchColor(score, false)
    val alto = matchColor((score + 18).coerceAtMost(100), false)
    return listOf(base, alto)
}

// ---------------------------------------------------------------------------
// Semanticos
// ---------------------------------------------------------------------------
object Semantico {
    /** Regimen atipico (14x7, campamento). */
    fun atipico(dark: Boolean) = if (dark) Color(0xFFE0A458) else Color(0xFFB45309)

    /** "No se pudo determinar". Deliberadamente apagado. */
    fun sinDato(dark: Boolean) = if (dark) Color(0xFF7B8AA5) else Color(0xFF94A3B8)

    fun exito(dark: Boolean) = if (dark) Color(0xFF4DBF8F) else VerdeVivo

    fun peligro(dark: Boolean) = if (dark) Color(0xFFE87B8E) else Color(0xFFDC2626)

    /** Fondo de una pastilla dado su color de tinta. */
    fun velo(color: Color, dark: Boolean) = color.copy(alpha = if (dark) 0.16f else 0.12f)

    /** Borde de una pastilla dado su color de tinta. */
    fun contorno(color: Color, dark: Boolean) = color.copy(alpha = if (dark) 0.34f else 0.28f)
}

// ---------------------------------------------------------------------------
// Marcas de portal y modalidad
// En oscuro todas bajan de saturacion para convivir en la misma lista.
// ---------------------------------------------------------------------------
object BrandColors {
    fun portalColor(portal: String, dark: Boolean): Color = when {
        portal.contains("LinkedIn", true) -> if (dark) Color(0xFF5AA9E0) else Color(0xFF0A66C2)
        portal.contains("Computrabajo", true) -> if (dark) Color(0xFFD99A5B) else NaranjaVivo
        portal.contains("Bumeran", true) -> if (dark) Color(0xFFD4738F) else RosaVivo
        else -> if (dark) Color(0xFF9E8AD6) else VioletaVivo
    }

    fun modalityColor(modality: String, dark: Boolean): Color = when {
        modality.contains("Remoto", true) -> if (dark) Color(0xFF52B7C4) else CianVivo
        modality.contains("Hibrido", true) || modality.contains("Híbrido", true) ->
            if (dark) Color(0xFF9E8AD6) else VioletaVivo
        modality.contains("Presencial", true) -> if (dark) Color(0xFF7FA0DB) else AzulElectrico
        else -> if (dark) Color(0xFF7B8AA5) else Color(0xFF64748B)
    }

    private val PALETA_LOGOS = listOf(
        AzulElectrico, CianVivo, VioletaVivo, NaranjaVivo,
        RosaVivo, VerdeVivo, Color(0xFF6366F1), Color(0xFFD97706)
    )

    fun companyColor(empresa: String): Color {
        if (empresa.isBlank()) return AzulElectrico
        return PALETA_LOGOS[empresa.uppercase().sumOf { it.code } % PALETA_LOGOS.size]
    }
}

val ErrorRed = Color(0xFFDC2626)
val DangerDark = Color(0xFFE87B8E)
