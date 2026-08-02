package com.jobai.hunter.data.net

import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.TlsVersion

/**
 * Lo que SI se puede hacer desde OkHttp para parecerse a Chrome, y lo que no.
 *
 * SE PUEDE:
 *  - orden y seleccion de cipher suites (parte del JA3)
 *  - versiones de TLS ofrecidas
 *  - ALPN (OkHttp lo maneja segun los protocolos configurados)
 *  - orden de las cabeceras HTTP (fingerprint de capa 7, muy usado)
 *
 * NO SE PUEDE sin cambiar de cliente HTTP:
 *  - GREASE, orden de extensiones TLS, supported_groups, signature_algorithms
 *  - el fingerprint HTTP/2 (orden del SETTINGS frame, WINDOW_UPDATE, pseudo-headers)
 *
 * Si un portal bloquea por JA3/JA4 puro, la unica salida real en Android es
 * usar el stack de Chromium: Cronet (org.chromium.net:cronet-embedded) o un
 * WebView headless. Esto de aca cubre la parte portable.
 */
object NetFingerprint {

    /**
     * Lista de cipher suites en el orden en que las ofrece Chrome desktop.
     * OkHttp descarta en silencio las que la plataforma no soporte, asi que
     * es seguro declararlas todas.
     */
    private val CHROME_CIPHERS = listOf(
        CipherSuite.TLS_AES_128_GCM_SHA256,
        CipherSuite.TLS_AES_256_GCM_SHA384,
        CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
        CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_RSA_WITH_AES_256_GCM_SHA384,
        CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
        CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA
    )

    /** Pasar a OkHttpClient.Builder().connectionSpecs(NetFingerprint.connectionSpecs()) */
    fun connectionSpecs(): List<ConnectionSpec> = listOf(
        ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
            .cipherSuites(*CHROME_CIPHERS.toTypedArray())
            .supportsTlsExtensions(true)
            .build(),
        ConnectionSpec.CLEARTEXT
    )

    val UA_CHROME =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    /**
     * Orden en que Chrome emite las cabeceras. Las que no aparezcan en esta
     * lista van al final, en el orden en que se agregaron.
     *
     * OJO: 'accept-encoding' NO esta y no debe estarlo. Si se setea a mano,
     * OkHttp deja de descomprimir de forma transparente y el body llega binario.
     */
    private val ORDEN_CHROME = listOf(
        "host",
        "connection",
        "sec-ch-ua",
        "sec-ch-ua-mobile",
        "sec-ch-ua-platform",
        "upgrade-insecure-requests",
        "user-agent",
        "accept",
        "x-requested-with",
        "origin",
        "referer",
        "sec-fetch-site",
        "sec-fetch-mode",
        "sec-fetch-user",
        "sec-fetch-dest",
        "accept-language",
        "cookie"
    )

    /**
     * Reordena las cabeceras que ya trae el request. NO agrega ni quita ninguna:
     * cada scraper decide cuales manda. Registrar como addInterceptor (de
     * aplicacion), para que corra antes del BridgeInterceptor de OkHttp.
     */
    class ChromeHeaderOrderInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val actuales = req.headers

            val builder = Headers.Builder()
            val yaPuestas = HashSet<String>()

            for (nombre in ORDEN_CHROME) {
                for (i in 0 until actuales.size) {
                    if (actuales.name(i).equals(nombre, ignoreCase = true)) {
                        builder.add(actuales.name(i), actuales.value(i))
                        yaPuestas.add(actuales.name(i).lowercase())
                    }
                }
            }
            for (i in 0 until actuales.size) {
                if (actuales.name(i).lowercase() !in yaPuestas) {
                    builder.add(actuales.name(i), actuales.value(i))
                }
            }

            return chain.proceed(req.newBuilder().headers(builder.build()).build())
        }
    }

    /**
     * Cabeceras base de navegacion de documento. Cada scraper puede pisar
     * los Sec-Fetch-* segun sea navegacion (document/navigate) o XHR (empty/cors).
     */
    fun aplicarBase(builder: okhttp3.Request.Builder, referer: String?): okhttp3.Request.Builder {
        builder.header("User-Agent", UA_CHROME)
            .header("sec-ch-ua", "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"126\", \"Google Chrome\";v=\"126\"")
            .header("sec-ch-ua-mobile", "?0")
            .header("sec-ch-ua-platform", "\"Windows\"")
        if (referer != null) builder.header("Referer", referer)
        return builder
    }
}
