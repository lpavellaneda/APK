package com.jobai.hunter.ui.main

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jobai.hunter.data.local.JobDatabase
import com.jobai.hunter.data.local.JobEntity
import com.jobai.hunter.data.local.toEntity
import com.jobai.hunter.domain.ScraperEngine
import com.jobai.hunter.domain.ScraperEvent
import com.jobai.hunter.ui.compose.JobUiModel
import com.jobai.hunter.ui.compose.toUiModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    /** Ya mapeadas para la vista: la tarjeta no calcula nada al dibujarse. */
    val offers: List<JobUiModel> = emptyList(),
    /** Cuántas hay ocultas por estado = "descartada", para poder deshacer. */
    val descartadas: Int = 0,
    val isScraping: Boolean = false,
    val progressMessage: String = "",
    val error: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val db = JobDatabase.getDatabase(application)
    private val dao = db.jobDao()
    private val engine = ScraperEngine(application.cacheDir)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // El mapeo (normalizar titulos, formatear salario, armar keywords)
            // se hace una vez y fuera del hilo principal. Antes cada tarjeta lo
            // repetia al entrar en pantalla y eso era el lag del scroll.
            dao.getAllOffers()
                .map { list -> list.map { it.toUiModel() } }
                .flowOn(Dispatchers.Default)
                .collect { tarjetas ->
                    Log.d("JobAI_UI", "Lista en DB: ${tarjetas.size} ofertas")
                    _uiState.update { it.copy(offers = tarjetas) }
                }
        }
        viewModelScope.launch {
            dao.contarDescartadas().collect { n ->
                _uiState.update { it.copy(descartadas = n) }
            }
        }
    }

    fun runScraper() {
        if (_uiState.value.isScraping) return

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(isScraping = true, progressMessage = "Iniciando busqueda...", error = null)
            }
            try {
                // Purga automática de vacantes en DB con más de 30 días (conservando las postuladas)
                val limite30DiasEpoch = (System.currentTimeMillis() / 1000L) - (30L * 24L * 3600L)
                dao.purgarAntiguas(limite30DiasEpoch)

                val cacheDesc = dao.getDescripciones().associate { it.url to it.descripcion }
                val urlsPrevias = dao.getUrls().toSet()
                val estados = dao.getEstados().associate { it.url to it.estado }

                val buffer = mutableListOf<JobEntity>()

                engine.runFullPipeline(cacheDesc).collect { event ->
                    when (event) {
                        is ScraperEvent.Status ->
                            _uiState.update { it.copy(progressMessage = event.message) }

                        is ScraperEvent.OfferFound -> {
                            val entidad = event.offer.toEntity().copy(
                                nuevo = !urlsPrevias.contains(event.offer.url),
                                estado = estados[event.offer.url] ?: "pendiente"
                            )
                            buffer.add(entidad)
                            if (buffer.size >= 15) {
                                dao.insertOffers(buffer.toList())
                                buffer.clear()
                            }
                        }

                        is ScraperEvent.Finished -> {
                            if (buffer.isNotEmpty()) {
                                dao.insertOffers(buffer.toList())
                                buffer.clear()
                            }
                            val msg = if (event.active == 0)
                                "Sin resultados. Descartadas: ${event.discarded}"
                            else
                                "Listo: ${event.active} vacantes · ${event.discarded} descartadas"
                            _uiState.update { it.copy(isScraping = false, progressMessage = msg) }
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isScraping = false, error = e.message, progressMessage = "Error: ${e.message}")
                }
            }
        }
    }

    fun togglePostulada(url: String, estadoActual: String) {
        val nuevo = if (estadoActual == "postulada") "pendiente" else "postulada"
        viewModelScope.launch(Dispatchers.IO) { dao.updateEstado(url, nuevo) }
    }

    fun markAsDescartada(url: String) {
        viewModelScope.launch(Dispatchers.IO) { dao.updateEstado(url, "descartada") }
    }

    fun restaurarDescartadas() {
        viewModelScope.launch(Dispatchers.IO) { dao.restaurarDescartadas() }
    }

    fun clearOffers() {
        viewModelScope.launch(Dispatchers.IO) { dao.deleteAll() }
    }
}
