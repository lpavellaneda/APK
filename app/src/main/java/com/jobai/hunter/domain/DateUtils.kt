package com.jobai.hunter.domain

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Utilidades de normalizacion de texto y de fechas.
 * No se usa java.time porque minSdk = 24 y no hay desugaring configurado.
 */
object Texto {
    private val ACENTOS = mapOf(
        'á' to 'a', 'à' to 'a', 'ä' to 'a', 'â' to 'a',
        'é' to 'e', 'è' to 'e', 'ë' to 'e', 'ê' to 'e',
        'í' to 'i', 'ì' to 'i', 'ï' to 'i', 'î' to 'i',
        'ó' to 'o', 'ò' to 'o', 'ö' to 'o', 'ô' to 'o',
        'ú' to 'u', 'ù' to 'u', 'ü' to 'u', 'û' to 'u',
        'ñ' to 'n', 'ç' to 'c'
    )

    /** minusculas + sin tildes + espacios colapsados */
    fun norm(s: String?): String {
        if (s.isNullOrBlank()) return ""
        val sb = StringBuilder(s.length)
        for (c in s.lowercase()) sb.append(ACENTOS[c] ?: c)
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    /** clave estable para empresas: text mining para unificar nombres variopintos */
    fun claveEmpresa(s: String?): String {
        val rawNorm = norm(s)
        var t = rawNorm.replace(".", "").replace(",", "").replace("-", "")
        
        // 1. Quitar sufijos legales comunes (sin grupo/servicios)
        val suffixes = Regex("\\b(s\\s?a\\s?c|sac|s\\s?a\\s?a|saa|s\\s?a|sa|s\\s?r\\s?l|srl|e\\s?i\\s?r\\s?l|eirl|ltda|inc|corp|peru|del peru|s de rl|company|cia|compania)\\b")
        t = t.replace(suffixes, " ")
        
        // 2. Quitar prefijos comunes que no aportan identidad unica
        val prefixes = Regex("\\b(corporacion|inversiones|distribuidora|comercial|industrias|empresa)\\b")
        t = t.replace(prefixes, " ")

        // 3. Quedarse solo con lo esencial: letras y numeros pegados
        val res = t.replace(Regex("[^a-z0-9]"), "")
        // Salvaguarda: si se limpio demasiado, volver a la cadena normalizada sin recortar
        return if (res.isBlank()) rawNorm.replace(Regex("[^a-z0-9]"), "") else res
    }

    /** clave estable para ubicaciones/sedes */
    fun claveUbicacion(s: String?): String {
        var t = norm(s).replace(".", "").replace(",", "")
        t = t.replace(Regex("\\b(peru|lima metropolitana|provincia de|distrito de|region)\\b"), " ")
        // IMPORTANTE: No quitar espacios aqui, se necesitan para detectarDistrito
        return t.replace(Regex("\\s+"), " ").trim()
    }
}

object DateUtils {
    private const val DAY_MS = 86_400_000L

    private val FORMATOS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd",
        "dd-MM-yyyy",
        "dd/MM/yyyy"
    )

    /**
     * Devuelve cuantos dias tiene la publicacion, o null si no se pudo interpretar.
     * Acepta ISO, epoch en milisegundos y fechas relativas en es/en ("hace 3 dias", "2 weeks ago").
     */
    fun diasDesde(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()

        s.toLongOrNull()?.let { millis ->
            if (millis > 1_000_000_000_000L) return ((System.currentTimeMillis() - millis) / DAY_MS).toInt()
            if (millis > 1_000_000_000L) return ((System.currentTimeMillis() / 1000 - millis) / 86_400L).toInt()
        }

        relativa(Texto.norm(s))?.let { return it }

        val candidatos = listOf(s, s.substringBefore(" "), s.substringBefore("T"))
        for (f in FORMATOS) {
            for (c in candidatos) {
                if (c.isBlank()) continue
                try {
                    val sdf = SimpleDateFormat(f, Locale.US)
                    sdf.isLenient = false
                    if (f.contains("XXX")) sdf.timeZone = TimeZone.getTimeZone("UTC")
                    val d = sdf.parse(c) ?: continue
                    val dias = ((System.currentTimeMillis() - d.time) / DAY_MS).toInt()
                    if (dias in -2..3650) return dias
                } catch (_: Exception) {
                }
            }
        }
        return null
    }

    /**
     * Epoch millis para poder ordenar por recencia. fechaPublicacion mezcla
     * ISO con texto relativo, asi que ordenar el String no significaba nada.
     * Granularidad de un dia; el desempate lo hace matchScore.
     */
    fun epochDe(raw: String?): Long? {
        val dias = diasDesde(raw) ?: return null
        return System.currentTimeMillis() - dias * DAY_MS
    }

    private fun relativa(t: String): Int? {
        if (t.contains("hoy") || t.contains("today") || t.contains("recien") ||
            t.contains("ahora") || t.contains("minuto") || t.contains("minute") ||
            t.contains("hora") || t.contains("hour")
        ) return 0
        if (t.contains("ayer") || t.contains("yesterday")) return 1

        val n = Regex("(\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return when {
            t.contains("dia") || t.contains("day") -> n
            t.contains("semana") || t.contains("week") -> n * 7
            t.contains("mes") || t.contains("month") -> n * 30
            t.contains("ano") || t.contains("year") -> n * 365
            else -> null
        }
    }
}
