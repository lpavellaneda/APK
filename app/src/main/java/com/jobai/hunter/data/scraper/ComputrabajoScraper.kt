package com.jobai.hunter.data.scraper

import android.util.Log
import com.jobai.hunter.data.model.JobOffer
import com.jobai.hunter.data.net.AdaptiveGate
import com.jobai.hunter.data.net.NetFingerprint
import com.jobai.hunter.data.net.RateLimiter
import com.jobai.hunter.domain.DateUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap

class ComputrabajoScraper(private val client: OkHttpClient) {

    private val base = "https://pe.computrabajo.com"

    // Un solo control de ritmo para TODO el portal: listados y detalles.
    private val gate = AdaptiveGate(nombre = "Computrabajo", inicial = 2, maxPermisos = 6)
    private val limiter = RateLimiter(requestsPorSegundo = 2.0)

    // La misma URL de detalle puede pedirse desde dos queries distintas.
    private val enVuelo = ConcurrentHashMap<String, Deferred<String>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var calentado = false

    /**
     * Primer GET a la home para conseguir __cf_bm antes de tocar los listados.
     * Sin esto la primera peticion del scraper aterriza en /trabajo-de-analista
     * sin cookie de Cloudflare: exactamente el patron de un visitante artificial.
     */
    suspend fun warmup() {
        // No-op: los listados directos no requieren GET inicial a la home.
    }

    suspend fun scrapeSlug(slug: String, maxDias: Int = 30): List<JobOffer> = coroutineScope {
        val list = mutableListOf<JobOffer>()
        val maxPaginas = 15
        val lote = 2
        var page = 1

        while (page <= maxPaginas) {

            val hasta = minOf(page + lote - 1, maxPaginas)
            val paginas = (page..hasta).map { p ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        limiter.acquire()
                        p to fetchPage(slug, p)
                    }
                }
            }.awaitAll()

            var cortar = false
            for ((p, pageOffers) in paginas) {
                if (pageOffers.isEmpty()) { cortar = true; break }

                var ultimaFecha: Int? = null
                for (o in pageOffers) {
                    val dias = DateUtils.diasDesde(o.fechaPublicacion)
                    if (dias != null) ultimaFecha = dias
                    if (dias == null || dias <= maxDias) list.add(o)
                }

                if (ultimaFecha != null && ultimaFecha > maxDias) {
                    Log.d("JobAI_Scraper", "Computrabajo '$slug': limite de $maxDias dias en p$p")
                    cortar = true
                    break
                }
            }
            if (cortar) break
            page = hasta + 1
        }
        Log.d("JobAI_Scraper", "Computrabajo '$slug': ${list.size} ofertas")
        list
    }

    private suspend fun fetchPage(slug: String, page: Int): List<JobOffer> {
        val cleanSlug = slug.trim().lowercase().replace(Regex("\\s+"), "-")
        val url = if (page == 1) "$base/trabajo-de-$cleanSlug-en-lima?ex=2" else "$base/trabajo-de-$cleanSlug-en-lima?p=$page&ex=2"
        val referer = if (page <= 2) "$base/" else "$base/trabajo-de-$cleanSlug-en-lima?p=${page - 1}&ex=2"

        val req = NetFingerprint.aplicarBase(Request.Builder().url(url), referer)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "es-PE,es;q=0.9")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Sec-Fetch-User", "?1")
            .header("Upgrade-Insecure-Requests", "1")
            .build()

        return try {
            val (codigo, html) = withContext(Dispatchers.IO) {
                client.newCall(req).execute().use { resp ->
                    resp.code to (if (resp.isSuccessful) resp.body?.string() else null)
                }
            }
            when {
                codigo == 403 || codigo == 429 -> {
                    gate.bloqueo(null)
                    Log.w("JobAI_Scraper", "Computrabajo '$slug' p=$page HTTP $codigo (bloqueo)")
                    emptyList()
                }
                html == null -> {
                    Log.w("JobAI_Scraper", "Computrabajo '$slug' p=$page HTTP $codigo")
                    emptyList()
                }
                else -> {
                    gate.exito()
                    parseHtml(html)
                }
            }
        } catch (e: Exception) {
            Log.w("JobAI_Scraper", "Computrabajo '$slug' p=$page fallo: ${e.message}")
            emptyList()
        }
    }

    private fun parseHtml(html: String): List<JobOffer> {
        val doc = Jsoup.parse(html)
        val cards = doc.select("article.box_offer, article[data-jobid], div.box_offer")
        Log.d("JobAI_Scraper", "CT html len=${html.length} title=${doc.title()} cards=${cards.size}")
        val jobs = mutableListOf<JobOffer>()

        for (card in cards) {
            val a = card.selectFirst("h2 a[href], h1 a[href], a.js-o-link") ?: continue
            val title = a.text().replace("Postulado", "").replace("Vista", "").trim()
            if (title.isBlank()) continue

            var href = a.attr("href")
            if (href.isBlank()) continue
            if (!href.startsWith("http")) href = base + href

            val empresa = card.selectFirst("a[offer-grid-article-company-url], a[href*=/empresas/], p.dFlex a")
                ?.text()?.trim().orEmpty()

            val ubicacion = extraerUbicacion(card, empresa)
            val fecha = extraerFecha(card)
            val salario = card.selectFirst("span[title*=Salario], .i_salary")?.parent()?.text()?.trim()
                ?: "No especificado"

            val snippet = card.select("p").map { it.text().trim() }
                .filter { it.length > 30 && !it.contains("Hace", true) && !it.contains("S/", true) && !it.contains(",") }
                .joinToString(" ")

            val cardTxt = (title + " " + snippet + " " + card.text()).lowercase()
            val modalidad = when {
                cardTxt.contains("remot") || cardTxt.contains("teletrabajo") -> "Remoto"
                cardTxt.contains("hibrid") || cardTxt.contains("hybrid") -> "Hibrido"
                else -> "Presencial"
            }

            val areaCategory = card.selectFirst("span.tag, a[href*=/empleos-de-]")?.text()?.trim().orEmpty()
            val areaFinal = com.jobai.hunter.domain.AreaClassifier.clasificarArea(title, snippet, areaCategory)

            jobs.add(
                JobOffer(
                    portal = "Computrabajo",
                    puesto = title,
                    empresa = empresa.ifBlank { "Confidencial" },
                    ubicacion = ubicacion.ifBlank { "Lima" },
                    distrito = ubicacion.substringBefore(",").trim().ifBlank { "Lima" },
                    modalidad = modalidad,
                    salario = salario,
                    area = areaFinal,
                    url = href,
                    fechaPublicacion = fecha,
                    descripcion = snippet
                )
            )
        }
        return jobs
    }

    // "4,1 Manpower" es la valoracion de la empresa, no una ubicacion.
    private val RATING = Regex("^\\d+[.,]\\d+\\b")

    /** La ubicacion es el <p> que tiene coma y no es la fecha, el salario ni el rating. */
    private fun extraerUbicacion(card: Element, empresa: String): String {
        val candidatos = card.select("p, span")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() && it.length < 70 }
        return candidatos.firstOrNull {
            it.contains(",") &&
                !RATING.containsMatchIn(it) &&
                !(empresa.isNotBlank() && it.contains(empresa, true)) &&
                !it.contains("Hace", true) &&
                !it.contains("Ayer", true) &&
                !it.contains("Hoy", true) &&
                !it.contains("S/", true) &&
                !it.contains("valoracion", true) &&
                !it.contains("postul", true)
        }.orEmpty()
    }

    private fun extraerFecha(card: Element): String {
        card.selectFirst("time")?.let { t ->
            val dt = t.attr("datetime")
            if (dt.isNotBlank()) return dt
            if (t.text().isNotBlank()) return t.text().trim()
        }
        val textos = card.select("p.fc_aux, span.fc_aux, p.fs13").map { it.text().trim() }
        return textos.firstOrNull {
            it.contains("Hace", true) || it.contains("Hoy", true) ||
                it.contains("Ayer", true) || it.contains("dia", true) ||
                it.contains("mes", true) || it.contains("semana", true)
        }.orEmpty()
    }

    /**
     * Deduplica peticiones en vuelo: si dos queries piden la misma oferta,
     * se descarga una sola vez y las dos esperan el mismo resultado.
     */
    suspend fun fetchFullDescription(url: String): String {
        if (url.isBlank()) return ""
        val d = enVuelo.computeIfAbsent(url) { scope.async { descargarConControl(url) } }
        return try {
            d.await()
        } finally {
            enVuelo.remove(url, d)
        }
    }

    private suspend fun descargarConControl(url: String): String = gate.withPermit {
        limiter.acquire()
        descargarDetalle(url)
    }

    private suspend fun descargarDetalle(url: String): String {
        val req = NetFingerprint.aplicarBase(Request.Builder().url(url), "$base/")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "es-PE,es;q=0.9")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Sec-Fetch-User", "?1")
            .header("Upgrade-Insecure-Requests", "1")
            .build()

        return try {
            val (codigo, html) = withContext(Dispatchers.IO) {
                client.newCall(req).execute().use { resp ->
                    resp.code to (if (resp.isSuccessful) resp.body?.string() else null)
                }
            }

            if (codigo == 403 || codigo == 429) {
                gate.bloqueo(null)
                Log.w("JobAI_Scraper", "CT detalle HTTP $codigo (bloqueo) $url")
                return ""
            }
            if (html == null) {
                Log.w("JobAI_Scraper", "CT detalle HTTP $codigo $url")
                return ""
            }
            gate.exito()

            val doc = Jsoup.parse(html)

            // 1) JSON-LD: delimitado por el portal, sin sidebar ni footer.
            JobPostingJsonLd.extraer(doc)?.let { ld ->
                return ld.descripcion.take(6000)
            }

            // 2) Fallback por selectores, por si el portal deja de emitir el JSON-LD.
            val el = doc.select("div.bWord, div.box_detail, section.box_detail, div[class*=description]")
                .maxByOrNull { it.text().length }
            val texto = el?.text()?.trim().orEmpty()

            if (texto.length < 200) {
                Log.w(
                    "JobAI_Scraper",
                    "CT detalle sin ld+json y corto len=${texto.length} " +
                        "sel=${el?.tagName()}.${el?.className()} title=${doc.title()} txt=[$texto]"
                )
            }
            texto.take(6000)
        } catch (e: Exception) {
            Log.w("JobAI_Scraper", "Computrabajo detalle fallo $url: ${e.message}")
            ""
        }
    }
}
