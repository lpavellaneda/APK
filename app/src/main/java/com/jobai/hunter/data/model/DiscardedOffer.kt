package com.jobai.hunter.data.model

data class DiscardedOffer(
    val portal: String,
    val puesto: String,
    val empresa: String,
    val motivo: String,
    val url: String,
    val descripcion: String = "",
    val ubicacion: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0
)
