package com.jobai.hunter.domain

/**
 * Extraccion de experiencia y seniority.
 *
 * Correcciones sobre la version anterior:
 *
 *  1. RUIDO DE PORTAL. LinkedIn cierra todos los avisos con el bloque
 *     "Show more Show less / Nivel de antiguedad: Sin experiencia / Tipo de
 *     empleo ..." y Computrabajo con "Ofertas similares". Ese pie hacia que
 *     RE_SIN_EXPERIENCIA disparara y el aviso quedara como "Sin exp." aunque
 *     en Requisitos pidiera "1 anio de experiencia". Ahora el texto se recorta
 *     antes de analizarlo.
 *
 *  2. "SIN EXPERIENCIA" YA NO CORTOCIRCUITA. Antes se devolvia NO_REQUERIDA
 *     apenas aparecia la frase, sin mirar si el aviso cuantificaba. Ahora
 *     primero se buscan los anios/meses y solo si no hay ninguno se evalua la
 *     ausencia de experiencia.
 *
 *  3. CONTEXTO_EMPRESA tenia "de operacion", que hacia match con
 *     "analista de operaciones" / "planner de operaciones" y anulaba el
 *     requisito real. Los marcadores de empresa ahora son regex acotadas.
 *
 *  4. RUIDO DE PLAZOS. "planilla luego de 3 a 6 meses de periodo de prueba",
 *     "contrato por 4 meses" ya no se leen como experiencia exigida.
 *
 *  5. Decimales ("1.5 anios"), semestres y "02 anios" tras punto pegado
 *     ("excel intermedio.2 a 3 anios de experiencia") se leen bien.
 *
 *  6. detectarSeniority ya no clasifica "Analista Semi Senior" como Senior.
 */
object PerfilExtractor {

    // =====================================================================
    // Experiencia
    // =====================================================================

    enum class TipoExperiencia {
        /** "2 anios en posiciones similares" */
        CUANTIFICADA,
        /** "experiencia comprobable en el area" */
        REQUERIDA_SIN_CUANTIFICAR,
        /** "con o sin experiencia" */
        NO_REQUERIDA,
        /** el aviso no toca el tema */
        NO_MENCIONADA
    }

    data class Experiencia(
        val mesesMin: Int?,
        val mesesMax: Int?,
        val evidencia: String?,
        val tipo: TipoExperiencia
    ) {
        val aniosMin: Int? get() = mesesMin?.let { it / 12 }

        /** Texto para la tarjeta. */
        val etiqueta: String
            get() = when {
                tipo == TipoExperiencia.NO_REQUERIDA -> "Sin exp."
                tipo == TipoExperiencia.REQUERIDA_SIN_CUANTIFICAR -> "Exp. sin precisar"
                tipo == TipoExperiencia.NO_MENCIONADA -> "Exp. no indicada"
                mesesMin == null -> "Exp. no indicada"
                mesesMin == 0 -> "Sin exp."
                mesesMin < 12 -> "$mesesMin meses"
                mesesMin % 12 == 0 -> "${mesesMin / 12}+ años"
                else -> "${mesesMin / 12}.${(mesesMin % 12) * 10 / 12}+ años"
            }
    }

    private val NUMEROS_TEXTO = mapOf(
        "un" to 1, "una" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5,
        "seis" to 6, "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10,
        "once" to 11, "doce" to 12, "quince" to 15
    )

    private val ALT_NUM = NUMEROS_TEXTO.keys.joinToString("|")

    /** El aviso ya viene sin tildes: "años" -> "anos" -> se unifica a "anios". */
    private const val UNIDAD = "(anios?|anos?|semestres?|meses|mes)\\b"

    /**
     * Los lookbehind evitan dos falsos positivos distintos:
     *   (?<!\d)        -> no partir "120" en "12" + "0"
     *   (?<![\d][.,])  -> no tomar el "5" de "1.5" como una cantidad propia,
     *                     pero SI el "2" de "excel intermedio.2 a 3 anios".
     */
    private val CANTIDAD = "(?<!\\d)(?<![\\d][.,])(\\d{1,2}(?:[.,]5)?|$ALT_NUM)\\b"
    private val NUM_TIEMPO = "$CANTIDAD\\s*(?:\\+|o\\s*mas|a\\s*mas|en adelante)?\\s*(?:de\\s+)?$UNIDAD"

    /** En los cuatro patrones el grupo 1 es la cantidad y el grupo 2 la unidad. */
    private val PATRONES = listOf(
        // "experiencia minima de 3 meses" / "experiencia: 1 anio"
        Regex("experiencia\\b[^.;]{0,45}?$NUM_TIEMPO"),
        // "3 anios de experiencia"
        Regex("$NUM_TIEMPO[^.;]{0,25}?experiencia"),
        // "02 anios en posiciones similares" — sin la palabra experiencia
        Regex(
            NUM_TIEMPO + "\\s*(?:\\(?[a-z]*\\)?\\s*)?" +
                "(?:en|como|desempenando|realizando|liderando|ejerciendo|manejando)\\b[^.;]{0,45}?" +
                "(?:similar|afin|relacionad|el puesto|el cargo|el rubro|el sector|roles|" +
                "posicion|puesto|cargo|labores|funciones|areas|ventas|atencion|supervision)"
        ),
        // "minimo 3 anios" / "al menos 2 anios"
        Regex("(?:minim[oa]|al menos|no menor a|mayor a|por lo menos|contar con|acreditar)\\s*(?:de\\s*)?$NUM_TIEMPO")
    )

    private val RE_RANGO = Regex(
        "(?<!\\d)(?<![\\d][.,])(\\d{1,2}|$ALT_NUM)\\s*(?:a|y|hasta|-|/)\\s*(\\d{1,2}|$ALT_NUM)\\s*(?:de\\s+)?$UNIDAD"
    )
    private val CONTEXTO_RANGO =
        listOf("experiencia", "minim", "al menos", "entre", "requier", "contar con", "acredit")

    /**
     * Pie de pagina de los portales. Todo lo que viene despues no describe el
     * puesto: es navegacion, avisos relacionados y metadatos de LinkedIn.
     * El umbral de 200 caracteres evita cortar un aviso que sea puro pie.
     */
    private val CORTES = listOf(
        "show more show less", "nivel de antiguedad", "las recomendaciones duplican",
        "ofertas similares", "empleos similares", "denunciar empleo",
        "avisame con ofertas similares", "no te pierdas ninguna oportunidad",
        "inicia sesion para", "postularme postulado", "gracias por ayudarnos a mejorar",
        "ver oferta completa", "otras personas tambien vieron", "se el primero en inscribirte"
    )

    private fun recortarRuidoDePortal(t: String): String {
        var fin = t.length
        for (c in CORTES) {
            val i = t.indexOf(c)
            if (i in 201 until fin) fin = i
        }
        return t.substring(0, fin)
    }

    private val RE_SIN_EXPERIENCIA = Regex(
        "\\b(?:con\\s+o\\s+)?sin\\s+experiencia(?:\\s+(?:previa|laboral|requerida))?\\b" +
            "|\\bno\\s+(?:se\\s+)?(?:requiere|requerimos|necesitas?|es\\s+necesaria|solicita)\\s+experiencia\\b" +
            "|\\bexperiencia\\s+no\\s+(?:indispensable|requerida|necesaria|excluyente)\\b" +
            "|\\bno\\s+indispensable\\s+experiencia\\b" +
            "|\\bexperiencia\\s+de\\s+0+\\s*anios?\\b|\\b0+\\s*anios?\\s+de\\s+experiencia\\b" +
            "|\\bprimer\\s+empleo\\b"
    )

    /** "Sin experiencia" que no habla del requisito del aviso. */
    private val RUIDO_SIN_EXP = listOf(
        "nivel de antiguedad", "tipo de empleo", "otras ofertas", "ofertas similares",
        "aunque no tengas", "no importa si"
    )

    /** Anios que hablan de la empresa, no del postulante. */
    private val CONTEXTO_EMPRESA = listOf(
        Regex("\\btrayectoria\\b"),
        Regex("en el mercado"),
        Regex("de historia"),
        Regex("\\bfundad[oa]"),
        Regex("de operacion(?:es)?\\s+(?:continua|ininterrumpid|en el pais|en el peru)"),
        Regex("\\bbrindando\\b"),
        Regex("de presencia"),
        Regex("de vida institucional"),
        Regex("anios?\\s+en\\s+el\\s+(?:pais|peru)")
    )

    /** Marcadores que solo valen si estan pegados ANTES del numero. */
    private val EMPRESA_ANTES = Regex(
        "\\b(?:somos|empresa|grupo|corporacion|compania|organizacion|holding|consorcio|" +
            "multinacional|transnacional)\\b[^.;:]{0,70}$"
    )

    /**
     * Verbos en tercera persona pegados DESPUES del numero: es el blurb
     * corporativo ("con mas de 15 anios de experiencia, desarrolla y construye
     * proyectos"). El gerundio ("desarrollando funciones similares") es del
     * puesto, por eso el \b final es obligatorio.
     */
    private val EMPRESA_DESPUES = Regex(
        "^\\s*[,)]?\\s*(?:desarrolla|desarrollamos|construye|construimos|brinda|brindamos|" +
            "se\\s+dedica|nos\\s+dedicamos|opera|operamos|lidera|lideramos|" +
            "impulsando\\s+el\\s+desarrollo)\\b"
    )

    private val CONTEXTO_EDAD =
        listOf("edad", "mayores de", "mayor de", "a partir de los", "menores de")

    /** Plazos que no son experiencia: prueba, contrato, descanso, garantia. */
    private val RUIDO_PREVIO = listOf(
        "periodo de prueba", "luego de", "despues de", "a partir del", "duracion",
        "plazo de", "contrato por", "contrato de", "vigencia", "renovacion",
        "reemplazo por", "licencia por", "descanso de", "vacaciones", "garantia de",
        "capacitacion de", "induccion de", "por un periodo de", "temporal por",
        "planilla luego de", "cada"
    )
    private val RUIDO_POST = listOf(
        "de prueba", "de contrato", "de vigencia", "de licencia", "de descanso",
        "de vacaciones", "de garantia", "de induccion", "de capacitacion",
        "de duracion", "de plazo", "de gracia"
    )

    private val RE_EXP_SIN_NUMERO = Regex(
        "experiencia\\s+(?:previa|comprobada|comprobable|demostrable|acreditada|solida|amplia|minima|laboral|relevante)\\b" +
            "|(?:con|contar con|acreditar|indispensable|deseable|requiere|requerimos|posea|poseer|tener)\\s+experiencia\\b" +
            "|experiencia\\s+(?:en|como|realizando|liderando|manejando|trabajando|desempenando|gestionando)\\b"
    )

    /** "Experiencia" que habla del cliente o del servicio, no del postulante. */
    private val RUIDO_EXPERIENCIA = listOf(
        "experiencia del cliente", "experiencia de cliente", "experiencia al cliente",
        "experiencia de usuario", "experiencia de compra", "experiencia bancaria",
        "experiencia de marca", "experiencia digital", "experiencia memorable",
        "experiencia unica", "mejor experiencia", "vive la experiencia",
        "experiencia de servicio", "grata experiencia", "experiencia wow",
        "nuestra experiencia"
    )

    private const val VENTANA_ANTES = 45
    private const val VENTANA_DESPUES = 35
    private const val TOPE_MESES = 240   // 20 anios; mas que eso es trayectoria de empresa

    private fun aCantidad(s: String): Double? {
        val t = s.trim().replace(',', '.')
        return t.toDoubleOrNull() ?: NUMEROS_TEXTO[t]?.toDouble()
    }

    private fun factor(unidad: String): Int = when {
        unidad.startsWith("mes") -> 1
        unidad.startsWith("semestre") -> 6
        else -> 12
    }

    private fun esContextoEmpresa(previo: String, cuerpo: String, post: String): Boolean {
        val todo = previo + cuerpo + post
        if (CONTEXTO_EMPRESA.any { it.containsMatchIn(todo) }) return true
        if (EMPRESA_ANTES.containsMatchIn(previo)) return true
        if (EMPRESA_DESPUES.containsMatchIn(post)) return true
        return false
    }

    private fun esPlazoNoExperiencia(previo: String, post: String): Boolean {
        if (RUIDO_PREVIO.any { previo.contains(it) }) return true
        val p = post.trimStart()
        return RUIDO_POST.any { p.startsWith(it) }
    }

    /** Recibe el texto YA normalizado por Texto.norm (minusculas, sin tildes). */
    fun extraerExperiencia(textoNormalizado: String): Experiencia {
        val t = recortarRuidoDePortal(textoNormalizado)
            .replace("anos", "anios")
            .replace("ano ", "anio ")
        if (t.isBlank()) return Experiencia(null, null, null, TipoExperiencia.NO_MENCIONADA)

        val candidatos = mutableListOf<Pair<Int, String>>()
        var topeRango: Int? = null

        // Paso 1: rangos ("de 1 a 3 anios en el cargo") -> el minimo es el requisito
        for (m in RE_RANGO.findAll(t)) {
            val ini = m.range.first
            val fin = m.range.last + 1
            val previo = t.substring(maxOf(0, ini - 40), ini)
            val posterior = t.substring(fin, minOf(t.length, fin + 45))

            if (CONTEXTO_RANGO.none { previo.contains(it) } &&
                listOf("experiencia", "similar", "el cargo", "el puesto", "el rubro")
                    .none { posterior.contains(it) }
            ) continue
            if (esContextoEmpresa(previo, m.value, posterior)) continue
            if (CONTEXTO_EDAD.any { previo.contains(it) }) continue
            if (esPlazoNoExperiencia(previo, posterior)) continue

            val n1 = aCantidad(m.groupValues[1]) ?: continue
            val n2 = aCantidad(m.groupValues[2]) ?: continue
            if (n1 > n2) continue

            val f = factor(m.groupValues[3])
            val meses = Math.round(n1 * f).toInt()
            if (meses in 0..TOPE_MESES) {
                candidatos.add(meses to m.value)
                topeRango = Math.round(n2 * f).toInt()
            }
        }

        // Paso 2: menciones simples
        for (patron in PATRONES) {
            for (m in patron.findAll(t)) {
                val cantidad = aCantidad(m.groupValues[1]) ?: continue
                val ini = m.range.first
                val fin = m.range.last + 1

                var previo = t.substring(maxOf(0, ini - VENTANA_ANTES), ini)
                // No cruzar el fin de la oracion anterior: "empresa con 80 anios de
                // trayectoria. Requisitos: 1 anio en ventas" -> el "1 anio" es valido.
                val corte = maxOf(previo.lastIndexOf('.'), previo.lastIndexOf(';'), previo.lastIndexOf(':'))
                if (corte != -1) previo = previo.substring(corte + 1)
                val posterior = t.substring(fin, minOf(t.length, fin + VENTANA_DESPUES))

                if (esContextoEmpresa(previo, m.value, posterior)) continue
                if (CONTEXTO_EDAD.any { previo.contains(it) }) continue
                if (esPlazoNoExperiencia(previo, posterior)) continue

                val meses = Math.round(cantidad * factor(m.groupValues[2])).toInt()
                if (meses in 0..TOPE_MESES) candidatos.add(meses to m.value)
            }
        }

        // Paso 3: lo cuantificado manda sobre cualquier "sin experiencia" suelto.
        if (candidatos.isNotEmpty()) {
            val (meses, evidencia) = candidatos.minByOrNull { it.first }!!
            val tipo = if (meses == 0) TipoExperiencia.NO_REQUERIDA else TipoExperiencia.CUANTIFICADA
            return Experiencia(meses, topeRango, evidencia, tipo)
        }

        // Paso 4: sin numeros, recien ahora vale la ausencia de experiencia.
        for (m in RE_SIN_EXPERIENCIA.findAll(t)) {
            val entorno = t.substring(maxOf(0, m.range.first - 60), minOf(t.length, m.range.last + 41))
            if (RUIDO_SIN_EXP.any { entorno.contains(it) }) continue
            return Experiencia(0, null, "sin experiencia requerida", TipoExperiencia.NO_REQUERIDA)
        }

        if (requiereExperienciaSinCuantificar(t)) {
            return Experiencia(null, null, "requerida sin cuantificar", TipoExperiencia.REQUERIDA_SIN_CUANTIFICAR)
        }
        return Experiencia(null, null, null, TipoExperiencia.NO_MENCIONADA)
    }

    private fun requiereExperienciaSinCuantificar(t: String): Boolean {
        for (m in RE_EXP_SIN_NUMERO.findAll(t)) {
            val ini = m.range.first
            val fin = m.range.last + 1
            val entorno = t.substring(maxOf(0, ini - 25), minOf(t.length, fin + 35))
            if (RUIDO_EXPERIENCIA.any { entorno.contains(it) }) continue
            return true
        }
        return false
    }

    // =====================================================================
    // Seniority
    // =====================================================================

    /**
     * Se evalua de mayor a menor jerarquia. "semi senior" se resuelve con
     * lookbehind: antes "Analista Semi Senior" caia en Senior y el prefiltro
     * lo descartaba como si fuera un puesto fuera de alcance.
     */
    private val NIVELES: List<Pair<String, List<String>>> = listOf(
        "Dirección" to listOf("\\bdirector", "\\bdirectora\\b", "gerente general", "\\bceo\\b",
            "\\bcfo\\b", "\\bcoo\\b", "\\bcto\\b", "country manager", "vicepresidente"),
        "Gerencia" to listOf("\\bgerente\\b", "\\bgerencia\\b", "head of", "\\bmanager\\b"),
        "Jefatura" to listOf("\\bjefe\\b", "\\bjefa\\b", "\\bjefatura\\b", "sub gerente", "subgerente"),
        "Supervisión" to listOf("\\bsupervisor", "\\bcoordinador", "\\blider\\b", "\\bleader\\b",
            "\\bencargad[oa]\\b", "responsable de"),
        "Senior" to listOf("(?<!semi )\\bsenior\\b", "\\bsr\\.", "\\bsr\\b",
            "\\bespecialista\\b", "\\bexperto\\b"),
        "Semi Senior" to listOf("semi\\s*senior", "\\bssr\\b", "\\banalista\\b"),
        "Junior" to listOf("\\bjunior\\b", "\\bjr\\.", "\\bjr\\b", "\\btrainee\\b",
            "\\bauxiliar\\b", "\\basistente\\b", "\\baprendiz\\b"),
        "Practicante" to listOf("\\bpracticante\\b", "practicas pre", "practicas profesionales",
            "\\bpasante\\b", "\\binternship\\b", "\\bintern\\b")
    )

    /** Niveles que quedan por encima de un puesto de entrada. */
    val NIVELES_SENIOR = setOf("Dirección", "Gerencia", "Jefatura", "Supervisión", "Senior")

    /** Se compila una sola vez; el pipeline llama esto desde varias corrutinas. */
    private val CACHE_REGEX = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    private fun contiene(texto: String, clave: String): Boolean =
        CACHE_REGEX.getOrPut(clave) { Regex(clave) }.containsMatchIn(texto)

    /** Recibe el titulo sin normalizar; normaliza internamente. */
    fun detectarSeniority(titulo: String): String {
        val t = " " + Texto.norm(titulo) + " "
        // Practicante manda sobre todo lo demas si aparece en el titulo.
        if (NIVELES.last().second.any { contiene(t, it) }) return "Practicante"
        for ((nivel, claves) in NIVELES) {
            if (claves.any { contiene(t, it) }) return nivel
        }
        return "Operativo / Sin especificar"
    }
}
