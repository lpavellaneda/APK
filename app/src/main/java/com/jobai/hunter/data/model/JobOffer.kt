package com.jobai.hunter.data.model

data class JobOffer(
    val portal: String,
    val puesto: String,
    val empresa: String,
    /** Texto crudo de ubicacion tal como lo publica el portal. */
    val ubicacion: String,
    val distrito: String = "",
    val departamento: String = "",
    /** Nombre de la constante Zona: LIMA_METROPOLITANA, CALLAO, LIMA_PROVINCIAS... */
    val zona: String = "DESCONOCIDA",
    val salario: String = "No especificado",
    var modalidad: String = "Presencial",
    var descripcion: String = "",
    val url: String,
    val fechaPublicacion: String = "",
    /**
     * fechaPublicacion mezcla "2026-07-15" con "hace 2 semanas", asi que
     * ordenar por ese String no significa nada. 0 = fecha desconocida.
     */
    val fechaEpoch: Long = 0L,
    val matchScore: Int = 0,
    // --- Experiencia (port de extractores.py) ---
    /** null cuando el aviso no cuantifica. NO es lo mismo que 0. */
    val mesesExperiencia: Int? = null,
    /** CUANTIFICADA / REQUERIDA_SIN_CUANTIFICAR / NO_REQUERIDA / NO_MENCIONADA */
    val expTipo: String = "NO_MENCIONADA",
    val expEvidencia: String = "",
    // --- Clasificacion ---
    val rubro: String = "",
    val area: String = "",
    val requisitos: String = "",
    val keywords: String = "",
    val nuevo: Boolean = true,
    // --- Enriquecimiento ---
    val salarioMin: Double? = null,
    val salarioMax: Double? = null,
    val salarioMoneda: String? = null,
    val seniority: String = "Operativo / Sin especificar",
    val nivelEducativo: String = "No especificado",
    val requiereIngles: Boolean = false,
    val nivelIngles: String? = null,
    val regimenTrabajo: String? = null,
    val trabajoEnCampamento: Boolean = false,
    val ingIndustrialExclusiva: Boolean = false,
    val beneficios: List<String> = emptyList()
)
