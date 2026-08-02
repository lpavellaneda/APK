package com.jobai.hunter.domain

import android.util.Log
import com.jobai.hunter.data.model.DiscardedOffer
import com.jobai.hunter.data.model.JobOffer
import com.jobai.hunter.data.scraper.BumeranScraper
import com.jobai.hunter.data.scraper.ComputrabajoScraper
import com.jobai.hunter.data.scraper.LinkedInScraper
import com.jobai.hunter.domain.matcher.PerfilMatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ScraperEngine(private val cacheDir: java.io.File? = null) {

    /** Cloudflare manda __cf_bm / cf_clearance y espera recibirlas de vuelta. */
    private val cookieJar = object : CookieJar {
        private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val lista = store.getOrPut(url.host) { mutableListOf() }
            synchronized(lista) {
                for (c in cookies) {
                    lista.removeAll { it.name == c.name }
                    lista.add(c)
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val lista = store[url.host] ?: return emptyList()
            val ahora = System.currentTimeMillis()
            return synchronized(lista) {
                lista.removeAll { it.expiresAt < ahora }
                lista.toList()
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 32
            maxRequestsPerHost = 6
        })
        .cookieJar(cookieJar)
        .connectionPool(ConnectionPool(24, 5, TimeUnit.MINUTES))
        .connectionSpecs(com.jobai.hunter.data.net.NetFingerprint.connectionSpecs())
        .apply {
            cacheDir?.let { cache(okhttp3.Cache(java.io.File(it, "http_cache"), 64L * 1024 * 1024)) }
        }
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .addInterceptor(com.jobai.hunter.data.net.NetFingerprint.ChromeHeaderOrderInterceptor())
        .build()

    private val bumeran = BumeranScraper(client)
    private val computrabajo = ComputrabajoScraper(client)
    private val linkedin = LinkedInScraper(client)

    private val MAX_DIAS = 30
    /** Tope de experiencia exigida, en meses. 24 = dos años. */
    private val MAX_MESES_EXPERIENCIA = 24

    private val BUMERAN_QUERIES = listOf("analista", "asistente", "auxiliar", "analyst", "assistant")
    private val INDUSTRIAL_QUERIES = listOf("analista", "asistente")

    /**
     * Una sola query booleana: el keywords de LinkedIn busca en el cuerpo del
     * aviso, no solo en el título, así que el prefiltro de carrera lo hace el
     * servidor y bajamos de ~1000 resultados a unas decenas.
     */
    private val LINKEDIN_QUERIES = listOf(
        "(analista OR asistente) AND (\"ingeniería industrial\" OR \"ingenieria industrial\" " +
            "OR \"ingeniero industrial\" OR \"ingeniera industrial\" " +
            "OR \"ing industrial\" OR \"ing. industrial\" OR \"industrial engineering\")"
    )

    // OJO: el texto llega normalizado (minusculas y SIN tildes) antes del regex.
    internal val ING_IND_REGEX = Regex(
        "(?:" +
            "ingen[a-z]{0,6}\\s+(?:de\\s+)?industrial" +
            "|ing[.,]?\\s*industrial" +
            "|ing[.,]?\\s*ind\\b" +
            "|industrial\\s+engineer(?:ing)?" +
            "|(?:ingen[a-z]{0,6}|bachiller|egresad[oa]|titulad[oa]|estudiante|licenciad[oa]|carrera|carreras)" +
            "[^.;\\n]{0,40}\\bindustrial\\b" +
            ")"
    )

    // "sector industrial", "seguridad industrial" NO son la carrera.
    private val INDUSTRIAL_NO_CARRERA = Regex(
        "(?:sector|rubro|ambito|parque|zona|planta|seguridad|higiene|corporacion|grupo|industria)" +
            "\\s+(?:\\w+\\s+){0,2}industrial"
    )

    private val HTML_TAGS = Regex("<[^>]*>")
    private val ESPACIOS = Regex("\\s+")

    private val PRACTICANTE_REGEX = Regex(
        "\\b(pract[a-z]*|practicante|practica|practicas|trainee|pasante|intern|" +
            "preprofesional|pre profesional|pre-profesional)\\b"
    )

    fun runFullPipeline(
        descripcionesPrevias: Map<String, String> = emptyMap()
    ): Flow<ScraperEvent> = channelFlow {

        // ---------- 1. Descarga de listados ----------
        send(ScraperEvent.Status("⚡ [1/3] Descargando vacantes de los ultimos $MAX_DIAS dias..."))

        computrabajo.warmup()

        val hechas = AtomicInteger(0)
        val totalTareas = BUMERAN_QUERIES.size + INDUSTRIAL_QUERIES.size + LINKEDIN_QUERIES.size

        val crudas = coroutineScope {
            val tareas = mutableListOf<kotlinx.coroutines.Deferred<List<JobOffer>>>()

            BUMERAN_QUERIES.mapTo(tareas) { query ->
                async {
                    val res = runCatching { bumeran.scrapeQuery(query, MAX_DIAS) }
                    val list = res.getOrDefault(emptyList())
                    send(ScraperEvent.Status(estado(hechas, totalTareas, "Bumeran", query, res.isFailure || list.isEmpty())))
                    list
                }
            }
            INDUSTRIAL_QUERIES.mapTo(tareas) { query ->
                async {
                    val res = runCatching { computrabajo.scrapeSlug(query, MAX_DIAS) }
                    val list = res.getOrDefault(emptyList())
                    send(ScraperEvent.Status(estado(hechas, totalTareas, "Computrabajo", query, res.isFailure || list.isEmpty())))
                    list
                }
            }
            LINKEDIN_QUERIES.mapTo(tareas) { query ->
                async {
                    val res = runCatching { linkedin.scrapeQuery(query, MAX_DIAS) }
                    val list = res.getOrDefault(emptyList())
                    send(ScraperEvent.Status(estado(hechas, totalTareas, "LinkedIn", "ing. industrial", res.isFailure || list.isEmpty())))
                    list
                }
            }

            tareas.awaitAll().flatten()
        }

        val unicas = crudas
            .filter { it.url.isNotBlank() }
            .distinctBy { limpiarUrl(it.url) }

        Log.d(TAG, "Crudas=${crudas.size} unicas=${unicas.size} ${unicas.groupingBy { it.portal }.eachCount()}")

        // ---------- 2. Prefiltro barato: rol, seniority y fecha ----------
        val descartadas = mutableListOf<DiscardedOffer>()
        val candidatas = mutableListOf<JobOffer>()

        for (o in unicas) {
            val tituloNorm = Texto.norm(o.puesto)
            val dias = DateUtils.diasDesde(o.fechaPublicacion)
            val seniority = PerfilExtractor.detectarSeniority(o.puesto)
            when {
                PRACTICANTE_REGEX.containsMatchIn(tituloNorm) ->
                    descartadas.add(descarte(o, "Puesto de practicante"))
                // Filtro nuevo: "Analista Sr." no es un puesto de entrada por
                // mas que la descripcion no cuantifique los anios.
                seniority in PerfilExtractor.NIVELES_SENIOR ->
                    descartadas.add(descarte(o, "Nivel $seniority en el titulo"))
                dias != null && dias > MAX_DIAS ->
                    descartadas.add(descarte(o, "Publicada hace $dias dias"))
                else -> candidatas.add(o)
            }
        }
        Log.d(TAG, "Candidatas tras prefiltro: ${candidatas.size}")

        // ---------- 3. Enriquecimiento + filtrado ----------
        send(ScraperEvent.Status("📥 [2/3] Procesando ${candidatas.size} vacantes..."))

        val activas = mutableListOf<JobOffer>()
        val procesadas = AtomicInteger(0)

        coroutineScope {
            candidatas.map { offer ->
                async {
                    try {
                        val cacheada = descripcionesPrevias[offer.url]
                        val enriched = when {
                            cacheada != null && cacheada.length >= 400 -> offer.copy(descripcion = cacheada)
                            offer.portal == "Computrabajo" && offer.descripcion.length < 400 -> {
                                val d = computrabajo.fetchFullDescription(offer.url)
                                if (d.isNotBlank()) offer.copy(descripcion = d) else offer
                            }
                            offer.portal == "LinkedIn" && offer.descripcion.length < 400 -> {
                                linkedin.esperarSiBloqueado()
                                val d = linkedin.fetchFullDescription(offer.url)
                                if (d.isNotBlank()) offer.copy(descripcion = d) else offer
                            }
                            else -> offer
                        }

                        val descLimpia = enriched.descripcion
                            .replace(HTML_TAGS, " ").replace(ESPACIOS, " ").trim()
                        val texto = Texto.norm(enriched.puesto + " " + descLimpia)

                        val exp = PerfilExtractor.extraerExperiencia(texto)

                        // El bug: se leia enriched.seniority, que en este punto
                        // todavia es el valor por defecto ("Operativo / Sin
                        // especificar") porque OfferEnricher corre DESPUES.
                        // Resultado: esSeniorOJefatura era siempre false, pasaExp
                        // siempre true y MAX_MESES_EXPERIENCIA no se usaba nunca.
                        // Por eso una oferta con "4+ anios" entraba a la lista.
                        val seniorityReal = PerfilExtractor.detectarSeniority(enriched.puesto)
                        val esSeniorOJefatura = seniorityReal in PerfilExtractor.NIVELES_SENIOR
                        val exigeDemasiado = (exp.mesesMin ?: 0) > MAX_MESES_EXPERIENCIA
                        val pasaExp = !esSeniorOJefatura && !exigeDemasiado

                        val pasaCarrera = ING_IND_REGEX.containsMatchIn(texto) &&
                            !(INDUSTRIAL_NO_CARRERA.containsMatchIn(texto) && !mencionaCarreraIndustrial(texto))

                        val zona = Ubicaciones.clasificar(enriched.ubicacion)

                        if (pasaCarrera && pasaExp) {
                            val modCalculada = calcularModalidad(enriched.modalidad, texto)
                            val base = enriched.copy(
                                descripcion = descLimpia,
                                modalidad = modCalculada,
                                distrito = Ubicaciones.distritoVisible(enriched.ubicacion),
                                departamento = Ubicaciones.departamentoVisible(enriched.ubicacion).orEmpty(),
                                zona = zona.name,
                                fechaEpoch = DateUtils.epochDe(enriched.fechaPublicacion) ?: 0L,
                                mesesExperiencia = exp.mesesMin,
                                expTipo = exp.tipo.name,
                                expEvidencia = exp.evidencia.orEmpty()
                            )
                            val enrichedOffer = OfferEnricher.enriquecer(base)
                            val matchResult = PerfilMatcher.evaluar(enrichedOffer)
                            val finalOffer = enrichedOffer.copy(matchScore = matchResult.score)
                            Log.d(TAG, "OK ${finalOffer.puesto} | ${zona.name} | match=${matchResult.score} | exp=${exp.tipo}:${exp.mesesMin} | reg=${finalOffer.regimenTrabajo}")
                            synchronized(activas) { activas.add(finalOffer) }
                            send(ScraperEvent.OfferFound(finalOffer))
                        } else {
                            val motivo = when {
                                !pasaCarrera -> "No menciona Ingenieria Industrial"
                                exigeDemasiado -> "Pide ${exp.mesesMin} meses de experiencia " +
                                    "(tope $MAX_MESES_EXPERIENCIA) — ${exp.evidencia}"
                                else -> "Cargo de nivel $seniorityReal (Senior / Jefatura)"
                            }
                            synchronized(descartadas) { descartadas.add(descarte(enriched, motivo)) }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error procesando: ${e.message}")
                    } finally {
                        val curr = procesadas.incrementAndGet()
                        send(ScraperEvent.Status("📥 [2/3] Procesando $curr/${candidatas.size}..."))
                    }
                }
            }.awaitAll()
        }

        send(ScraperEvent.Finished(activas.size, descartadas.size))
        Log.d(TAG, "Activas=${activas.size} Descartadas=${descartadas.size}")
    }

    private fun estado(hechas: AtomicInteger, total: Int, portal: String, query: String, vacio: Boolean): String {
        val sufijo = if (vacio) " — $portal sin resultados" else " — $portal \"$query\""
        return "⚡ [1/3] ${hechas.incrementAndGet()}/$total$sufijo"
    }

    private fun calcularModalidad(actual: String, texto: String): String = when {
        texto.contains("100% presencial") || texto.contains("de manera presencial") ||
            texto.contains("trabajo presencial") || texto.contains("modalidad presencial") -> "Presencial"
        actual == "Remoto" || texto.contains("remoto") || texto.contains("teletrabajo") ||
            texto.contains("home office") -> "Remoto"
        actual == "Hibrido" || texto.contains("hibrido") || texto.contains("hybrid") ||
            texto.contains("semi presencial") || texto.contains("semipresencial") -> "Hibrido"
        else -> "Presencial"
    }

    private fun descarte(o: JobOffer, motivo: String) = DiscardedOffer(
        o.portal, o.puesto, o.empresa, motivo, o.url, o.descripcion, o.ubicacion,
        lat = 0.0, lon = 0.0
    )

    private fun mencionaCarreraIndustrial(texto: String): Boolean =
        Regex("\\b(ingenieria|ing[.,]|bachiller|egresado|estudiante)\\s+industrial\\b")
            .containsMatchIn(texto)

    private fun limpiarUrl(url: String): String =
        url.split("?")[0].split("#")[0].trim().trimEnd('/')

    private companion object {
        const val TAG = "JobAI_Scraper"
    }
}

sealed class ScraperEvent {
    data class Status(val message: String) : ScraperEvent()
    data class OfferFound(val offer: JobOffer) : ScraperEvent()
    data class Finished(val active: Int, val discarded: Int) : ScraperEvent()
}
