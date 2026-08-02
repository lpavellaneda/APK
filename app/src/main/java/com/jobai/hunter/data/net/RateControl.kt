package com.jobai.hunter.data.net

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Token bucket global por portal.
 *
 * Un delay aleatorio dentro de cada corrutina NO controla la tasa: con 4
 * permisos en paralelo y delay de 350-900ms terminas cerca de 6 req/s.
 * Esto fija el techo real independientemente de cuantas corrutinas haya.
 */
class RateLimiter(requestsPorSegundo: Double, private val jitterMs: Int = 250) {
    private val intervaloMs = (1000.0 / requestsPorSegundo).toLong()
    private val mutex = Mutex()
    private var siguiente = 0L

    suspend fun acquire() {
        val esperar = mutex.withLock {
            val ahora = System.currentTimeMillis()
            val t = max(ahora, siguiente)
            siguiente = t + intervaloMs
            t - ahora
        }
        // El jitter va fuera del lock: si no, serializa a todos contra el reloj.
        val extra = if (jitterMs > 0) (0..jitterMs).random().toLong() else 0L
        if (esperar + extra > 0) delay(esperar + extra)
    }
}

/**
 * Concurrencia adaptativa (AIMD, el mismo esquema que TCP).
 *
 * Sube de a uno cada [exitosParaSubir] respuestas buenas y corta a la mitad
 * ante un 403/429.
 *
 * NO usa Semaphore a proposito. La version anterior hacia sem.acquire() dentro
 * del mutex para "estacionar" permisos, y eso se deadlockeaba: quien recibia el
 * 403 tomaba el mutex y esperaba un permiso, mientras los demas retenian sus
 * permisos esperando el mutex para reportar exito. Aca el limite es un simple
 * contador y nunca se espera nada con el lock tomado.
 */
class AdaptiveGate(
    private val nombre: String,
    private val minPermisos: Int = 1,
    private val maxPermisos: Int = 8,
    inicial: Int = 2,
    private val exitosParaSubir: Int = 15
) {
    private val mutex = Mutex()

    private var limite = inicial.coerceIn(minPermisos, maxPermisos)
    private var enVuelo = 0
    private var exitos = 0

    @Volatile private var bloqueadoHasta = 0L
    private var fallosSeguidos = 0

    val permisosActuales: Int get() = limite

    suspend fun <T> withPermit(block: suspend () -> T): T {
        adquirir()
        try {
            return block()
        } finally {
            mutex.withLock { enVuelo-- }
        }
    }

    private suspend fun adquirir() {
        while (true) {
            esperarSiBloqueado()
            val entro = mutex.withLock {
                if (enVuelo < limite) { enVuelo++; true } else false
            }
            if (entro) return
            delay(40)
        }
    }

    suspend fun esperarSiBloqueado() {
        while (true) {
            val falta = bloqueadoHasta - System.currentTimeMillis()
            if (falta <= 0) return
            delay(min(falta, 5_000L))
        }
    }

    /** Llamar tras cada respuesta 2xx. */
    suspend fun exito() {
        mutex.withLock {
            fallosSeguidos = 0
            exitos++
            if (exitos >= exitosParaSubir && limite < maxPermisos) {
                exitos = 0
                limite++
                Log.d("JobAI_Scraper", "$nombre: concurrencia -> $limite")
            }
        }
    }

    /**
     * Llamar ante 403/429. Corta la concurrencia a la mitad y aplica backoff
     * exponencial con jitter completo sobre [retryAfterSeg].
     */
    suspend fun bloqueo(retryAfterSeg: Long?) {
        val espera: Long
        val nuevoLimite: Int
        mutex.withLock {
            exitos = 0
            fallosSeguidos++
            limite = max(minPermisos, limite / 2)
            nuevoLimite = limite

            val base = retryAfterSeg ?: 30L
            val techo = (base * 2.0.pow((fallosSeguidos - 1).coerceAtMost(3))).toLong()
            espera = (1..max(2L, min(techo, 180L))).random()
            bloqueadoHasta = max(bloqueadoHasta, System.currentTimeMillis() + espera * 1000)
        }
        Log.w("JobAI_Scraper", "$nombre: bloqueo #$fallosSeguidos, concurrencia -> $nuevoLimite, pausa ${espera}s")
    }

    /** Corta el portal cuando ya no tiene sentido seguir insistiendo. */
    fun circuitoAbierto(): Boolean = fallosSeguidos >= 6
}
