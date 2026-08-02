package com.jobai.hunter.domain.matcher

import com.jobai.hunter.data.model.JobOffer
import com.jobai.hunter.domain.Texto
import com.jobai.hunter.domain.Zona

/**
 * Reemplaza a UltraMatcher.
 *
 * El anterior sumaba puntos sueltos por palabra clave y topaba en 100 con
 * facilidad: en la practica casi todo caia entre 75 y 100, o sea que el numero
 * no ordenaba nada. Este modelo cambia tres cosas:
 *
 *  1. BLOQUES CON TOPE. Encontrar cinco herramientas no vale mas que el tope
 *     del bloque de herramientas. Antes, una oferta que repetia "Excel, SQL,
 *     Power BI, SAP" ya llegaba a 50 sin decir nada del puesto.
 *  2. DOMINIO POR CARRIL. El CV tiene dos perfiles reales (Control de
 *     Proyectos/PMO y Supply Chain). Se puntua el carril que mejor calza y el
 *     segundo aporta solo un tercio: una oferta muy de PMO deberia ganarle a
 *     una que menciona un poco de todo.
 *  3. PENALIZACIONES. Ingles avanzado, colegiatura, provincia o campamento
 *     restan. Sin restas todo tiende hacia arriba y el ranking se aplana.
 *
 * El perfil esta en constantes editables: si cambia el CV, se edita aqui.
 */
object PerfilMatcher {

    data class Resultado(
        val score: Int,
        /** Por que puntuo asi. Se guarda y se muestra en la tarjeta. */
        val razones: List<String>
    )

    private class Regla(val puntos: Int, val etiqueta: String, vararg val claves: String)

    private fun aplicar(texto: String, reglas: List<Regla>, tope: Int): Pair<Int, List<String>> {
        var suma = 0
        val hits = mutableListOf<Pair<Int, String>>()
        for (r in reglas) {
            if (r.claves.any { texto.contains(it) }) {
                suma += r.puntos
                hits.add(r.puntos to r.etiqueta)
            }
        }
        // Las razones se ordenan por peso para que la tarjeta muestre lo que mas peso.
        return minOf(suma, tope) to hits.sortedByDescending { it.first }.map { it.second }
    }

    // =====================================================================
    // 1. Herramientas (tope 28)
    // =====================================================================
    private val HERRAMIENTAS = listOf(
        Regla(10, "Power BI", "power bi", "powerbi", " dax", "power query", "tablero de control", "dashboard"),
        Regla(7, "SQL", "sql", "consultas", "base de datos"),
        Regla(6, "SAP", "sap", "erp"),
        Regla(5, "Python", "python", "automatizacion de reportes"),
        Regla(5, "Excel avanzado", "excel avanzado", "tablas dinamicas", "macros", "vba", "buscarv"),
        Regla(4, "MS Project", "ms project", "msproject", "microsoft project", "cronograma"),
        Regla(3, "Power Platform", "power apps", "power automate")
    )

    // =====================================================================
    // 2. Dominio funcional — tres carriles
    // =====================================================================
    private val CARRIL_PMO = listOf(
        Regla(12, "Presupuestos CAPEX/OPEX", "presupuesto", "capex", "opex", "presupuestal"),
        Regla(8, "Forecast y desviaciones", "forecast", "desviacion", "proyeccion financiera"),
        Regla(8, "Control de gestión", "control de gestion", "controller", "control interno", "planeamiento financiero"),
        Regla(6, "Valor ganado / EVM", "valor ganado", "evm", "curva s", "avance de obra", "pmbook", "pmbok", "pmo"),
        Regla(5, "Contratos y valorizaciones", "valorizacion", "contrato", "licitacion"),
        Regla(5, "Costos y márgenes", "costos", "margen", "rentabilidad", "centro de costo")
    )

    private val CARRIL_SUPPLY = listOf(
        Regla(10, "Inventarios", "inventario", "stock", "conteo ciclico", "exactitud de registro", "eri", "abc"),
        Regla(8, "Almacén", "almacen", "warehouse", "eru", "ubicaciones", "rotacion"),
        Regla(8, "Planeamiento de demanda", "pcp", "demanda", "planeamiento de la produccion", "s&op", "sop"),
        Regla(8, "Supply chain", "supply chain", "cadena de suministro", "logistica"),
        Regla(6, "Despacho y transporte", "despacho", "distribucion", "transporte", "trazabilidad"),
        Regla(5, "Compras", "compras", "abastecimiento", "procura", "proveedores")
    )

    private val CARRIL_PROCESOS = listOf(
        Regla(8, "Mejora continua", "mejora continua", "lean", "kaizen", "six sigma", "vsm"),
        Regla(6, "Mapeo de procesos", "mapeo de procesos", "levantamiento de procesos", "flujograma", "estandarizacion"),
        Regla(5, "KPIs", "kpi", "indicadores", "tablero"),
        Regla(5, "Automatización", "automatizacion", "digitalizacion", "rpa")
    )

    private const val TOPE_DOMINIO = 34

    // =====================================================================
    // 3. Penalizaciones
    // =====================================================================
    private val RE_INGLES_ALTO = Regex(
        "ingles\\s+(?:avanzado|fluido|c1|c2|bilingue)|nivel\\s+avanzado\\s+de\\s+ingles|fluent english|advanced english"
    )
    private val RE_COLEGIATURA = Regex(
        "colegiatura|colegiado|habilitado por el|\\bcip\\b|registro profesional|titulado(?!\\s*y/?o)"
    )
    private val RE_LICENCIA = Regex("licencia de conducir|brevete|\\ba-?iib\\b|movilidad propia")

    /**
     * @param offer debe venir YA enriquecido: usa zona, regimen, seniority,
     *              nivelIngles y expTipo, que los fija OfferEnricher.
     */
    fun evaluar(offer: JobOffer): Resultado {
        val texto = Texto.norm(offer.puesto + " " + offer.descripcion + " " + offer.requisitos)
        val razones = mutableListOf<String>()

        // --- Herramientas ---
        val (pHerr, rHerr) = aplicar(texto, HERRAMIENTAS, 28)
        razones += rHerr.take(2)

        // --- Dominio: el mejor carril manda, el segundo aporta un tercio ---
        val carriles = listOf(
            "Control de proyectos" to aplicar(texto, CARRIL_PMO, TOPE_DOMINIO),
            "Supply chain" to aplicar(texto, CARRIL_SUPPLY, TOPE_DOMINIO),
            "Mejora de procesos" to aplicar(texto, CARRIL_PROCESOS, 22)
        ).sortedByDescending { it.second.first }

        val mejor = carriles[0]
        val segundo = carriles[1]
        val pDominio = minOf(mejor.second.first + (segundo.second.first * 0.33).toInt(), TOPE_DOMINIO)
        if (mejor.second.first > 0) {
            razones += mejor.first
            razones += mejor.second.second.take(2)
        }

        // --- Encaje de nivel y experiencia (tope 22) ---
        var pEncaje = 0
        pEncaje += when (offer.seniority) {
            "Junior", "Semi Senior" -> 8
            "Operativo / Sin especificar" -> 6
            "Practicante" -> 3
            else -> 0
        }
        pEncaje += when {
            offer.expTipo == "NO_REQUERIDA" -> 10
            offer.mesesExperiencia == null -> 4          // no se pudo determinar: no premiar
            offer.mesesExperiencia <= 12 -> 10
            offer.mesesExperiencia <= 24 -> 5
            else -> 0
        }
        if (offer.mesesExperiencia != null && offer.mesesExperiencia <= 24) {
            razones += "Pide ≤ 2 años"
        }
        pEncaje += when (offer.modalidad) {
            "Remoto", "Hibrido" -> 4
            else -> 2
        }
        pEncaje = minOf(pEncaje, 22)

        // --- Carrera (tope 12) ---
        var pCarrera = 0
        if (Regex("ingenier[a-z]*\\s+industrial|ing[.,]?\\s*industrial").containsMatchIn(texto)) {
            pCarrera += 8
            razones += "Pide Ing. Industrial"
        }
        if (offer.ingIndustrialExclusiva) {
            pCarrera += 4
            razones += "Exclusiva Ing. Industrial"
        }

        // --- Penalizaciones ---
        var castigo = 0
        val alertas = mutableListOf<String>()

        val inglesAlto = offer.nivelIngles?.contains("Avanzado", true) == true ||
            RE_INGLES_ALTO.containsMatchIn(texto)
        if (inglesAlto) { castigo += 10; alertas += "Inglés avanzado" }

        // Bachiller en tramite: colegiatura o titulo exigido es un bloqueo real.
        if (RE_COLEGIATURA.containsMatchIn(texto)) { castigo += 8; alertas += "Pide colegiatura/título" }

        if (RE_LICENCIA.containsMatchIn(texto)) { castigo += 4; alertas += "Pide brevete" }

        val zona = runCatching { Zona.valueOf(offer.zona) }.getOrDefault(Zona.DESCONOCIDA)
        if (zona == Zona.OTRO_DEPARTAMENTO) { castigo += 8; alertas += "Fuera de Lima" }
        else if (zona == Zona.LIMA_PROVINCIAS) { castigo += 4; alertas += "Provincia de Lima" }

        if (offer.trabajoEnCampamento || offer.regimenTrabajo != null) {
            castigo += 6; alertas += "Régimen atípico"
        }

        val bruto = pHerr + pDominio + pEncaje + pCarrera - castigo
        val score = bruto.coerceIn(0, 100)

        // Las alertas van al final: importan mas para decidir que los aciertos.
        val finales = (razones.distinct().take(4) + alertas.map { "⚠ $it" }).take(6)
        return Resultado(score, finales)
    }
}
