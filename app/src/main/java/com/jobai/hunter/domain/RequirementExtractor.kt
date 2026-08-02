package com.jobai.hunter.domain

import java.text.Normalizer

/**
 * Extrae requisitos (herramientas, software, metodologías, normativa, formación
 * y habilidades) del cuerpo de un aviso de empleo.
 *
 * Mejoras sobre la v1:
 *  1. NORMALIZACIÓN   — decodifica entidades HTML, separa palabras pegadas
 *                       ("AuditoríaBeneficios"), quita emojis y pliega tildes.
 *  2. NIVEL POR VENTANA — "Excel a nivel avanzado" / "Excel (Avanzado)" ya no se
 *                       degradan a un genérico. Ante un rango ("intermedio -
 *                       avanzado") se toma el MÍNIMO, que es el umbral real.
 *  3. EXIGENCIA       — distingue indispensable / deseable, con herencia por
 *                       enumeración ("Deseable manejo de Power BI y SAP").
 *  4. ZONAS           — ignora la sección de beneficios ("ingreso a planilla
 *                       desde el primer día" ya no cuenta como requisito) y
 *                       marca la confianza según de qué sección salió el match.
 *  5. JERARQUÍA       — el dedupe ya no depende del orden de la lista: se declara
 *                       con el campo `padre`. Un hijo reemplaza al padre; dos o
 *                       más hijos colapsan de vuelta en el padre.
 *  6. SIN CORTE CIEGO — el orden de salida es por relevancia + confianza, no por
 *                       posición en el catálogo.
 */
object RequirementExtractor {

    // ─────────────────────────── Modelo ───────────────────────────

    enum class Categoria(val relevanciaBase: Int) {
        BI_DATOS(95), PROGRAMACION(95), OFIMATICA(90), ERP_CRM(85),
        DISENO_TECNICO(80), METODOLOGIA(70), NORMATIVA(70), IDIOMA(65),
        FUNCIONAL(50), FORMACION(35), BLANDA(20)
    }

    enum class Nivel { BASICO, INTERMEDIO, AVANZADO }

    enum class Exigencia { INDISPENSABLE, DESEABLE, NO_ESPECIFICADO }

    /** De qué sección del aviso salió el match. */
    enum class Confianza(val peso: Int) { ALTA(20), MEDIA(8), BAJA(0) }

    data class Requisito(
        val nombre: String,
        val categoria: Categoria,
        val nivel: Nivel? = null,
        val exigencia: Exigencia = Exigencia.NO_ESPECIFICADO,
        val confianza: Confianza = Confianza.BAJA,
        val relevancia: Int = 0,
        val posicion: Int = 0
    ) {
        /** Texto listo para UI: "Excel Avanzado", "SAP MM", "Inglés Intermedio". */
        val etiqueta: String
            get() = if (nivel == null) nombre else "$nombre ${nivel.name.lowercase()
                .replaceFirstChar { it.uppercase() }}"
    }

    private data class Skill(
        val nombre: String,
        val patron: Regex,
        val categoria: Categoria,
        val soportaNivel: Boolean = false,
        val padre: String? = null,
        val relevancia: Int = -1
    ) {
        val peso: Int get() = if (relevancia >= 0) relevancia else categoria.relevanciaBase
    }

    // ─────────────────────── Normalización ───────────────────────

    private val ENTIDAD_NUM = Regex("&#x([0-9a-fA-F]+);|&#(\\d+);")
    private val ENTIDADES = mapOf(
        "&amp;" to "&", "&quot;" to "\"", "&apos;" to "'", "&nbsp;" to " ",
        "&lt;" to "<", "&gt;" to ">", "&ntilde;" to "ñ", "&aacute;" to "á",
        "&eacute;" to "é", "&iacute;" to "í", "&oacute;" to "ó", "&uacute;" to "ú",
        "&uuml;" to "ü", "&hellip;" to "...", "&mdash;" to "-", "&ndash;" to "-"
    )
    private val PEGADAS = Regex("([a-z0-9áéíóúñ])([A-ZÁÉÍÓÚÑ])")
    private val EMOJI = Regex("[\\p{So}\\p{Cn}\\uFE0F]")
    private val MARCAS = Regex("\\p{Mn}+")
    private val ESPACIOS = Regex("[ \\t]{2,}")
    private val SALTOS = Regex("[\\r\\n]+")

    /** HTML → texto plano, sin tildes y en minúsculas, listo para regex. */
    internal fun normalizar(bruto: String): String {
        var t = bruto
        repeat(2) { p ->                                   // doble decodificación
            ENTIDADES.forEach { (k, v) -> t = t.replace(k, v, ignoreCase = true) }
            t = ENTIDAD_NUM.replace(t) { m ->
                val cp = m.groupValues[1].takeIf { it.isNotEmpty() }?.toIntOrNull(16)
                    ?: m.groupValues[2].toIntOrNull() ?: return@replace " "
                if (cp in 32..0x2FFF) String(Character.toChars(cp)) else " "
            }
        }
        t = EMOJI.replace(t, " ")
        t = PEGADAS.replace(t, "$1 $2")                    // "procesosFunciones" → "procesos Funciones"
        t = MARCAS.replace(Normalizer.normalize(t, Normalizer.Form.NFD), "")
        t = SALTOS.replace(t, "\n")
        t = ESPACIOS.replace(t, " ")
        return t.lowercase()
    }

    // ──────────────────────── Catálogo ────────────────────────
    // Los patrones se escriben SIN tildes y en minúsculas (el texto ya viene plegado).
    // `\s*` dentro de los compuestos absorbe el efecto de separar palabras pegadas
    // ("AutoCAD" → "auto cad", "PostgreSQL" → "postgre sql").

    private val CATALOGO: List<Skill> = buildList {
        fun s(
            nombre: String, patron: String, cat: Categoria,
            nivel: Boolean = false, padre: String? = null, rel: Int = -1
        ) = add(Skill(nombre, Regex(patron), cat, nivel, padre, rel))

        // ── Ofimática
        s("Excel", """\bexcel\b""", Categoria.OFIMATICA, nivel = true)
        s("Word", """\bword\b""", Categoria.OFIMATICA, nivel = true)
        s("PowerPoint", """\bpower\s*point\b|\bppt\b""", Categoria.OFIMATICA, nivel = true)
        s("Outlook", """\boutlook\b""", Categoria.OFIMATICA)
        s("Google Sheets", """\bgoogle\s+sheets\b|\bhojas?\s+de\s+calculo\b""", Categoria.OFIMATICA)
        s("Office", """\b(microsoft|ms)\s+office\b|\boffice\s*365\b|\bofimatica\b|\bpaquete\s+office\b""",
            Categoria.OFIMATICA, nivel = true)

        // ── BI y datos
        s("Power BI", """\bpower\s*bi\b""", Categoria.BI_DATOS, nivel = true)
        s("Tableau", """\btableau\b""", Categoria.BI_DATOS, nivel = true)
        s("Looker / Data Studio", """\blooker\b|\bdata\s+studio\b""", Categoria.BI_DATOS)
        s("Power Query", """\bpower\s*query\b""", Categoria.BI_DATOS, nivel = true)
        s("Power Pivot", """\bpower\s*pivot\b""", Categoria.BI_DATOS)
        s("DAX", """\bdax\b""", Categoria.BI_DATOS)
        s("Power Apps / Automate", """\bpower\s*(apps|automate)\b""", Categoria.BI_DATOS)
        s("Tablas dinámicas", """\btablas\s+dinamicas\b""", Categoria.BI_DATOS, rel = 60)
        s("Dashboards", """\bdashboards?\b|\btableros?\s+de\s+control\b""", Categoria.BI_DATOS, rel = 60)
        s("ETL", """\betl\b|\bpipelines?\s+de\s+datos\b""", Categoria.BI_DATOS)
        s("Databricks", """\bdatabricks\b""", Categoria.BI_DATOS)
        s("Big Data", """\bbig\s+data\b|\bmineria\s+de\s+datos\b""", Categoria.BI_DATOS, rel = 60)

        // ── Programación y bases de datos
        s("SQL", """\bsql\b""", Categoria.PROGRAMACION, nivel = true)
        s("MySQL", """\bmy\s*sql\b""", Categoria.PROGRAMACION, padre = "SQL")
        s("PostgreSQL", """\bpostgre\s*sql\b""", Categoria.PROGRAMACION, padre = "SQL")
        s("SQL Server", """\bsql\s+server\b""", Categoria.PROGRAMACION, padre = "SQL")
        s("BigQuery", """\bbig\s*query\b""", Categoria.PROGRAMACION, padre = "SQL")
        s("Python", """\bpython\b|\bphyton\b|\bpyton\b""", Categoria.PROGRAMACION, nivel = true) // incluye typos reales
        s("R", """\br\s*studio\b|\blenguaje\s+r\b""", Categoria.PROGRAMACION)
        s("VBA / Macros", """\bvba\b|\bmacros?\b""", Categoria.PROGRAMACION)
        s("Java", """\bjava\b(?!script)""", Categoria.PROGRAMACION)
        s("JavaScript", """\bjavascript\b""", Categoria.PROGRAMACION)
        s(".NET / C#", """\.net\b|\bc#""", Categoria.PROGRAMACION)
        s("Git", """\bgit(hub|lab)?\b""", Categoria.PROGRAMACION)
        s("Linux", """\blinux\b""", Categoria.PROGRAMACION)
        s("Cloud (AWS/Azure/GCP)", """\baws\b|\bazure\b|\bgoogle\s+cloud\b|\bgcp\b""", Categoria.PROGRAMACION)

        // ── ERP y CRM
        s("SAP", """\bsap\b""", Categoria.ERP_CRM, nivel = true)
        listOf("mm", "pp", "fi", "co", "sd", "wm", "hcm", "bw").forEach {
            s("SAP ${it.uppercase()}", """\bsap\s+$it\b""", Categoria.ERP_CRM, padre = "SAP")
        }
        s("Oracle", """\boracle\b""", Categoria.ERP_CRM)
        s("JD Edwards", """\bjd\s+edwards\b""", Categoria.ERP_CRM)
        s("Dynamics", """\bdynamics\b""", Categoria.ERP_CRM)
        s("Odoo", """\bodoo\b""", Categoria.ERP_CRM)
        s("ERP contable local", """\b(concar|starsoft|siscont|contasis|softland)\b""", Categoria.ERP_CRM)
        s("ERP", """\berp\b""", Categoria.ERP_CRM, rel = 75)
        s("CRM", """\bcrm\b""", Categoria.ERP_CRM)
        s("Salesforce", """\bsalesforce\b""", Categoria.ERP_CRM, padre = "CRM")
        s("HubSpot", """\bhubspot\b""", Categoria.ERP_CRM, padre = "CRM")

        // ── Metodologías
        s("Lean", """\blean\b""", Categoria.METODOLOGIA)
        s("Six Sigma", """\bsix\s+sigma\b""", Categoria.METODOLOGIA)
        s("Kaizen", """\bkaizen\b""", Categoria.METODOLOGIA)
        s("5S", """\b5\s*'?s\b""", Categoria.METODOLOGIA)
        s("Scrum", """\bscrum\b""", Categoria.METODOLOGIA)
        s("Agile", """\bagile\b|\bagiles?\b|\bmetodologias?\s+agil""", Categoria.METODOLOGIA)
        s("Kanban", """\bkanban\b""", Categoria.METODOLOGIA)
        s("Jira", """\bjira\b""", Categoria.METODOLOGIA)
        s("BPM / BPMN", """\bbpmn\b|\bbusiness\s+process\b|\bbpm\b(?!\s*y?\s*poes)""", Categoria.METODOLOGIA)
        s("PMP / PMI", """\bpmp\b|\bpmi\b|\bpmbok\b""", Categoria.METODOLOGIA)
        s("MS Project", """\b(ms|microsoft)\s+project\b""", Categoria.METODOLOGIA)
        s("Design Thinking", """\bdesign\s+thinking\b""", Categoria.METODOLOGIA)
        s("Mejora continua", """\bmejora\s+continua\b""", Categoria.METODOLOGIA, rel = 55)

        // ── Normativa y certificaciones
        listOf("9001", "14001", "45001", "27001", "17025", "22000").forEach {
            s("ISO $it", """\biso\s*$it\b""", Categoria.NORMATIVA)
        }
        s("SSOMA", """\bssoma\b""", Categoria.NORMATIVA)
        s("Seguridad y Salud (SST)", """\bseguridad\s+y\s+salud\b|\bsst\b|\bley\s*29783\b""", Categoria.NORMATIVA)
        s("HACCP / BPM-POES", """\bhaccp\b|\bpoes\b""", Categoria.NORMATIVA)
        s("NIIF / IFRS", """\bniif\b|\bifrs\b""", Categoria.NORMATIVA)
        s("SBS", """\bsbs\b""", Categoria.NORMATIVA)
        s("LA/FT", """\bla\s*/\s*ft\b|\blavado\s+de\s+activos\b""", Categoria.NORMATIVA)
        s("SUNAT / Tributario", """\bsunat\b|\btributari[oa]\b""", Categoria.NORMATIVA)

        // ── Diseño y software técnico
        s("AutoCAD", """\bauto\s*cad\b""", Categoria.DISENO_TECNICO, nivel = true)
        s("SolidWorks", """\bsolid\s*works\b""", Categoria.DISENO_TECNICO)
        s("Revit", """\brevit\b""", Categoria.DISENO_TECNICO)
        s("SketchUp", """\bsketch\s*up\b""", Categoria.DISENO_TECNICO)
        s("Bizagi", """\bbizagi\b""", Categoria.DISENO_TECNICO, nivel = true)
        s("Visio", """\bvisio\b""", Categoria.DISENO_TECNICO, nivel = true)
        s("Lucidchart / Miro", """\blucidchart\b|\bmiro\b""", Categoria.DISENO_TECNICO)
        s("ArcGIS / QGIS", """\barc\s*gis\b|\bqgis\b""", Categoria.DISENO_TECNICO)

        // ── Idiomas
        s("Inglés", """\bingles\b|\benglish\b""", Categoria.IDIOMA, nivel = true)
        s("Portugués", """\bportugues\b""", Categoria.IDIOMA, nivel = true)
        s("Quechua", """\bquechua\b""", Categoria.IDIOMA)

        // ── Dominio funcional / negocio
        s("KPIs", """\bkpis?\b|\bindicadores\s+de\s+gestion\b""", Categoria.FUNCIONAL, rel = 60)
        s("Logística / Supply Chain",
            """\blogistica\b|\bsupply\s+chain\b|\bcadena\s+de\s+suministro\b|\babastecimiento\b""", Categoria.FUNCIONAL)
        s("Comercio exterior",
            """\bcomercio\s+exterior\b|\baduan\w*|\bimportacion\w*|\bexportacion\w*|\bincoterms?\b""", Categoria.FUNCIONAL)
        s("Inventarios", """\binventari\w*|\bkardex\b""", Categoria.FUNCIONAL)
        s("Costos", """\bcostos\b|\bcosteo\b""", Categoria.FUNCIONAL)
        s("Presupuestos", """\bpresupuest\w*""", Categoria.FUNCIONAL)
        s("Facturación", """\bfacturacion\b""", Categoria.FUNCIONAL)
        s("Conciliaciones", """\bconciliaci\w*""", Categoria.FUNCIONAL)
        s("Cobranzas", """\bcobranza\w*|\bmorosidad\b|\brecuperacion\s+de\s+cartera\b""", Categoria.FUNCIONAL)
        s("Créditos / Evaluación crediticia",
            """\bevaluacion\s+crediticia\b|\banalisis\s+de\s+creditos?\b|\bcolocacion\w*\s+de\s+creditos?\b|\bcreditos?\s+mype\b""",
            Categoria.FUNCIONAL)
        s("Planillas / Nómina", """\bplanillas?\b|\bnomina\b|\bpdt\b|\bplame\b""", Categoria.FUNCIONAL)
        s("Reclutamiento", """\breclutamiento\b|\bseleccion\s+de\s+personal\b""", Categoria.FUNCIONAL)
        s("Atención al cliente",
            """\batencion\s+al\s+cliente\b|\bservicio\s+al\s+cliente\b|\bpost\s*venta\b""", Categoria.FUNCIONAL)
        s("Ventas / Negociación", """\bnegociacion\b|\bcierre\s+de\s+ventas\b|\bprospeccion\b""", Categoria.FUNCIONAL)
        s("Marketing digital",
            """\bseo\b|\bsem\b|\bgoogle\s+ads\b|\bmeta\s+ads\b|\bga4\b|\bmarketing\s+digital\b""", Categoria.FUNCIONAL)
        s("Gestión de riesgos",
            """\bgestion\s+de\s+riesgos?\b|\briesgo\s+operacional\b|\bmatriz\s+de\s+riesgos?\b""", Categoria.FUNCIONAL)
        s("Auditoría", """\bauditoria\b|\bcontrol\s+interno\b""", Categoria.FUNCIONAL)
        s("Control de gestión",
            """\bcontrol\s+de\s+gestion\b|\bplaneamiento\s+financiero\b|\bfp&a\b""", Categoria.FUNCIONAL)
        s("Compras / Procura",
            """\bcompras\b|\bprocura\w*|\bhomologacion\s+de\s+proveedores\b|\bcotizacion\w*""", Categoria.FUNCIONAL)

        // ── Formación y requisitos duros
        s("Bachiller / Titulado",
            """\bbachiller\b|\btitulad[oa]\b|\begresad[oa]\b|\btitulo\s+profesional\b""", Categoria.FORMACION)
        s("Estudios universitarios", """\buniversitari[oa]\b|\bestudios\s+universitarios\b""", Categoria.FORMACION)
        s("Técnico", """\btecnic[oa]\s+(superior|complet[oa]|titulad[oa])\b|\binstituto\s+tecnico\b""", Categoria.FORMACION)
        s("Maestría / MBA / Diplomado",
            """\bmaestria\b|\bmba\b|\bdiplomado\b|\bespecializacion\b|\bpostgrado\b""", Categoria.FORMACION)
        s("Colegiatura", """\bcolegiatura\b|\bcolegiad[oa]\b""", Categoria.FORMACION)
        s("Licencia de conducir", """\blicencia\s+de\s+conducir\b|\bbrevete\b""", Categoria.FORMACION)
        s("Disponibilidad para viajar",
            """\bdisponibilidad\s+(para|de)\s+viajar\b|\bviajes\s+a\s+provincia\b""", Categoria.FORMACION)
        s("Residir en zona", """\bresidir\s+en\b|\bvivir\s+cerca\b""", Categoria.FORMACION)

        // ── Habilidades blandas (relevancia baja: nunca desplazan a una herramienta)
        s("Trabajo en equipo", """\btrabajo\s+en\s+equipo\b""", Categoria.BLANDA)
        s("Liderazgo", """\bliderazgo\b|\bmanejo\s+de\s+equipos?\b""", Categoria.BLANDA)
        s("Comunicación efectiva", """\bcomunicacion\s+(efectiva|asertiva|clara)\b""", Categoria.BLANDA)
        s("Orientación a resultados", """\borientacion\s+a\s+(resultados|logros)\b""", Categoria.BLANDA)
        s("Pensamiento analítico", """\b(pensamiento|capacidad|perfil)\s+analitic\w*""", Categoria.BLANDA)
        s("Proactividad", """\bproactiv\w*""", Categoria.BLANDA)
    }

    // ───────────────────── Secciones del aviso ─────────────────────

    private const val H_EXCLUIDA =
        "beneficios|te\\s+ofrecemos|que\\s+ofrecemos|ofrecemos|condiciones\\s+laborales|condiciones|postulacion|como\\s+postular"
    private const val H_REQUISITO =
        "requisitos|perfil|conocimientos|competencias|que\\s+buscamos|buscamos|formacion|estudios|requerimientos|indispensable|deseable|habilidades"
    private const val H_FUNCION =
        "funciones|responsabilidades|retos|que\\s+haras|mision\\s+del\\s+puesto|principales\\s+actividades|desafios"

    private val RX_HEADER = Regex("¿?\\s*\\b($H_EXCLUIDA|$H_REQUISITO|$H_FUNCION)\\b[^\\n:.]{0,32}:")
    private val RX_E = Regex(H_EXCLUIDA)
    private val RX_R = Regex(H_REQUISITO)

    private enum class Zona { EXCLUIDA, REQUISITOS, FUNCIONES, INTRO }

    private data class Tramo(val desde: Int, val hasta: Int, val zona: Zona)

    private fun zonificar(t: String): List<Tramo> {
        val tramos = mutableListOf<Tramo>()
        var prev = 0
        var zPrev = Zona.INTRO
        for (m in RX_HEADER.findAll(t)) {
            val h = m.groupValues[1]
            val z = when {
                RX_E.matches(h) -> Zona.EXCLUIDA
                RX_R.matches(h) -> Zona.REQUISITOS
                else -> Zona.FUNCIONES
            }
            tramos += Tramo(prev, m.range.last + 1, zPrev)
            prev = m.range.last + 1
            zPrev = z
        }
        tramos += Tramo(prev, t.length, zPrev)
        return tramos
    }

    private fun zonaDe(tramos: List<Tramo>, pos: Int): Zona =
        tramos.firstOrNull { pos >= it.desde && pos < it.hasta }?.zona ?: Zona.INTRO

    // ──────────────── Nivel y exigencia por contexto ────────────────

    private val RX_AVANZADO = Regex("""\bavanzad|\bexpert|\bdominio\s+total\b|\bc1\b|\bc2\b""")
    private val RX_INTERMEDIO = Regex("""\bintermedi|\bb1\b|\bb2\b""")
    private val RX_BASICO = Regex("""\bbasic|\busuario\b|\bnivel\s+inicial\b|\ba1\b|\ba2\b""")
    private val RX_DESEABLE = Regex(
        """\bdeseable\b|\bno\s+excluyente\b|\bopcional\b|\bde\s+preferencia\b|\bpreferentemente\b|\bplus\b|\bvalorad\w*|\bnice\s+to\s+have\b|\bno\s+indispensable\b"""
    )
    private val RX_OBLIGATORIO = Regex(
        """\bindispensable\b|\bexcluyente\b|\bobligatori\w*|\bimprescindible\b|\brequisito\s+minimo\b"""
    )
    private val RX_CONECTOR = Regex("""^[\s,/y o\-–()]*$""")
    private val SEPARADORES = charArrayOf('.', ';', '\n')

    /** Texto tras el match, cortado en el separador o en el siguiente match. */
    private fun ventanaPost(t: String, fin: Int, inicios: List<Int>, largo: Int = 60): String {
        var lim = minOf(t.length, fin + largo)
        inicios.firstOrNull { it > fin }?.let { if (it < lim) lim = it }
        var i = fin
        while (i < lim && t[i] !in SEPARADORES) i++
        return t.substring(fin, i)
    }

    /** Texto previo al match, cortado en el separador o en el match anterior. */
    private fun ventanaPre(t: String, ini: Int, finales: List<Int>, largo: Int = 45): String {
        var lim = maxOf(0, ini - largo)
        finales.lastOrNull { it <= ini }?.let { if (it > lim) lim = it }
        var i = ini
        while (i > lim && t[i - 1] !in SEPARADORES) i--
        return t.substring(i, ini)
    }

    // ────────────────────────── API pública ──────────────────────────

    /**
     * Extrae todos los requisitos del aviso, ordenados por relevancia y confianza.
     * No trunca: usar [top] o filtrar por [Requisito.categoria] en la capa de UI.
     */
    fun extraerRequisitos(puesto: String, descripcion: String): List<Requisito> {
        val t = normalizar("$puesto. $descripcion")
        if (t.isBlank()) return emptyList()
        val tramos = zonificar(t)

        // 1. primera ocurrencia de cada skill FUERA de la zona de beneficios
        data class Crudo(val skill: Skill, val ini: Int, val fin: Int)

        val crudos = mutableListOf<Crudo>()
        for (sk in CATALOGO) {
            val m = sk.patron.findAll(t).firstOrNull { zonaDe(tramos, it.range.first) != Zona.EXCLUIDA }
                ?: continue
            crudos += Crudo(sk, m.range.first, m.range.last + 1)
        }
        if (crudos.isEmpty()) return emptyList()
        crudos.sortBy { it.ini }

        val inicios = crudos.map { it.ini }
        val finales = crudos.map { it.fin }

        // 2. nivel + exigencia por ventana de contexto
        val parcial = mutableListOf<Requisito>()
        var exigPrev: Exigencia? = null
        var finPrev: Int? = null
        val padreDe = HashMap<String, String?>()

        for (c in crudos) {
            val post = ventanaPost(t, c.fin, inicios)
            val pre = ventanaPre(t, c.ini, finales)
            val ctx = "$pre $post"

            // Ante un rango ("intermedio - avanzado") gana el MÍNIMO: es el umbral real.
            val nivel = if (!c.skill.soportaNivel) null else when {
                RX_BASICO.containsMatchIn(ctx) -> Nivel.BASICO
                RX_INTERMEDIO.containsMatchIn(ctx) -> Nivel.INTERMEDIO
                RX_AVANZADO.containsMatchIn(ctx) -> Nivel.AVANZADO
                else -> null
            }

            var exig: Exigencia? = null
            for (v in listOf(post, pre)) {
                if (RX_DESEABLE.containsMatchIn(v)) { exig = Exigencia.DESEABLE; break }
                if (RX_OBLIGATORIO.containsMatchIn(v)) { exig = Exigencia.INDISPENSABLE; break }
            }
            // Herencia por enumeración: "Deseable manejo de Power BI y SAP" → SAP también es deseable.
            if (exig == null && exigPrev != null && finPrev != null &&
                RX_CONECTOR.matches(t.substring(finPrev!!, c.ini))
            ) exig = exigPrev
            exigPrev = exig
            finPrev = c.fin

            val zona = zonaDe(tramos, c.ini)
            val conf = when (zona) {
                Zona.REQUISITOS -> Confianza.ALTA
                Zona.FUNCIONES -> Confianza.MEDIA
                else -> Confianza.BAJA
            }
            padreDe[c.skill.nombre] = c.skill.padre
            parcial += Requisito(
                nombre = c.skill.nombre, categoria = c.skill.categoria, nivel = nivel,
                exigencia = exig ?: Exigencia.NO_ESPECIFICADO, confianza = conf,
                relevancia = c.skill.peso, posicion = c.ini
            )
        }

        // 3. jerarquía declarativa (independiente del orden del catálogo)
        val conteoPadres = parcial.mapNotNull { padreDe[it.nombre] }.groupingBy { it }.eachCount()
        val colapsar = conteoPadres.filterValues { it >= 2 }.keys   // MySQL+PostgreSQL → SQL
        val reemplazan = conteoPadres.filterValues { it == 1 }.keys // SAP MM reemplaza a SAP

        return parcial
            .filterNot { padreDe[it.nombre] in colapsar }
            .filterNot { it.nombre in reemplazan }
            .sortedWith(compareByDescending<Requisito> { it.relevancia + it.confianza.peso }
                .thenBy { it.posicion })
    }

    /** Los N requisitos más relevantes; opcionalmente solo de ciertas categorías. */
    fun top(
        puesto: String, descripcion: String, n: Int = 6,
        categorias: Set<Categoria>? = null
    ): List<Requisito> =
        extraerRequisitos(puesto, descripcion)
            .let { r -> if (categorias == null) r else r.filter { it.categoria in categorias } }
            .take(n)

    /** Compatibilidad con la v1: devuelve solo las etiquetas de texto. */
    @Deprecated("Usar extraerRequisitos() para conservar nivel, exigencia y confianza.")
    fun extraerKeywords(puesto: String, descripcion: String, max: Int = 6): List<String> =
        top(puesto, descripcion, max).map { it.etiqueta }
}
