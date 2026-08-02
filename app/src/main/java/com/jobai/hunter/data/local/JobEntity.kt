package com.jobai.hunter.data.local

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.jobai.hunter.data.model.JobOffer
import com.jobai.hunter.domain.PerfilExtractor
import com.jobai.hunter.domain.Zona

@Immutable
@Entity(tableName = "job_offers")
data class JobEntity(
    @PrimaryKey val url: String,
    val portal: String,
    val puesto: String,
    val empresa: String,
    val ubicacion: String,
    val distrito: String,
    val departamento: String = "",
    val zona: String = Zona.DESCONOCIDA.name,
    val salario: String,
    val modalidad: String,
    val descripcion: String,
    val fechaPublicacion: String,
    val fechaEpoch: Long = 0L,
    val matchScore: Int,
    val mesesExperiencia: Int? = null,
    val expTipo: String = "NO_MENCIONADA",
    val expEvidencia: String = "",
    val nuevo: Boolean,
    val estado: String = "pendiente", // pendiente, postulada, descartada
    // --- Enriquecimiento ---
    val area: String = "",
    val rubro: String = "",
    val requisitos: String = "",
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
    /** Lista serializada con '|' para no necesitar TypeConverter. */
    val beneficios: String = ""
)

// ======================================================================
// Derivados
// ======================================================================

enum class TipoJornada { ESTANDAR, ATIPICO, NO_DETERMINADO }

/**
 * NO_DETERMINADO no es lo mismo que ESTANDAR: pintar como "estandar" una
 * oferta cuya descripcion nunca se llego a descargar es afirmar algo que no
 * se miro.
 */
val JobEntity.tipoJornada: TipoJornada
    get() = when {
        regimenTrabajo != null || trabajoEnCampamento -> TipoJornada.ATIPICO
        descripcion.length < 400 -> TipoJornada.NO_DETERMINADO
        else -> TipoJornada.ESTANDAR
    }

val JobEntity.etiquetaJornada: String?
    get() = when (tipoJornada) {
        TipoJornada.ATIPICO -> when {
            regimenTrabajo != null && trabajoEnCampamento -> "$regimenTrabajo · Campamento"
            regimenTrabajo != null -> regimenTrabajo
            else -> "Campamento"
        }
        TipoJornada.NO_DETERMINADO -> "Jornada s/d"
        TipoJornada.ESTANDAR -> null
    }

val JobEntity.zonaEnum: Zona
    get() = runCatching { Zona.valueOf(zona) }.getOrDefault(Zona.DESCONOCIDA)

val JobEntity.expTipoEnum: PerfilExtractor.TipoExperiencia
    get() = runCatching { PerfilExtractor.TipoExperiencia.valueOf(expTipo) }
        .getOrDefault(PerfilExtractor.TipoExperiencia.NO_MENCIONADA)

/** Texto de la pastilla de experiencia. Ya no dice "Sin exp." cuando no se sabe. */
val JobEntity.etiquetaExperiencia: String
    get() = PerfilExtractor.Experiencia(mesesExperiencia, null, null, expTipoEnum).etiqueta

val JobEntity.beneficiosLista: List<String>
    get() = if (beneficios.isBlank()) emptyList() else beneficios.split("|").filter { it.isNotBlank() }

/** Ubicación presentable: "San Borja" o "Arequipa, Arequipa". */
val JobEntity.ubicacionCorta: String
    get() = when {
        distrito.isBlank() && departamento.isBlank() -> "Sin especificar"
        distrito.isBlank() -> departamento
        departamento.isBlank() || distrito.equals(departamento, true) -> distrito
        zonaEnum == Zona.LIMA_METROPOLITANA || zonaEnum == Zona.CALLAO -> distrito
        else -> "$distrito, $departamento"
    }

val JobEntity.salarioTexto: String?
    get() {
        val mon = salarioMoneda ?: "S/"
        fun f(v: Double) = if (v >= 1000) {
            val k = v / 1000
            if (k % 1.0 == 0.0) "${k.toInt()}k" else String.format("%.1fk", k)
        } else v.toInt().toString()
        return when {
            salarioMin != null && salarioMax != null && salarioMax > salarioMin ->
                "$mon ${f(salarioMin)} – ${f(salarioMax)}"
            salarioMin != null -> "Desde $mon ${f(salarioMin)}"
            salarioMax != null -> "Hasta $mon ${f(salarioMax)}"
            salario.isNotBlank() && !salario.contains("No especificado", true) -> salario
            else -> null
        }
    }

// ======================================================================
// Mappers
// ======================================================================

fun JobOffer.toEntity(): JobEntity = JobEntity(
    url = url,
    portal = portal,
    puesto = puesto,
    empresa = empresa,
    ubicacion = ubicacion,
    distrito = distrito,
    departamento = departamento,
    zona = zona,
    salario = salario,
    modalidad = modalidad,
    descripcion = descripcion,
    fechaPublicacion = fechaPublicacion,
    fechaEpoch = fechaEpoch,
    matchScore = matchScore,
    mesesExperiencia = mesesExperiencia,
    expTipo = expTipo,
    expEvidencia = expEvidencia,
    nuevo = nuevo,
    area = area,
    rubro = rubro,
    requisitos = requisitos,
    salarioMin = salarioMin,
    salarioMax = salarioMax,
    salarioMoneda = salarioMoneda,
    seniority = seniority,
    nivelEducativo = nivelEducativo,
    requiereIngles = requiereIngles,
    nivelIngles = nivelIngles,
    regimenTrabajo = regimenTrabajo,
    trabajoEnCampamento = trabajoEnCampamento,
    ingIndustrialExclusiva = ingIndustrialExclusiva,
    beneficios = beneficios.joinToString("|")
)

fun JobEntity.toDomain(): JobOffer = JobOffer(
    portal = portal,
    puesto = puesto,
    empresa = empresa,
    ubicacion = ubicacion,
    distrito = distrito,
    departamento = departamento,
    zona = zona,
    salario = salario,
    modalidad = modalidad,
    descripcion = descripcion,
    url = url,
    fechaPublicacion = fechaPublicacion,
    fechaEpoch = fechaEpoch,
    matchScore = matchScore,
    mesesExperiencia = mesesExperiencia,
    expTipo = expTipo,
    expEvidencia = expEvidencia,
    nuevo = nuevo,
    area = area,
    rubro = rubro,
    requisitos = requisitos,
    salarioMin = salarioMin,
    salarioMax = salarioMax,
    salarioMoneda = salarioMoneda,
    seniority = seniority,
    nivelEducativo = nivelEducativo,
    requiereIngles = requiereIngles,
    nivelIngles = nivelIngles,
    regimenTrabajo = regimenTrabajo,
    trabajoEnCampamento = trabajoEnCampamento,
    ingIndustrialExclusiva = ingIndustrialExclusiva,
    beneficios = beneficiosLista
)
