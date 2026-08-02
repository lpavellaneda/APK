package com.jobai.hunter.data.scraper

import com.jobai.hunter.data.model.JobOffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

class ConvocatoriasTrabajoScraper(private val client: OkHttpClient) {
    suspend fun scrapePublicJobs(): List<JobOffer> = withContext(Dispatchers.IO) {
        val list = mutableListOf<JobOffer>()
        try {
            val url = "https://www.convocatoriasdetrabajo.com/ofertas-de-empleo-en-INGENIERIA-INDUSTRIAL-15.html"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36")
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val html = resp.body?.string() ?: ""
                    val doc = Jsoup.parse(html)
                    val items = doc.select("article.convocatoria, div.convocatoria")
                    for (item in items) {
                        val a = item.selectFirst("h4 a, h3 a")
                        if (a != null) {
                            val title = a.text().trim()
                            var href = a.attr("href")
                            if (!href.startsWith("http")) {
                                href = "https://www.convocatoriasdetrabajo.com" + href
                            }
                            list.add(JobOffer(
                                portal = "Estado / CAS",
                                puesto = title,
                                empresa = "Sector Público / CAS",
                                ubicacion = "Lima",
                                url = href
                            ))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list
    }
}
