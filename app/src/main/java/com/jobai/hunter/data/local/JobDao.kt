package com.jobai.hunter.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface JobDao {
    /** Excluye las descartadas: antes era un SELECT * y nadie filtraba por estado. */
    @Query("SELECT * FROM job_offers WHERE estado != 'descartada' ORDER BY matchScore DESC")
    fun getAllOffers(): Flow<List<JobEntity>>

    @Query("SELECT COUNT(*) FROM job_offers WHERE estado = 'descartada'")
    fun contarDescartadas(): Flow<Int>

    @Query("UPDATE job_offers SET estado = 'pendiente' WHERE estado = 'descartada'")
    suspend fun restaurarDescartadas()

    @Query("SELECT url FROM job_offers")
    suspend fun getUrls(): List<String>

    @Query("SELECT url, estado FROM job_offers WHERE estado != 'pendiente'")
    suspend fun getEstados(): List<EstadoRow>

    @Query("SELECT url, descripcion FROM job_offers WHERE length(descripcion) >= 400")
    suspend fun getDescripciones(): List<DescripcionRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOffers(offers: List<JobEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOffer(offer: JobEntity)

    @Query("UPDATE job_offers SET estado = :estado WHERE url = :url")
    suspend fun updateEstado(url: String, estado: String)

    @Query("DELETE FROM job_offers WHERE fechaEpoch > 0 AND fechaEpoch < :limiteEpoch AND estado != 'postulada'")
    suspend fun purgarAntiguas(limiteEpoch: Long)

    @Query("DELETE FROM job_offers")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM job_offers")
    suspend fun getCount(): Int
}

data class EstadoRow(val url: String, val estado: String)

data class DescripcionRow(val url: String, val descripcion: String)
