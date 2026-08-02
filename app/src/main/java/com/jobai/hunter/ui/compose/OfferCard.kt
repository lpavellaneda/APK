package com.jobai.hunter.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jobai.hunter.ui.theme.GradientPalette
import com.jobai.hunter.ui.theme.LocalJobColors
import com.jobai.hunter.ui.theme.NumberFont

/**
 * La tarjeta ya no calcula nada: recibe un [JobUiModel] con los textos hechos.
 * Los `remember` que quedan resuelven colores del tema, que son baratos.
 */
@Composable
fun OfferCard(
    offer: JobUiModel,
    onVerOferta: () -> Unit,
    onTogglePostulada: () -> Unit,
    onDescartar: () -> Unit,
    onClick: () -> Unit
) {
    val jc = LocalJobColors.current
    val postulada = offer.postulada
    val esAtipica = offer.esAtipica

    val portalColor = remember(offer.portal, jc.dark) { jc.portal(offer.portal) }
    val modColor = remember(offer.modalidad, jc.dark) { jc.modalidad(offer.modalidad) }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp),
        shape = RoundedCornerShape(18.dp),
        color = jc.tarjeta,
        border = BorderStroke(1.dp, if (esAtipica) jc.contorno(jc.atipico) else jc.borde)
    ) {
        Column(modifier = Modifier.alpha(if (postulada) 0.5f else 1f)) {

            // --- Franja de portal --------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(jc.tarjetaAlt)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(6.dp).background(portalColor, CircleShape))
                Spacer(Modifier.width(7.dp))
                Text(
                    text = offer.portalUpper,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = portalColor,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.weight(1f))
                if (esAtipica) {
                    Text(
                        text = "RÉGIMEN ATÍPICO",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = jc.atipico,
                        letterSpacing = 0.8.sp
                    )
                } else if (offer.nuevo) {
                    Text(
                        text = "NUEVA",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = jc.exito,
                        letterSpacing = 0.8.sp
                    )
                }
            }

            // --- Cuerpo -------------------------------------------------
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 13.dp, bottom = 10.dp),
                verticalAlignment = Alignment.Top
            ) {
                DiscoMatch(offer.matchScore)

                Spacer(Modifier.width(13.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        text = offer.titulo,
                        style = MaterialTheme.typography.titleMedium,
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        color = jc.texto,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = offer.empresa,
                        style = MaterialTheme.typography.bodySmall,
                        color = jc.textoSuave,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Place,
                            contentDescription = null,
                            tint = jc.textoTenue,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = offer.ubicacion,
                            style = MaterialTheme.typography.bodySmall,
                            color = jc.textoTenue,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    val salarioTxt = offer.salarioTexto
                    if (salarioTxt != null) {
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = salarioTxt,
                            style = MaterialTheme.typography.labelMedium,
                            color = jc.exito,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }

            // --- Etiquetas ----------------------------------------------
            // Row + horizontalScroll en vez de LazyRow: son 4-7 chips conocidos
            // y montar un LazyRow anidado por tarjeta cuesta mas que dibujarlos.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                offer.etiquetaJornada?.let {
                    Etiqueta(it, if (esAtipica) jc.atipico else jc.sinDato, fuerte = esAtipica)
                }
                if (offer.modalidadVisible.isNotBlank()) {
                    Etiqueta(offer.modalidadVisible, modColor)
                }
                Etiqueta(offer.etiquetaExperiencia, jc.textoSuave)
                if (postulada) Etiqueta("Postulada", jc.acento, fuerte = true)
                offer.keywords.forEach { kw -> Etiqueta(kw, jc.acentoCian) }
            }

            Spacer(Modifier.height(12.dp))

            // --- Acciones -----------------------------------------------
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 13.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val fondoBoton = remember(jc.dark, jc.acento) {
                    if (jc.dark) Brush.horizontalGradient(GradientPalette.HeroButton)
                    else Brush.horizontalGradient(listOf(jc.acento, Color(0xFF1D4ED8)))
                }
                Surface(
                    onClick = onVerOferta,
                    modifier = Modifier.weight(1f).height(38.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Unspecified
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(fondoBoton, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.OpenInNew,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(
                                "Ver oferta",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }

                IconoAccion(
                    icono = Icons.Outlined.Check,
                    descripcion = if (postulada) "Postulado" else "Marcar como postulada",
                    tinte = if (postulada) Color.White else jc.textoSuave,
                    fondo = if (postulada) jc.acento else jc.tarjetaAlt,
                    borde = if (postulada) jc.acento else jc.borde,
                    onClick = onTogglePostulada
                )
                IconoAccion(
                    icono = Icons.Outlined.Archive,
                    descripcion = "Descartar",
                    tinte = jc.textoSuave,
                    fondo = jc.tarjetaAlt,
                    borde = jc.borde,
                    onClick = onDescartar
                )
            }
        }
    }
}

/**
 * Disco del % de match. En oscuro el relleno es un velo del color y el numero
 * va en la tinta viva; en claro el relleno es solido.
 */
@Composable
fun DiscoMatch(score: Int, tamano: Int = 50) {
    val jc = LocalJobColors.current
    // Un solo remember para los cuatro valores: cuatro bloques separados eran
    // cuatro slots del composer por tarjeta sin ninguna ganancia.
    val paleta = remember(score, jc.dark) {
        val aro = jc.match(score)
        DiscoPaleta(
            fondo = jc.matchFondo(score),
            tinta = jc.matchTinta(score),
            aro = if (jc.dark && score >= 75) {
                Brush.linearGradient(
                    listOf(Color(0xFF8B5CF6), Color(0xFFEC4899), Color(0xFFF97316))
                )
            } else {
                SolidColor(aro.copy(alpha = 0.5f))
            }
        )
    }

    Box(
        modifier = Modifier
            .size(tamano.dp)
            .background(paleta.fondo, CircleShape)
            .border(BorderStroke(1.5.dp, paleta.aro), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = score.toString(),
                fontFamily = NumberFont,
                fontSize = (tamano / 2.7).sp,
                fontWeight = FontWeight.ExtraBold,
                color = paleta.tinta
            )
            Text(
                text = "%",
                fontSize = (tamano / 6).sp,
                fontWeight = FontWeight.Bold,
                color = paleta.tinta.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 3.dp, start = 1.dp)
            )
        }
    }
}

private data class DiscoPaleta(
    val fondo: Color,
    val tinta: Color,
    val aro: Brush
)

@Composable
fun Etiqueta(texto: String, color: Color, fuerte: Boolean = false) {
    val jc = LocalJobColors.current
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = if (fuerte) color else jc.velo(color),
        border = if (fuerte) null else BorderStroke(1.dp, jc.contorno(color))
    ) {
        Text(
            text = texto,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = if (fuerte) MaterialTheme.colorScheme.onPrimary else color,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun IconoAccion(
    icono: androidx.compose.ui.graphics.vector.ImageVector,
    descripcion: String,
    tinte: Color,
    fondo: Color,
    borde: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(38.dp),
        shape = RoundedCornerShape(12.dp),
        color = fondo,
        border = BorderStroke(1.dp, borde)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icono, contentDescription = descripcion, tint = tinte, modifier = Modifier.size(17.dp))
        }
    }
}

/** "ANALISTA DE DESARROLLO" -> "Analista de Desarrollo". Deja siglas cortas intactas. */
internal fun normalizarMayusculas(s: String): String {
    val letras = s.count { it.isLetter() }
    if (letras == 0) return s
    val mayus = s.count { it.isUpperCase() }
    if (mayus.toFloat() / letras < 0.7f) return s   // ya viene con formato normal

    val menores = setOf("de", "del", "la", "las", "el", "los", "y", "en", "para", "con", "a", "o")
    return s.lowercase().split(" ").mapIndexed { i, p ->
        when {
            p.isBlank() -> p
            p.length <= 3 && p.all { it.isLetter() } && i > 0 && p in menores -> p
            else -> p.replaceFirstChar { it.uppercase() }
        }
    }.joinToString(" ")
}
