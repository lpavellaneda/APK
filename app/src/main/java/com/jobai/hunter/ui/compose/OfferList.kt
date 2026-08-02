package com.jobai.hunter.ui.compose

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun OfferList(
    offers: List<JobUiModel>,
    onVerOferta: (JobUiModel) -> Unit,
    onTogglePostulada: (JobUiModel) -> Unit,
    onDescartar: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp)
    ) {
        // contentType permite a Compose reciclar la estructura de la tarjeta
        // entre items en vez de reconstruirla al entrar cada una en pantalla.
        items(
            items = offers,
            key = { it.url },
            contentType = { "oferta" }
        ) { offer ->
            val onVer = remember(offer.url) { { onVerOferta(offer) } }
            val onToggle = remember(offer.url, offer.estado) { { onTogglePostulada(offer) } }
            val onArchivar = remember(offer.url) { { onDescartar(offer.url) } }

            OfferCard(
                offer = offer,
                onVerOferta = onVer,
                onTogglePostulada = onToggle,
                onDescartar = onArchivar,
                onClick = onVer
            )
        }
    }
}
