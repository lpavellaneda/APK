package com.jobai.hunter.domain

import com.jobai.hunter.data.model.JobOffer
import java.util.regex.Pattern

/**
 * Motor de enriquecimiento de ofertas laborales (Port de extractores.py + enriquecer_ofertas.py).
 * Extrae campos derivados de salario, régimen de trabajo (14x7, campamento), seniority,
 * nivel educativo, nivel de inglés, exclusividad de carrera y beneficios.
 */
object OfferEnricher {

    // ---------------------------------------------------------------------------
    // 1. Salario (rescatado del texto)
    // ---------------------------------------------------------------------------
    private val RE_NUM = Pattern.compile("\\d{1,3}(?:[.,]\\d{3})+(?:[.,]\\d{2})?|\\d{3,6}(?:[.,]\\d{2})?")
    private val RE_SOLES = Regex("(?:s/\\.?|soles|pen)\\s*[:.]?\\s*(\\d{1,3}(?:[.,]\\d{3})+(?:[.,]\\d{2})?|\\d{3,6}(?:[.,]\\d{2})?)", RegexOption.IGNORE_CASE)
    private val RE_DOLARES = Regex("(?:us\\$|usd|\\$)\\s*[:.]?\\s*(\\d{1,3}(?:[.,]\\d{3})+(?:[.,]\\d{2})?|\\d{3,6}(?:[.,]\\d{2})?)", RegexOption.IGNORE_CASE)

    private val PALABRAS_SALARIO = listOf(
        "sueldo", "salario", "remuneracion", "subvencion", "basico", "haber",
        "ingreso mensual", "renta mensual", "pago mensual", "banda salarial",
        "rango salarial", "planilla de", "honorarios", "retribucion"
    )

    fun extraerSalario(descripcion: String): Triple<Double?, Double?, String?> {
        val texto = Texto.norm(descripcion)
        if (texto.isBlank()) return Triple(null, null, null)

        val tieneContextoSalario = PALABRAS_SALARIO.any { texto.contains(it) }
        if (!tieneContextoSalario) return Triple(null, null, null)

        val montosSoles = RE_SOLES.findAll(texto).mapNotNull { m ->
            limpiarNumero(m.groupValues[1])
        }.filter { it in 500.0..60000.0 }.toList()

        if (montosSoles.isNotEmpty()) {
            return Triple(montosSoles.minOrNull(), montosSoles.maxOrNull(), "PEN")
        }

        val montosDolares = RE_DOLARES.findAll(texto).mapNotNull { m ->
            limpiarNumero(m.groupValues[1])
        }.filter { it in 400.0..20000.0 }.toList()

        if (montosDolares.isNotEmpty()) {
            return Triple(montosDolares.minOrNull(), montosDolares.maxOrNull(), "USD")
        }

        return Triple(null, null, null)
    }

    private fun limpiarNumero(s: String): Double? {
        val limpio = s.replace(",", "").replace(".", "")
        return limpio.toDoubleOrNull() ?: s.replace(",", ".").toDoubleOrNull()
    }

    // ---------------------------------------------------------------------------
    // 2. Seniority (del título)
    // ---------------------------------------------------------------------------
    /**
     * Habia dos tablas de seniority distintas (esta y la de PerfilExtractor) y
     * no coincidian: esta clasificaba "Analista Semi Senior" como Senior porque
     * evaluaba "senior" sin mirar el "semi", y hacia contains("ceo") sin
     * limites de palabra. El filtro del ScraperEngine usaba una y la tarjeta la
     * otra. Ahora hay una sola fuente de verdad.
     */
    fun detectarSeniority(titulo: String): String = PerfilExtractor.detectarSeniority(titulo)

    // ---------------------------------------------------------------------------
    // 3. Nivel educativo
    // ---------------------------------------------------------------------------
    fun detectarNivelEducativo(descripcion: String): String {
        val d = Texto.norm(descripcion)
        return when {
            d.contains("maestria") || d.contains("mba") || d.contains("doctorado") || d.contains("postgrado") || d.contains("posgrado") -> "Postgrado"
            d.contains("colegiado") || d.contains("titulado") || d.contains("habilitado") || d.contains("licenciado en") -> "Titulado/Colegiado"
            d.contains("bachiller") -> "Bachiller"
            d.contains("universitari") || d.contains("egresado") || d.contains("carrera profesional") -> "Universitario / Egresado"
            d.contains("tecnico") || d.contains("instituto") || d.contains("senati") || d.contains("tecsup") || d.contains("cibertec") -> "Técnico"
            d.contains("secundaria completa") || d.contains("quinto de secundaria") -> "Secundaria"
            else -> "No especificado"
        }
    }

    // ---------------------------------------------------------------------------
    // 4. Inglés
    // ---------------------------------------------------------------------------
    fun detectarIngles(descripcion: String): Pair<Boolean, String?> {
        val d = Texto.norm(descripcion)
        return when {
            d.contains("ingles avanzado") || d.contains("ingles fluido") || d.contains("advanced english") || d.contains("bilingue") -> Pair(true, "Avanzado")
            d.contains("ingles intermedio") || d.contains("ingles b1") || d.contains("ingles b2") -> Pair(true, "Intermedio")
            d.contains("ingles basico") || d.contains("ingles a1") || d.contains("ingles a2") -> Pair(true, "Básico")
            Regex("\\bingles\\b").containsMatchIn(d) -> Pair(true, "Mencionado sin nivel")
            else -> Pair(false, null)
        }
    }

    // ---------------------------------------------------------------------------
    // 5. Régimen de trabajo (14x7, 20x10...) y Campamento
    // ---------------------------------------------------------------------------
    private val RE_REGIMEN = Regex("(?<![\\d,.])(\\d{1,2})\\s*[x×]\\s*(\\d{1,2})(?![\\d])")
    private val SENALES_CAMPAMENTO = listOf(
        "campamento", "unidad minera", "alojamiento cubierto", "hospedaje cubierto",
        "msnm", "internado", "alimentacion y alojamiento", "proyecto minero", "tajo abierto"
    )
    private val RUIDO_REGIMEN = listOf("camioneta", "4x4", "pickup", "metos", "tamano", "24x7")

    fun extraerRegimen(descripcion: String): Pair<String?, Boolean> {
        val d = Texto.norm(descripcion)
        if (d.isBlank()) return Pair(null, false)

        var esCampamento = SENALES_CAMPAMENTO.any { d.contains(it) }

        val matches = RE_REGIMEN.findAll(d)
        for (m in matches) {
            val trabajo = m.groupValues[1].toIntOrNull() ?: 0
            val descanso = m.groupValues[2].toIntOrNull() ?: 0
            if (trabajo == 0 || (trabajo == 24 && descanso == 7)) continue

            val idx = m.range.first
            val entorno = d.substring((idx - 50).coerceAtLeast(0), (idx + 50).coerceAtMost(d.length))
            if (RUIDO_REGIMEN.any { entorno.contains(it) }) continue

            val regimenStr = "${trabajo}x${descanso}"
            if (trabajo >= 7 || (trabajo + descanso) >= 10) {
                esCampamento = true
            }
            return Pair(regimenStr, esCampamento)
        }

        return Pair(null, esCampamento)
    }

    // ---------------------------------------------------------------------------
    // 6. Exclusividad de Ingeniería Industrial
    // ---------------------------------------------------------------------------
    private val RE_AFINES = Regex("\\b(y/?o\\s*afines|carreras\\s+afines|o\\s+afines|y\\s+afines)\\b", RegexOption.IGNORE_CASE)
    private val OTRAS_CARRERAS = listOf(
        "administracion", "economia", "contabilidad", "ing sistemas", "ingenieria de sistemas",
        "ingenieria mecanica", "ingenieria electrica", "ingenieria civil", "ingenieria quimica",
        "marketing", "psicologia", "derecho"
    )

    fun analizarExclusividadIndustrial(titulo: String, descripcion: String): Boolean {
        val texto = Texto.norm("$titulo $descripcion")
        val hayAfines = RE_AFINES.containsMatchIn(texto)
        val hayOtras = OTRAS_CARRERAS.any { texto.contains(it) }
        return !hayAfines && !hayOtras
    }

    // ---------------------------------------------------------------------------
    // 7. Beneficios
    // ---------------------------------------------------------------------------
    fun detectarBeneficios(descripcion: String): List<String> {
        val d = Texto.norm(descripcion)
        val lista = mutableListOf<String>()
        if (d.contains("planilla") || d.contains("beneficios de ley")) lista.add("Planilla")
        if (d.contains("eps") || d.contains("seguro medico") || d.contains("seguro de salud")) lista.add("EPS / Seguro")
        if (d.contains("bono") || d.contains("comision") || d.contains("incentivo")) lista.add("Bonos / Comisiones")
        if (d.contains("capacitacion") || d.contains("linea de carrera") || d.contains("convenio educativo")) lista.add("Capacitaciones")
        if (d.contains("alimentacion") || d.contains("almuerzo") || d.contains("vale de alimento") || d.contains("comedor")) lista.add("Alimentación")
        if (d.contains("movilidad") || d.contains("transporte cubierto") || d.contains("bus de personal")) lista.add("Movilidad")
        return lista
    }

    // ---------------------------------------------------------------------------
    // Función Principal de Enriquecimiento
    // ---------------------------------------------------------------------------
    fun enriquecer(offer: JobOffer): JobOffer {
        val (sMin, sMax, sMoneda) = extraerSalario(offer.descripcion)
        val seniorityVal = detectarSeniority(offer.puesto)
        val nivelEduVal = detectarNivelEducativo(offer.descripcion)
        val (reqIngles, nivelInglesVal) = detectarIngles(offer.descripcion)
        val (regimenVal, esCampamento) = extraerRegimen(offer.descripcion)
        val exclusivaInd = analizarExclusividadIndustrial(offer.puesto, offer.descripcion)
        val beneficiosVal = detectarBeneficios(offer.descripcion)

        // Extraer requisitos en texto para mantener compatibilidad UI
        val reqsUI = RequirementExtractor.top(offer.puesto, offer.descripcion, 6).joinToString("; ") { it.etiqueta }

        return offer.copy(
            salarioMin = sMin,
            salarioMax = sMax,
            salarioMoneda = sMoneda,
            seniority = seniorityVal,
            nivelEducativo = nivelEduVal,
            requiereIngles = reqIngles,
            nivelIngles = nivelInglesVal,
            regimenTrabajo = regimenVal,
            trabajoEnCampamento = esCampamento,
            ingIndustrialExclusiva = exclusivaInd,
            beneficios = beneficiosVal,
            requisitos = if (reqsUI.isNotBlank()) reqsUI else offer.requisitos
        )
    }
}
