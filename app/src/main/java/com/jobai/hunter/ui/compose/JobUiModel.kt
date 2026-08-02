package com.jobai.hunter.ui.compose

import androidx.compose.runtime.Immutable
import com.jobai.hunter.data.local.JobEntity
import com.jobai.hunter.data.local.TipoJornada
import com.jobai.hunter.data.local.etiquetaExperiencia
import com.jobai.hunter.data.local.etiquetaJornada
import com.jobai.hunter.data.local.salarioTexto
import com.jobai.hunter.data.local.tipoJornada
import com.jobai.hunter.data.local.ubicacionCorta
import com.jobai.hunter.data.local.zonaEnum
import com.jobai.hunter.domain.RequirementExtractor
import com.jobai.hunter.domain.Texto
import com.jobai.hunter.domain.Zona

/**
 * Lo que la tarjeta necesita, ya calculado.
 *
 * La lista laggeaba porque cada OfferCard resolvia en el hilo de UI, en su
 * primera composicion, cosas que no dependen del scroll: normalizacion de
 * mayusculas, formato de salario, etiqueta de jornada y —lo caro de verdad—
 * RequirementExtractor sobre una descripcion de varios miles de caracteres
 * (normalizar HTML, quitar emojis y correr ~90 regex). Con 30-40 tarjetas
 * entrando y saliendo por segundo eso son cientos de milisegundos por segundo
 * robados al frame.
 *
 * Aca todo eso se hace una sola vez, fuera del hilo principal, y la vista solo
 * pinta strings. Se conserva `offer` para el detalle y la exportacion.
 */
@Immutable
data class JobUiModel(
    val offer: JobEntity,
    // --- identidad / acciones ---
    val url: String,
    val estado: String,
    val postulada: Boolean,
    // --- cabecera ---
    val portal: String,
    val portalUpper: String,
    val nuevo: Boolean,
    val esAtipica: Boolean,
    // --- cuerpo ---
    val titulo: String,
    val empresa: String,
    val ubicacion: String,
    val salarioTexto: String?,
    val matchScore: Int,
    // --- etiquetas ---
    val etiquetaJornada: String?,
    val etiquetaExperiencia: String,
    val modalidad: String,
    val modalidadVisible: String,
    val keywords: List<String>,
    // --- campos de filtro y orden (evitan tocar JobEntity al filtrar) ---
    val modalidadNorm: String,
    val zona: Zona,
    val jornada: TipoJornada,
    val fechaEpoch: Long,
    val mesesExperiencia: Int?
)

/** Separadores con los que OfferEnricher serializa los requisitos. */
private val SEPARADOR_REQUISITOS = Regex("[;|]")

/**
 * Los requisitos ya se calcularon en el pipeline y viven en la fila de Room.
 * Volver a extraerlos en la tarjeta era trabajo duplicado; el extractor solo
 * corre si la fila es antigua y viene vacia.
 */
private fun JobEntity.keywordsDeTarjeta(): List<String> {
    if (requisitos.isNotBlank()) {
        return requisitos.split(SEPARADOR_REQUISITOS)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(4)
    }
    return RequirementExtractor.top(puesto, descripcion, n = 4).map { it.etiqueta }
}

/** Conversion pesada: llamarla siempre desde Dispatchers.Default. */
fun JobEntity.toUiModel(): JobUiModel {
    val jornadaTipo = tipoJornada
    return JobUiModel(
        offer = this,
        url = url,
        estado = estado,
        postulada = estado == "postulada",
        portal = portal,
        portalUpper = portal.uppercase(),
        nuevo = nuevo,
        esAtipica = jornadaTipo == TipoJornada.ATIPICO,
        titulo = normalizarMayusculas(puesto),
        empresa = normalizarMayusculas(empresa),
        ubicacion = ubicacionCorta,
        salarioTexto = salarioTexto,
        matchScore = matchScore,
        etiquetaJornada = etiquetaJornada,
        etiquetaExperiencia = etiquetaExperiencia,
        modalidad = modalidad,
        modalidadVisible = if (modalidad == "Hibrido") "Híbrido" else modalidad,
        keywords = keywordsDeTarjeta(),
        modalidadNorm = Texto.norm(modalidad),
        zona = zonaEnum,
        jornada = jornadaTipo,
        fechaEpoch = fechaEpoch,
        mesesExperiencia = mesesExperiencia
    )
}
