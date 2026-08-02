package com.jobai.hunter.domain

/**
 * Reemplaza al calculo de distancias. No hay geocodificacion ni haversine:
 * se usa tal cual el texto de ubicacion que publica el portal, que es lo unico
 * que se sabe con certeza.
 *
 * Port de separar_ubicacion() / es_lima_metropolitana() de extractores.py.
 */
enum class Zona(val etiqueta: String) {
    LIMA_METROPOLITANA("Lima Metropolitana"),
    CALLAO("Callao"),
    LIMA_PROVINCIAS("Provincias de Lima"),
    OTRO_DEPARTAMENTO("Otros departamentos"),
    DESCONOCIDA("Sin ubicación")
}

object Ubicaciones {

    /** Los 24 departamentos + Callao. Valida el lado derecho de la coma. */
    private val DEPARTAMENTOS = setOf(
        "amazonas", "ancash", "apurimac", "arequipa", "ayacucho", "cajamarca",
        "callao", "cusco", "huancavelica", "huanuco", "ica", "junin",
        "la libertad", "lambayeque", "lima", "loreto", "madre de dios",
        "moquegua", "pasco", "piura", "puno", "san martin", "tacna",
        "tumbes", "ucayali"
    )

    /**
     * Provincias del departamento de Lima que NO son Lima Metropolitana.
     * Sin esta lista, "Huaral, Lima" se contaria como Lima Metropolitana solo
     * porque el departamento dice Lima.
     */
    private val PROVINCIAS_LIMA = setOf(
        "barranca", "cajatambo", "canta", "canete", "huaral", "huarochiri",
        "huaura", "oyon", "yauyos", "san vicente de canete", "huacho",
        "chancay", "supe", "paramonga", "matucana", "imperial", "mala",
        "chilca", "asia", "san bartolome", "santa eulalia", "pativilca"
    )

    /** Ruido que agregan los portales: "Peru", "Lima Province", "y alrededores". */
    private val RUIDO = listOf(
        "peru", "provincia de", "province", "region", "departamento de",
        "y alrededores", "and surrounding area", "metropolitan area", "area metropolitana"
    )

    /**
     * "San Vicente de Cañete, Lima" -> ("san vicente de canete", "lima")
     * "Lima, Lima Province, Peru"   -> ("lima", "lima")
     */
    fun separar(ubicacion: String?): Pair<String?, String?> {
        if (ubicacion.isNullOrBlank()) return null to null
        val partes = ubicacion.split(",")
            .map { limpiar(it) }
            .filter { it.isNotBlank() }
        if (partes.isEmpty()) return null to null

        // El departamento es la ultima parte que este en la lista oficial.
        val depto = partes.lastOrNull { it in DEPARTAMENTOS }
        val resto = partes.filter { it != depto }
        val distrito = resto.firstOrNull() ?: partes.first()

        return (if (distrito == depto) null else distrito) to depto
    }

    private fun limpiar(s: String): String {
        var t = Texto.norm(s)
        for (r in RUIDO) t = t.replace(r, " ")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    fun clasificar(ubicacion: String?): Zona {
        val (distrito, depto) = separar(ubicacion)
        if (distrito == null && depto == null) return Zona.DESCONOCIDA

        if (depto == "callao" || distrito == "callao") return Zona.CALLAO
        // Distritos del Callao que a veces vienen sin el departamento.
        if (distrito != null && distrito in setOf("bellavista", "la perla", "la punta", "ventanilla", "mi peru", "carmen de la legua")) {
            return Zona.CALLAO
        }

        if (depto == "lima" || depto == null) {
            if (distrito != null) {
                if (PROVINCIAS_LIMA.any { distrito.contains(it) }) return Zona.LIMA_PROVINCIAS
                if (LimaGeo.centroDeDistrito(distrito) != null) return Zona.LIMA_METROPOLITANA
            }
            // "Lima" a secas: el portal no dijo el distrito.
            return if (depto == "lima") Zona.LIMA_METROPOLITANA else Zona.DESCONOCIDA
        }

        return if (depto in DEPARTAMENTOS) Zona.OTRO_DEPARTAMENTO else Zona.DESCONOCIDA
    }

    /**
     * Nombre presentable del distrito o ciudad tal como lo dio el portal.
     * Se conserva la capitalizacion original en vez de normalizarla.
     */
    fun distritoVisible(ubicacion: String?): String {
        if (ubicacion.isNullOrBlank()) return "Sin especificar"
        val crudas = ubicacion.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val norm = crudas.map { limpiar(it) }
        // Primera parte que no sea un departamento ni quede vacia tras limpiar ruido.
        for (i in crudas.indices) {
            if (norm[i].isNotBlank() && norm[i] !in DEPARTAMENTOS) return crudas[i]
        }
        return crudas.firstOrNull() ?: "Sin especificar"
    }

    /** Departamento presentable, para las ofertas de provincia. */
    fun departamentoVisible(ubicacion: String?): String? {
        val (_, depto) = separar(ubicacion)
        return depto?.split(" ")?.joinToString(" ") { p ->
            p.replaceFirstChar { it.uppercase() }
        }
    }
}
