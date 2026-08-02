package com.jobai.hunter.data.scraper

import android.util.Log
import android.util.LruCache
import com.jobai.hunter.data.model.JobOffer
import com.jobai.hunter.data.net.AdaptiveGate
import com.jobai.hunter.data.net.NetFingerprint
import com.jobai.hunter.data.net.RateLimiter
import com.jobai.hunter.domain.AreaClassifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.Closeable
import java.io.IOException
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Scraper del endpoint "guest" de LinkedIn (sin sesion).
 *
 * Cambios respecto a la version anterior:
 *  - Se elimino la cookie li_at. Las cookies de infraestructura (__cf_bm, etc.)
 *    siguen viajando por el CookieJar del cliente padre heredado en newBuilder().
 *  - Paginacion secuencial con corte temprano real (antes el `break` corria
 *    despues del awaitAll, o sea despues de haber descargado las 20 paginas).
 *  - Una pagina vacia ya no es indistinguible de un error o un bloqueo.
 *  - Se maneja el HTTP 999 de LinkedIn y el "soft block" (200 con challenge).
 *  - CancellationException se relanza en vez de tragarse.
 *  - Peticiones cancelables (enqueue + call.cancel) en vez de execute().
 *  - Cache LRU de descripciones ademas del deduplicado en vuelo.
 *  - close() para no fugar el CoroutineScope.
 */
class LinkedInScraper(
    client: OkHttpClient,
    /** Texto de ubicacion del filtro. */
    private val ubicacionBusqueda: String = "Peru",
    /** Opcional: geoId de LinkedIn. Es mas estable que el string de ubicacion. */
    private val geoId: String? = null,
    /**
     * Filtros nativos del endpoint guest:
     *   f_E  Nivel: 1=Practicas 2=Inicial 3=Asociado 4=Mid-Senior 5=Director 6=Ejecutivo
     *   f_JT Contrato: F=Full-Time C=Contract P=Part-Time T=Temporary I=Internship
     *   f_WT Modalidad: 1=Presencial 2=Remoto 3=Hibrido
     * null desactiva el filtro.
     */
    private val nivelesExperiencia: String? = "2",
    private val tipoContrato: String? = "F,C",
    private val modalidad: String? = null,
) : Closeable {

    private companion object {
        const val TAG = "JobAI_Scraper"
        const val GUEST_SEARCH =
            "https://www.linkedin.com/jobs-guest/jobs/api/seeMoreJobPostings/search"
        const val GUEST_DETAIL = "https://www.linkedin.com/jobs-guest/jobs/api/jobPosting"
        const val REFERER = "https://www.linkedin.com/jobs/search"

        const val DEFAULT_QUERY =
            "(analista OR asistente) AND (\"ingeniería industrial\" OR \"ingenieria industrial\" OR \"ingeniero industrial\" OR \"ingeniera industrial\" OR \"ing industrial\" OR \"ing. industrial\" OR \"industrial engineering\")"

        const val TAM_PAGINA = 10
        const val START_MAXIMO = 200
        const val MAX_DESCRIPCION = 6_000

        /** LinkedIn devuelve 999 ("Request denied") cuando decide que sos un bot. */
        const val HTTP_DENEGADO = 999

        /**
         * Un listado sin resultados devuelve un cuerpo diminuto. Si viene un HTML
         * grande y aun asi no hay cards, es una pagina de challenge o de login:
         * eso es un bloqueo, no el final de la paginacion.
         */
        const val UMBRAL_SOFT_BLOCK = 1_500
    }

    /**
     * Timeouts coherentes entre si: antes callTimeout(10) era menor que
     * connect(5) + read(6) = 11, asi que cortaba respuestas legitimas y
     * anulaba el motivo por el que se habia subido el readTimeout.
     */
    private val http = client.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    // El listado tolera muy poca concurrencia; el detalle un poco mas.
    private val gateBusqueda = AdaptiveGate(nombre = "LinkedIn-busqueda", inicial = 1, maxPermisos = 2)
    private val gateDetalle = AdaptiveGate(nombre = "LinkedIn-detalle", inicial = 2, maxPermisos = 6)
    private val limiter = RateLimiter(requestsPorSegundo = 1.0)

    private val enVuelo = ConcurrentHashMap<String, Deferred<String>>()
    private val cacheDescripciones = LruCache<String, String>(120)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ------------------------------------------------------------------
    // Resultado de una pagina del listado
    // ------------------------------------------------------------------

    private sealed interface Pagina {
        data class Ok(val jobs: List<JobOffer>) : Pagina
        object Vacia : Pagina
        data class Bloqueado(val codigo: Int, val retryAfter: Long?) : Pagina
        data class Error(val motivo: String) : Pagina
    }

    private class Respuesta(
        val codigo: Int,
        val retryAfter: Long?,
        val body: String?,
    )

    // ------------------------------------------------------------------
    // Listado
    // ------------------------------------------------------------------

    /**
     * f_TPR=r2592000 = publicadas en los ultimos 30 dias.
     *
     * Recorre las paginas de una en una y corta en cuanto no hay resultados
     * nuevos, hay bloqueo o hay error. Con el limiter a 1 rps, la version
     * anterior gastaba ~20 s por query aunque hubiera 12 ofertas.
     */
    suspend fun scrapeQuery(
        query: String = DEFAULT_QUERY,
        maxDias: Int = 30,
        maxPaginas: Int = START_MAXIMO / TAM_PAGINA,
    ): List<JobOffer> {
        val segundos = maxDias * 86_400
        val vistos = HashSet<String>()
        val resultado = mutableListOf<JobOffer>()

        var start = 0
        var pagina = 0
        var motivoCorte = "sin mas paginas"

        while (pagina < maxPaginas && start < START_MAXIMO) {
            val r = gateBusqueda.withPermit {
                limiter.acquire()
                fetchOffset(query, start, segundos)
            }

            when (r) {
                is Pagina.Ok -> {
                    gateBusqueda.exito()
                    var nuevas = 0
                    for (job in r.jobs) {
                        val clave = extractJobId(job.url) ?: job.url
                        if (vistos.add(clave)) {
                            resultado += job
                            nuevas++
                        }
                    }
                    // LinkedIn a veces reenvia la primera pagina cuando start
                    // se pasa del total real: si no aporta nada nuevo, corto.
                    if (nuevas == 0) {
                        motivoCorte = "pagina repetida en start=$start"
                        break
                    }
                }

                Pagina.Vacia -> {
                    gateBusqueda.exito()
                    motivoCorte = "pagina vacia en start=$start"
                    break
                }

                is Pagina.Bloqueado -> {
                    gateBusqueda.bloqueo(r.retryAfter)
                    motivoCorte = "bloqueo HTTP ${r.codigo} en start=$start"
                    break
                }

                is Pagina.Error -> {
                    motivoCorte = "error en start=$start: ${r.motivo}"
                    break
                }
            }

            start += TAM_PAGINA
            pagina++
        }

        Log.d(TAG, "LinkedIn '$query': ${resultado.size} ofertas en ${pagina + 1} pag ($motivoCorte)")
        return resultado
    }

    private suspend fun fetchOffset(query: String, start: Int, segundos: Int): Pagina {
        val url = buildString {
            append(GUEST_SEARCH)
            append("?keywords=").append(enc(query))
            if (geoId.isNullOrBlank()) {
                append("&location=").append(enc(ubicacionBusqueda))
            } else {
                append("&geoId=").append(enc(geoId))
            }
            append("&f_TPR=r").append(segundos)
            nivelesExperiencia?.let { append("&f_E=").append(enc(it)) }
            tipoContrato?.let { append("&f_JT=").append(enc(it)) }   // la coma va como %2C
            modalidad?.let { append("&f_WT=").append(enc(it)) }
            append("&sortBy=DD")               // por fecha descendente
            append("&start=").append(start)
        }

        // Esto es un XHR, no una navegacion: los Sec-Fetch van en modo cors.
        val req = NetFingerprint.aplicarBase(Request.Builder().url(url), REFERER)
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.9")
            .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "same-origin")
            .build()

        return try {
            val resp = ejecutarConReintento(req)
            val cuerpo = resp.body

            when {
                esBloqueo(resp.codigo) ->
                    Pagina.Bloqueado(resp.codigo, resp.retryAfter)

                cuerpo == null ->
                    Pagina.Error("HTTP ${resp.codigo}")

                cuerpo.isBlank() ->
                    Pagina.Vacia

                else -> {
                    val jobs = parseHtml(cuerpo)
                    when {
                        jobs.isNotEmpty() -> Pagina.Ok(jobs)
                        cuerpo.length > UMBRAL_SOFT_BLOCK ->
                            Pagina.Bloqueado(resp.codigo, null)   // challenge / login disfrazado de 200
                        else -> Pagina.Vacia
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e                                   // no romper la cancelacion estructurada
        } catch (e: Exception) {
            Pagina.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    // ------------------------------------------------------------------
    // Parseo del listado
    // ------------------------------------------------------------------

    private fun parseHtml(html: String): List<JobOffer> {
        val doc = Jsoup.parse(html)
        // "li div.base-card" era subconjunto de "div.base-card": sobraba.
        val cards = doc.select("div.base-card, div.job-search-card")
        Log.d(TAG, "LI html len=${html.length} cards=${cards.size}")

        val jobs = mutableListOf<JobOffer>()
        for (card in cards) {
            parseCard(card)?.let { jobs.add(it) }
        }
        return jobs
    }

    private fun parseCard(card: Element): JobOffer? {
        val title = card.selectFirst("h3.base-search-card__title, h3.job-search-card__title")
            ?.text()?.trim().orEmpty()
        if (title.isBlank()) return null

        var href = card
            .selectFirst("a.base-card__full-link, a.job-search-card__link, a[href*=/jobs/view/]")
            ?.attr("href").orEmpty()
            .substringBefore("?")
            .substringBefore("#")
        if (href.isBlank()) {
            val id = card.attr("data-entity-urn").substringAfterLast(":")
            if (id.isBlank()) return null
            href = "https://www.linkedin.com/jobs/view/$id"
        }

        val empresa = card.selectFirst("h4.base-search-card__subtitle, a.hidden-nested-link")
            ?.text()?.trim().orEmpty()
        val ubicacion = card.selectFirst("span.job-search-card__location, .base-search-card__metadata span")
            ?.text()?.trim().orEmpty()

        val time = card.selectFirst("time")
        // El atributo datetime viene como yyyy-MM-dd. Si falta, queda el texto
        // relativo ("hace 2 semanas") y de eso se ocupa DateUtils.diasDesde.
        val fecha = time?.attr("datetime").orEmpty()
            .ifBlank { time?.text()?.trim().orEmpty() }

        val textoCompleto = card.text()
        val areaFinal = AreaClassifier.clasificarArea(title, textoCompleto.lowercase())

        return JobOffer(
            portal = "LinkedIn",
            puesto = title,
            empresa = empresa.ifBlank { "Confidencial" },
            ubicacion = ubicacion.ifBlank { "Lima" },
            distrito = ubicacion.substringBefore(",").trim().ifBlank { "Lima" },
            modalidad = detectarModalidad(title, ubicacion),
            area = areaFinal,
            url = href,
            fechaPublicacion = fecha
        )
    }

    /**
     * Solo mira titulo y ubicacion, no el texto entero de la card.
     * LinkedIn pone la modalidad en la ubicacion ("Peru (Remote)") o en el
     * titulo; buscarla en todo el texto daba falsos positivos con frases como
     * "no remoto" o "remoto ocasional" que aparecen en el snippet.
     * Lo definitivo es jobLocationType del JSON-LD del detalle.
     */
    private fun detectarModalidad(titulo: String, ubicacion: String): String {
        val t = sinTildes("$titulo $ubicacion".lowercase())
        return when {
            Regex("\\b(remote|remoto|remota|teletrabajo|home\\s?office)\\b").containsMatchIn(t) -> "Remoto"
            Regex("\\b(hybrid|hibrido|hibrida|semi\\s?presencial)\\b").containsMatchIn(t) -> "Hibrido"
            else -> "Presencial"
        }
    }

    // ------------------------------------------------------------------
    // Detalle
    // ------------------------------------------------------------------

    /**
     * El ritmo del detalle lo controla el scraper, no quien lo llama.
     *
     * Deduplica peticiones en vuelo y ademas cachea el resultado: antes la
     * entrada de enVuelo se borraba al terminar el primer await, asi que un
     * tercer llamador volvia a descargar lo mismo.
     */
    suspend fun fetchFullDescription(url: String): String {
        val id = extractJobId(url) ?: run {
            Log.w(TAG, "LinkedIn sin id: $url")
            return ""
        }

        cacheDescripciones.get(id)?.let { return it }

        // LAZY: si arrancara eager, la corrutina podria completar y tocar el mapa
        // mientras ConcurrentHashMap todavia tiene tomado el bin de la clave.
        val nuevo = scope.async(start = CoroutineStart.LAZY) {
            val texto = descargarConControl(id)
            if (texto.isNotBlank()) cacheDescripciones.put(id, texto)
            texto
        }

        val d = enVuelo.putIfAbsent(id, nuevo) ?: nuevo
        if (d !== nuevo) {
            nuevo.cancel()
        } else {
            d.invokeOnCompletion { enVuelo.remove(id, d) }
        }

        return try {
            d.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "LinkedIn detalle fallo id=$id: ${e.message}")
            ""
        }
    }

    private suspend fun descargarConControl(id: String): String = gateDetalle.withPermit {
        limiter.acquire()
        descargarDetalle(id)
    }

    private suspend fun descargarDetalle(id: String): String {
        val req = NetFingerprint.aplicarBase(
            Request.Builder().url("$GUEST_DETAIL/$id"),
            REFERER
        )
            .header("Accept", "text/html,*/*;q=0.8")
            .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "same-origin")
            .build()

        return try {
            val resp = ejecutarConReintento(req)

            if (esBloqueo(resp.codigo)) {
                gateDetalle.bloqueo(resp.retryAfter)
                Log.w(TAG, "LinkedIn detalle HTTP ${resp.codigo} id=$id")
                return ""
            }
            val html = resp.body
            if (html.isNullOrBlank()) {
                if (resp.codigo != 404) Log.w(TAG, "LinkedIn detalle HTTP ${resp.codigo} id=$id")
                return ""
            }
            gateDetalle.exito()

            val doc = Jsoup.parse(html)

            JobPostingJsonLd.extraer(doc)?.let { return it.descripcion.take(MAX_DESCRIPCION) }

            doc.selectFirst(
                "div.show-more-less-html__markup, div.description__text, " +
                    "section.description, .decorated-job-posting__details"
            )?.text()?.trim()?.take(MAX_DESCRIPCION).orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "LinkedIn detalle fallo id=$id: ${e.message}")
            ""
        }
    }

    /** Lo usa el ScraperEngine para no encolar detalles durante un bloqueo. */
    suspend fun esperarSiBloqueado() = gateDetalle.esperarSiBloqueado()

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    private suspend fun ejecutarConReintento(req: Request, intentos: Int = 2): Respuesta {
        var ultimo: IOException? = null
        repeat(intentos) { i ->
            try {
                return ejecutar(req)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                ultimo = e
                if (i < intentos - 1) delay(600L * (i + 1))
            }
        }
        throw ultimo ?: IOException("fallo desconocido")
    }

    /**
     * enqueue en vez de execute: execute() no es cancelable, asi que al salir
     * de la pantalla la peticion seguia viva hasta agotar el timeout.
     */
    private suspend fun ejecutar(req: Request): Respuesta =
        suspendCancellableCoroutine { cont ->
            val call = http.newCall(req)
            cont.invokeOnCancellation { runCatching { call.cancel() } }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val r = try {
                        response.use { resp ->
                            Respuesta(
                                codigo = resp.code,
                                retryAfter = leerRetryAfter(resp),
                                body = if (resp.isSuccessful) resp.body?.string() else null
                            )
                        }
                    } catch (e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                        return
                    }
                    if (cont.isActive) cont.resume(r)
                }
            })
        }

    /** Retry-After puede venir en segundos o como fecha HTTP; antes solo se leia el primero. */
    private fun leerRetryAfter(resp: Response): Long? {
        resp.header("Retry-After")?.trim()?.toLongOrNull()?.let { return it }
        val fecha = resp.headers.getDate("Retry-After") ?: return null
        val segundos = (fecha.time - System.currentTimeMillis()) / 1000
        return if (segundos > 0) segundos else null
    }

    private fun esBloqueo(codigo: Int) =
        codigo == 429 || codigo == 403 || codigo == HTTP_DENEGADO

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun sinTildes(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")

    private fun extractJobId(url: String): String? {
        Regex("currentJobId=(\\d+)").find(url)?.let { return it.groupValues[1] }
        Regex("/jobs/view/(\\d+)").find(url)?.let { return it.groupValues[1] }
        Regex("-(\\d{8,})").find(url)?.let { return it.groupValues[1] }
        return null
    }

    /** Llamalo cuando destruyas el ScraperEngine: el scope no se cancelaba solo. */
    override fun close() {
        scope.cancel()
        enVuelo.clear()
        cacheDescripciones.evictAll()
    }
}
