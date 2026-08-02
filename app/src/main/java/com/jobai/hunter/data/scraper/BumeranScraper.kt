package com.jobai.hunter.data.scraper

import android.util.Log
import com.jobai.hunter.data.model.JobOffer
import com.jobai.hunter.domain.DateUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class BumeranScraper(private val client: OkHttpClient) {

    // 4 queries x 4 paginas en paralelo escriben aca desde extractAreaFacets.
    // Con un mutableMapOf una ConcurrentModificationException se comia la pagina
    // entera (el catch de fetchPage la registraba como "fallo").
    private val areaMap = ConcurrentHashMap<String, String>()

    /**
     * Recorre paginas hasta que la ultima oferta de la pagina supera maxDias.
     */
    suspend fun scrapeQuery(query: String, maxDias: Int = 30): List<JobOffer> = coroutineScope {
        val list = mutableListOf<JobOffer>()
        val maxPaginas = 15
        val lote = 4
        var page = 0

        // Se piden [lote] paginas a la vez y despues se evalua el corte por fecha.
        // Como mucho se bajan 3 paginas de mas, pero en paralelo: sale casi gratis.
        while (page < maxPaginas) {
            val hasta = minOf(page + lote, maxPaginas)
            val paginas = (page until hasta).map { p ->
                async(Dispatchers.IO) { p to fetchPage(query, p) }
            }.awaitAll()

            var cortar = false
            for ((p, items) in paginas) {
                if (items.isEmpty()) { cortar = true; break }

                var ultimaFechaConocida: Int? = null
                for (item in items) {
                    val offer = parseItem(item) ?: continue
                    val dias = DateUtils.diasDesde(offer.fechaPublicacion)
                    if (dias != null) ultimaFechaConocida = dias
                    if (dias == null || dias <= maxDias) list.add(offer)
                }

                if (ultimaFechaConocida != null && ultimaFechaConocida > maxDias) {
                    Log.d("JobAI_Scraper", "Bumeran '$query': limite de $maxDias dias alcanzado en p$p")
                    cortar = true
                    break
                }
            }
            if (cortar) break
            page = hasta
        }

        // Si se corto por maxPaginas y no por fecha, la ventana de 30 dias NO se
        // esta respetando: el aviso queda en el log para no confundir los numeros.
        if (page >= maxPaginas) {
            Log.d("JobAI_Scraper", "Bumeran '$query': tope de $maxPaginas paginas (corte por limite, no por fecha)")
        }
        Log.d("JobAI_Scraper", "Bumeran '$query': ${list.size} ofertas")
        list
    }

    private fun fetchPage(query: String, page: Int): List<JSONObject> {
        val url = "https://www.bumeran.com.pe/api/avisos/searchV2?pageSize=50&page=$page&sort=RECIENTES"
        val payload = JSONObject().apply {
            put("filtros", JSONArray())
            put("query", query)
            put("localidad", "en-lima")
            put("internacional", false)
        }.toString()

        val req = Request.Builder()
            .url(url)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "es-PE,es;q=0.9")
            .header("Referer", "https://www.bumeran.com.pe/")
            .header("Origin", "https://www.bumeran.com.pe")
            .header("x-site-id", "BMPE")
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("JobAI_Scraper", "Bumeran '$query' p=$page HTTP ${resp.code}")
                    return emptyList()
                }
                val body = resp.body?.string() ?: return emptyList()
                val json = JSONObject(body)
                extractAreaFacets(json)
                val content = json.optJSONArray("content") ?: return emptyList()
                val out = mutableListOf<JSONObject>()
                for (i in 0 until content.length()) out.add(content.getJSONObject(i))
                out
            }
        } catch (e: Exception) {
            Log.w("JobAI_Scraper", "Bumeran '$query' p=$page fallo: ${e.message}")
            emptyList()
        }
    }

    private fun extractAreaFacets(json: JSONObject) {
        val filters = json.optJSONArray("filters") ?: return
        for (i in 0 until filters.length()) {
            val f = filters.optJSONObject(i) ?: continue
            if (f.optString("type") == "area") {
                val facets = f.optJSONArray("facets") ?: continue
                for (j in 0 until facets.length()) {
                    val facet = facets.optJSONObject(j) ?: continue
                    val id = facet.optString("id")
                    val nombre = facet.optString("name")
                    if (id.isNotBlank() && nombre.isNotBlank()) areaMap[id] = nombre
                }
            }
        }
    }

    /** Bumeran devuelve unos campos como objeto y otros como string segun el endpoint. */
    private fun textoDe(obj: JSONObject, campo: String, subcampo: String = "nombre"): String {
        val v = obj.opt(campo) ?: return ""
        return when (v) {
            is JSONObject -> v.optString(subcampo, "")
            is String -> v
            else -> v.toString()
        }
    }

    private fun parseItem(item: JSONObject): JobOffer? {
        return try {
            val id = item.optString("id", "")
            val title = item.optString("titulo", "")
            if (id.isBlank() || title.isBlank()) return null

            val company = if (item.optBoolean("confidencial", false)) "Confidencial"
            else textoDe(item, "empresa").ifBlank { "Confidencial" }

            val locObj = item.optJSONObject("localizacion")
            val loc = locObj?.optString("nombre").orEmpty().ifBlank { textoDe(item, "localizacion") }
            val locSuperior = locObj?.optString("nombreSuperior").orEmpty()
            val ubicacion = listOf(loc, locSuperior).filter { it.isNotBlank() }.distinct().joinToString(", ")

            val rawMod = textoDe(item, "modalidadTrabajo") + " " + textoDe(item, "modalidad")
            val modNorm = com.jobai.hunter.domain.Texto.norm(rawMod)
            val modalidad = when {
                modNorm.contains("remot") || modNorm.contains("teletrabajo") -> "Remoto"
                modNorm.contains("hibr") || modNorm.contains("hybrid") || modNorm.contains("semi") -> "Hibrido"
                else -> "Presencial"
            }

            val salMin = item.optInt("salarioMinimo", 0)
            val salMax = item.optInt("salarioMaximo", 0)
            val salario = if (salMin > 0) "S/ $salMin - $salMax" else "No especificado"

            val fecha = listOf(
                item.optString("fechaHoraPublicacion", ""),
                item.optString("fechaPublicacion", ""),
                item.optString("fechaModificado", "")
            ).firstOrNull { it.isNotBlank() } ?: ""

            val slug = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            val rawDesc = listOf(
                item.optString("descripcion", ""),
                item.optString("detalle", ""),
                item.optString("resumen", ""),
                item.optString("descripcionBreve", ""),
                item.optJSONObject("fichaAviso")?.optString("descripcion", "").orEmpty()
            ).firstOrNull { it.isNotBlank() } ?: ""
            val detalle = rawDesc.replace(Regex("<[^>]+>"), " ").trim()

            val rawArea = item.optJSONObject("area")?.optString("nombre").orEmpty()
                .ifBlank { textoDe(item, "area") }
                .ifBlank { areaMap[item.optString("idArea", "")] ?: "" }
            val areaFinal = com.jobai.hunter.domain.AreaClassifier.clasificarArea(title, detalle, rawArea)

            JobOffer(
                portal = "Bumeran",
                puesto = title,
                empresa = company,
                ubicacion = ubicacion.ifBlank { "Lima" },
                distrito = loc.ifBlank { "Lima" },
                modalidad = modalidad,
                salario = salario,
                area = areaFinal,
                descripcion = detalle,
                url = "https://www.bumeran.com.pe/empleos/$slug-$id.html",
                fechaPublicacion = fecha
            )
        } catch (e: Exception) {
            null
        }
    }
}
