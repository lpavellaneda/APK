package com.jobai.hunter.data.scraper

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Los portales serios embeben schema.org/JobPosting en un
 * <script type="application/ld+json">. Ese bloque esta delimitado por el
 * propio portal: no arrastra nav, footer ni el sidebar de ofertas relacionadas,
 * que es lo que hacia que las descripciones midieran 6000 caracteres.
 *
 * Ademas trae estructurado lo que hoy se saca con regex del texto plano:
 * fecha exacta, empresa, ubicacion y a veces los meses de experiencia.
 */
data class JobPostingLd(
    val descripcion: String,
    val fechaPublicacion: String?,
    val empresa: String?,
    val ubicacion: String?,
    val mesesExperiencia: Int?
)

object JobPostingJsonLd {

    fun extraer(doc: Document): JobPostingLd? {
        for (script in doc.select("script[type=application/ld+json]")) {
            val raw = script.data().trim()
            if (raw.isEmpty()) continue
            val nodo = buscarJobPosting(raw) ?: continue
            val ld = mapear(nodo)
            if (ld != null) return ld
        }
        return null
    }

    /** El script puede ser un objeto, un array, o traer @graph adentro. */
    private fun buscarJobPosting(raw: String): JSONObject? {
        return try {
            when (raw.first()) {
                '[' -> enArray(JSONArray(raw))
                '{' -> {
                    val o = JSONObject(raw)
                    if (esJobPosting(o)) o
                    else o.optJSONArray("@graph")?.let { enArray(it) }
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun enArray(arr: JSONArray): JSONObject? {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (esJobPosting(o)) return o
        }
        return null
    }

    /** "@type" puede venir como string o como array de strings. */
    private fun esJobPosting(o: JSONObject): Boolean {
        when (val t = o.opt("@type")) {
            is String -> return t.equals("JobPosting", true)
            is JSONArray -> {
                for (i in 0 until t.length()) {
                    if (t.optString(i).equals("JobPosting", true)) return true
                }
            }
        }
        return false
    }

    private fun mapear(o: JSONObject): JobPostingLd? {
        // description viene con HTML escapado: Jsoup lo aplana bien.
        val descHtml = o.optString("description", "")
        val descripcion = if (descHtml.isBlank()) "" else Jsoup.parse(descHtml).text().trim()
        if (descripcion.isBlank()) return null

        val fecha = o.optString("datePosted", "").ifBlank { null }

        val empresa = o.optJSONObject("hiringOrganization")?.optString("name")?.ifBlank { null }

        val ubicacion = o.optJSONObject("jobLocation")
            ?.optJSONObject("address")
            ?.let { a ->
                listOf(
                    a.optString("addressLocality"),
                    a.optString("addressRegion")
                ).filter { it.isNotBlank() }.distinct().joinToString(", ")
            }?.ifBlank { null }

        // experienceRequirements: string libre, u OccupationalExperienceRequirements
        val meses = o.optJSONObject("experienceRequirements")
            ?.let { req ->
                when (val m = req.opt("monthsOfExperience")) {
                    is Int -> m
                    is String -> m.toIntOrNull()
                    is Number -> m.toInt()
                    else -> null
                }
            }

        return JobPostingLd(descripcion, fecha, empresa, ubicacion, meses)
    }
}
