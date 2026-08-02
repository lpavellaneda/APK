package com.jobai.hunter.ui.compose

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jobai.hunter.data.local.JobEntity
import com.jobai.hunter.data.local.TipoJornada
import com.jobai.hunter.data.local.beneficiosLista
import com.jobai.hunter.data.local.tipoJornada
import com.jobai.hunter.data.local.zonaEnum
import com.jobai.hunter.domain.Texto
import com.jobai.hunter.domain.Zona
import com.jobai.hunter.ui.main.MainViewModel
import com.jobai.hunter.ui.theme.JobHunterTheme
import com.jobai.hunter.ui.theme.LocalJobColors

private const val TAB_ESTANDAR = 0
private const val TAB_ATIPICO = 1

private val ORDENES = listOf("Mejor match", "Más recientes", "Menos experiencia")

/** El orden importa: es el orden en que se muestran los chips. */
private val ZONAS_FILTRO = listOf(
    Zona.LIMA_METROPOLITANA,
    Zona.CALLAO,
    Zona.LIMA_PROVINCIAS,
    Zona.OTRO_DEPARTAMENTO
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobHunterApp(viewModel: MainViewModel = viewModel()) {
    JobHunterTheme(darkTheme = true) {
        val jc = LocalJobColors.current
        val uiState by viewModel.uiState.collectAsState()
        val context = LocalContext.current

        var tab by remember { mutableStateOf(TAB_ESTANDAR) }
        // Conjunto vacío = sin filtrar. Evita el caso raro de "deseleccioné
        // todo y no queda nada" que obligaba a un parche en la versión anterior.
        var zonasSel by remember { mutableStateOf(emptySet<Zona>()) }
        var portalesSel by remember { mutableStateOf(emptySet<String>()) }
        var modalidadesSel by remember { mutableStateOf(emptySet<String>()) }
        var sortBy by remember { mutableStateOf(ORDENES[0]) }
        var hojaFiltros by remember { mutableStateOf(false) }

        fun abrir(offer: JobUiModel) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(offer.url))) }
        }

        val portalesDisponibles = remember(uiState.offers) {
            uiState.offers.map { it.portal }.distinct().sorted()
        }
        val conteoZona = remember(uiState.offers) {
            uiState.offers.groupingBy { it.zona }.eachCount()
        }

        // Filtros transversales antes de partir por régimen, para que los
        // contadores de las pestañas reflejen lo que se está viendo.
        val base = remember(uiState.offers, zonasSel, portalesSel, modalidadesSel) {
            // modalidadNorm viene precalculada; antes se normalizaba el texto
            // de cada oferta en cada pasada del filtro.
            val modalidadesNorm = modalidadesSel.map { Texto.norm(it) }
            uiState.offers.filter { o ->
                (zonasSel.isEmpty() || zonasSel.contains(o.zona)) &&
                    (portalesSel.isEmpty() || portalesSel.contains(o.portal)) &&
                    (modalidadesNorm.isEmpty() || modalidadesNorm.any { o.modalidadNorm.contains(it) })
            }
        }

        val atipicas = remember(base) { base.filter { it.jornada == TipoJornada.ATIPICO } }
        val estandar = remember(base) { base.filter { it.jornada != TipoJornada.ATIPICO } }

        val visibles = remember(tab, estandar, atipicas, sortBy) {
            val lista = if (tab == TAB_ATIPICO) atipicas else estandar
            when (sortBy) {
                "Más recientes" -> lista.sortedWith(
                    compareByDescending<JobUiModel> { it.fechaEpoch }.thenByDescending { it.matchScore }
                )
                // Las que no cuantifican van al final: no se sabe si exigen mucho.
                "Menos experiencia" -> lista.sortedWith(
                    compareBy<JobUiModel> { it.mesesExperiencia ?: Int.MAX_VALUE }
                        .thenByDescending { it.matchScore }
                )
                else -> lista.sortedByDescending { it.matchScore }
            }
        }

        val filtrosActivos = zonasSel.size + portalesSel.size + modalidadesSel.size

        Column(Modifier.fillMaxSize().background(jc.fondo)) {

            BarraSuperior(
                escaneando = uiState.isScraping,
                onBuscar = viewModel::runScraper,
                onBorrar = viewModel::clearOffers,
                onExportar = { exportOffersToCsv(context, (estandar + atipicas).map { it.offer }) }
            )

            if (uiState.isScraping) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = jc.acentoCian,
                    trackColor = jc.borde
                )
                Row(
                    modifier = Modifier.fillMaxWidth().background(jc.superficie)
                        .padding(vertical = 8.dp, horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(6.dp).background(jc.acentoCian, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        uiState.progressMessage,
                        style = MaterialTheme.typography.labelMedium,
                        color = jc.textoSuave,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // ---- Dos secciones: régimen estándar vs atípico ----
            TabRow(
                selectedTabIndex = tab,
                containerColor = jc.superficie,
                indicator = { pos ->
                    Box(
                        modifier = Modifier
                            .tabIndicatorOffset(pos[tab])
                            .height(3.dp)
                            .padding(horizontal = 24.dp)
                            .background(
                                if (tab == TAB_ATIPICO)
                                    androidx.compose.ui.graphics.SolidColor(jc.atipico)
                                else
                                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                                        com.jobai.hunter.ui.theme.GradientPalette.TabIndicator
                                    ),
                                CircleShape
                            )
                    )
                },
                divider = {}
            ) {
                Pestana(tab == TAB_ESTANDAR, "Régimen estándar", estandar.size, null, if (tab == TAB_ESTANDAR) jc.acento else jc.textoTenue) { tab = TAB_ESTANDAR }
                Pestana(tab == TAB_ATIPICO, "Régimen atípico", atipicas.size, null, if (tab == TAB_ATIPICO) jc.atipico else jc.textoTenue) { tab = TAB_ATIPICO }
            }

            HorizontalDivider(color = jc.borde, thickness = 1.dp)

            // ---- Zona: el filtro espacial que reemplaza al mapa ----
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, end = 16.dp),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                item {
                    Chip("Todo el país", zonasSel.isEmpty(), jc.textoSuave) { zonasSel = emptySet() }
                }
                items(ZONAS_FILTRO, key = { it.name }) { z ->
                    val n = conteoZona[z] ?: 0
                    if (n > 0) {
                        Chip(
                            texto = "${z.etiqueta} · $n",
                            activo = zonasSel.contains(z),
                            color = if (z == Zona.OTRO_DEPARTAMENTO) jc.atipico else jc.acento,
                            onClick = {
                                zonasSel = if (zonasSel.contains(z)) zonasSel - z else zonasSel + z
                            }
                        )
                    }
                }
                item { Spacer(Modifier.width(14.dp)) }
            }

            // ---- Contador + filtros + orden ----
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "${visibles.size}",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = jc.texto
                    )
                    Text(
                        text = if (visibles.size == 1) "vacante" else "vacantes",
                        style = MaterialTheme.typography.labelMedium,
                        color = jc.textoTenue
                    )
                }
                BotonIcono(Icons.Outlined.FilterList, "Filtros", filtrosActivos) { hojaFiltros = true }
                Spacer(Modifier.width(8.dp))
                MenuOrden(sortBy) { sortBy = it }
            }

            if (uiState.descartadas > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${uiState.descartadas} descartadas ocultas",
                        style = MaterialTheme.typography.labelSmall,
                        color = jc.textoTenue
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Restaurar",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = jc.acento,
                        modifier = Modifier.clickable { viewModel.restaurarDescartadas() }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            when {
                uiState.offers.isEmpty() -> EstadoVacio(uiState.isScraping, viewModel::runScraper)
                visibles.isEmpty() -> SinResultados(tab == TAB_ATIPICO, filtrosActivos > 0) {
                    zonasSel = emptySet(); portalesSel = emptySet(); modalidadesSel = emptySet()
                }
                else -> OfferList(
                    offers = visibles,
                    onVerOferta = ::abrir,
                    onTogglePostulada = { viewModel.togglePostulada(it.url, it.estado) },
                    onDescartar = viewModel::markAsDescartada
                )
            }
        }

        if (hojaFiltros) {
            HojaFiltros(
                portales = portalesDisponibles,
                portalesSel = portalesSel,
                modalidadesSel = modalidadesSel,
                onPortal = { p -> portalesSel = if (portalesSel.contains(p)) portalesSel - p else portalesSel + p },
                onModalidad = { m -> modalidadesSel = if (modalidadesSel.contains(m)) modalidadesSel - m else modalidadesSel + m },
                onLimpiar = { portalesSel = emptySet(); modalidadesSel = emptySet() },
                onCerrar = { hojaFiltros = false }
            )
        }
    }
}

// ===========================================================================
// Barra superior
// ===========================================================================
@Composable
private fun BarraSuperior(
    escaneando: Boolean,
    onBuscar: () -> Unit,
    onBorrar: () -> Unit,
    onExportar: () -> Unit
) {
    val jc = LocalJobColors.current
    var confirmar by remember { mutableStateOf(false) }

    val gradientLogo = remember {
        androidx.compose.ui.graphics.Brush.horizontalGradient(
            com.jobai.hunter.ui.theme.GradientPalette.Sunset
        )
    }

    Surface(color = jc.superficie) {
        Row(
            modifier = Modifier.statusBarsPadding().fillMaxWidth()
                .padding(start = 18.dp, end = 10.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Job", fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, color = jc.texto)
                Text(
                    text = "Hunter",
                    fontSize = 23.sp,
                    fontWeight = FontWeight.ExtraBold,
                    style = androidx.compose.ui.text.TextStyle(brush = gradientLogo)
                )
            }

            Spacer(Modifier.weight(1f))

            IconButton(onExportar, Modifier.size(38.dp)) {
                Icon(Icons.Outlined.FileDownload, "Exportar CSV", tint = jc.textoSuave, modifier = Modifier.size(20.dp))
            }
            IconButton({ if (!escaneando) onBuscar() }, Modifier.size(38.dp)) {
                if (escaneando) {
                    CircularProgressIndicator(Modifier.size(17.dp), color = jc.acento, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Refresh, "Buscar ofertas", tint = jc.acento, modifier = Modifier.size(21.dp))
                }
            }
            IconButton(
                { if (confirmar) { onBorrar(); confirmar = false } else confirmar = true },
                Modifier.size(38.dp)
            ) {
                Icon(
                    if (confirmar) Icons.Outlined.DeleteForever else Icons.Outlined.DeleteOutline,
                    "Borrar vacantes",
                    tint = if (confirmar) jc.peligro else jc.textoTenue,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// ===========================================================================
// Piezas
// ===========================================================================
@Composable
private fun Pestana(
    seleccionada: Boolean,
    titulo: String,
    cantidad: Int,
    subtitulo: String?,
    color: Color,
    onClick: () -> Unit
) {
    val jc = LocalJobColors.current
    Tab(selected = seleccionada, onClick = onClick, modifier = Modifier.height(56.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    titulo,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (seleccionada) FontWeight.Bold else FontWeight.Medium,
                    color = if (seleccionada) color else jc.textoTenue
                )
                Spacer(Modifier.width(6.dp))
                Surface(shape = CircleShape, color = jc.velo(if (seleccionada) color else jc.textoTenue)) {
                    Text(
                        cantidad.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (seleccionada) color else jc.textoTenue,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            if (subtitulo != null) {
                Text(subtitulo, fontSize = 9.sp, color = jc.textoTenue)
            }
        }
    }
}

@Composable
private fun Chip(texto: String, activo: Boolean, color: Color, onClick: () -> Unit) {
    val jc = LocalJobColors.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = Color.Unspecified,
        border = if (activo) null else BorderStroke(1.dp, jc.borde),
        modifier = Modifier.height(34.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .background(
                    if (activo) {
                        if (jc.dark) androidx.compose.ui.graphics.Brush.horizontalGradient(
                            listOf(Color(0xFF6366F1), Color(0xFF8B5CF6), Color(0xFFEC4899))
                        ) else androidx.compose.ui.graphics.SolidColor(jc.velo(color))
                    } else androidx.compose.ui.graphics.SolidColor(jc.tarjeta),
                    RoundedCornerShape(10.dp)
                )
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                texto,
                style = MaterialTheme.typography.labelMedium,
                fontSize = 12.sp,
                fontWeight = if (activo) FontWeight.Bold else FontWeight.Medium,
                color = if (activo) (if (jc.dark) Color.White else color) else jc.textoSuave,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun BotonIcono(
    icono: androidx.compose.ui.graphics.vector.ImageVector,
    descripcion: String,
    badge: Int,
    onClick: () -> Unit
) {
    val jc = LocalJobColors.current
    Box {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(10.dp),
            color = jc.tarjeta,
            border = BorderStroke(1.dp, if (badge > 0) jc.contorno(jc.acento) else jc.borde),
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icono, descripcion, tint = if (badge > 0) jc.acento else jc.textoSuave, modifier = Modifier.size(18.dp))
            }
        }
        if (badge > 0) {
            Surface(
                shape = CircleShape,
                color = jc.acento,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-5).dp)
            ) {
                Text(
                    badge.toString(),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
        }
    }
}

@Composable
private fun MenuOrden(actual: String, onSelect: (String) -> Unit) {
    val jc = LocalJobColors.current
    var abierto by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { abierto = true },
            shape = RoundedCornerShape(10.dp),
            color = jc.tarjeta,
            border = BorderStroke(1.dp, jc.borde),
            modifier = Modifier.height(36.dp)
        ) {
            Row(
                Modifier.padding(start = 10.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.SwapVert, null, tint = jc.textoSuave, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text(actual, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = jc.textoSuave)
            }
        }
        DropdownMenu(abierto, { abierto = false }, modifier = Modifier.background(jc.tarjeta)) {
            ORDENES.forEach { opt ->
                DropdownMenuItem(
                    text = {
                        Text(
                            opt,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (opt == actual) FontWeight.Bold else FontWeight.Normal,
                            color = if (opt == actual) jc.acento else jc.texto
                        )
                    },
                    onClick = { onSelect(opt); abierto = false }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun HojaFiltros(
    portales: List<String>,
    portalesSel: Set<String>,
    modalidadesSel: Set<String>,
    onPortal: (String) -> Unit,
    onModalidad: (String) -> Unit,
    onLimpiar: () -> Unit,
    onCerrar: () -> Unit
) {
    val jc = LocalJobColors.current
    ModalBottomSheet(
        onDismissRequest = onCerrar,
        containerColor = jc.superficie,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(36.dp, 4.dp)
                    .background(jc.borde, CircleShape)
            )
        }
    ) {
        Column(Modifier.padding(start = 22.dp, end = 22.dp, bottom = 36.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Filtros",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = jc.texto,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onLimpiar) {
                    Text("Limpiar", color = Color(0xFFEC4899), fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Portal", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = jc.textoSuave)
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                portales.forEach { p ->
                    Chip(p, portalesSel.contains(p), jc.portal(p)) { onPortal(p) }
                }
            }

            Spacer(Modifier.height(22.dp))
            Text("Modalidad", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = jc.textoSuave)
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("Presencial", "Remoto", "Hibrido").forEach { m ->
                    Chip(if (m == "Hibrido") "Híbrido" else m, modalidadesSel.contains(m), jc.modalidad(m)) {
                        onModalidad(m)
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
            Surface(
                onClick = onCerrar,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
                color = Color.Unspecified
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            if (jc.dark) androidx.compose.ui.graphics.Brush.horizontalGradient(
                                com.jobai.hunter.ui.theme.GradientPalette.HeroButton
                            )
                            else androidx.compose.ui.graphics.Brush.horizontalGradient(
                                listOf(jc.acento, Color(0xFF1D4ED8))
                            ),
                            RoundedCornerShape(14.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Ver resultados",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun EstadoVacio(escaneando: Boolean, onBuscar: () -> Unit) {
    val jc = LocalJobColors.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Todavía no hay ofertas", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = jc.texto)
        Spacer(Modifier.height(8.dp))
        Text(
            "Pulsa actualizar para traer las vacantes de los últimos 30 días.",
            style = MaterialTheme.typography.bodyMedium,
            color = jc.textoTenue
        )
        if (!escaneando) {
            Spacer(Modifier.height(22.dp))
            Button(onBuscar, shape = RoundedCornerShape(12.dp)) {
                Text("Buscar ofertas ahora", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SinResultados(esAtipico: Boolean, hayFiltros: Boolean, onLimpiar: () -> Unit) {
    val jc = LocalJobColors.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            if (esAtipico) "Ninguna oferta con régimen atípico" else "Ninguna oferta con régimen estándar",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = jc.texto
        )
        if (hayFiltros) {
            Spacer(Modifier.height(10.dp))
            TextButton(onLimpiar) { Text("Quitar filtros", color = jc.acento) }
        }
    }
}

// ===========================================================================
// Exportación
// ===========================================================================
/**
 * Se construye el CSV en un hilo aparte: recorrer cientos de descripciones con
 * el extractor y escribir el archivo desde el hilo de UI congelaba la pantalla
 * varios segundos al tocar "Exportar".
 */
fun exportOffersToCsv(context: android.content.Context, offers: List<JobEntity>) {
    if (offers.isEmpty()) {
        android.widget.Toast.makeText(context, "No hay ofertas para exportar", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val principal = android.os.Handler(android.os.Looper.getMainLooper())
    Thread {
    try {
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

        val sb = StringBuilder()
        sb.append(
            "Portal,Puesto,Empresa,Ubicacion,Distrito,Departamento,Zona,Modalidad,Jornada,Regimen,Campamento," +
                "Seniority,ExperienciaTipo,ExperienciaMeses,ExperienciaEvidencia,NivelEducativo,Ingles," +
                "Salario,SalarioMin,SalarioMax,Moneda,Beneficios,MatchScore,FechaPublicacion," +
                "RequisitosIdentificados,Descripcion,URL\n"
        )
        for (o in offers) {
            val reqs = com.jobai.hunter.domain.RequirementExtractor
                .top(o.puesto, o.descripcion, n = 10).map { it.etiqueta }
            sb.append(q(o.portal)).append(',')
                .append(q(o.puesto)).append(',')
                .append(q(o.empresa)).append(',')
                .append(q(o.ubicacion)).append(',')
                .append(q(o.distrito)).append(',')
                .append(q(o.departamento)).append(',')
                .append(q(o.zonaEnum.etiqueta)).append(',')
                .append(q(o.modalidad)).append(',')
                .append(q(o.tipoJornada.name)).append(',')
                .append(q(o.regimenTrabajo ?: "")).append(',')
                .append(if (o.trabajoEnCampamento) "SI" else "NO").append(',')
                .append(q(o.seniority)).append(',')
                .append(q(o.expTipo)).append(',')
                .append(o.mesesExperiencia?.toString() ?: "").append(',')
                .append(q(o.expEvidencia)).append(',')
                .append(q(o.nivelEducativo)).append(',')
                .append(q(if (o.requiereIngles) (o.nivelIngles ?: "Si") else "No")).append(',')
                .append(q(o.salario)).append(',')
                .append(o.salarioMin?.toInt()?.toString() ?: "").append(',')
                .append(o.salarioMax?.toInt()?.toString() ?: "").append(',')
                .append(q(o.salarioMoneda ?: "")).append(',')
                .append(q(o.beneficiosLista.joinToString(" | "))).append(',')
                .append(o.matchScore).append(',')
                .append(q(o.fechaPublicacion)).append(',')
                .append(q(reqs.joinToString(" | "))).append(',')
                .append(q(o.descripcion.replace("\n", " ").replace("\r", ""))).append(',')
                .append(q(o.url)).append('\n')
        }

        val file = java.io.File(context.cacheDir, "vacantes_job_hunter.csv")
        file.writeText(sb.toString(), Charsets.UTF_8)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        principal.post {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "Exportar Vacantes CSV"
                )
            )
        }
    } catch (e: Exception) {
        principal.post {
            android.widget.Toast.makeText(context, "Error al exportar CSV: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    }.start()
}
