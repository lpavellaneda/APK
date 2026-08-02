package com.jobai.hunter.domain

/**
 * Clasificador inteligente de Áreas Funcionales para ofertas de empleo.
 * Normaliza y asigna categorías (Logística, Operaciones, Calidad, TI, etc.)
 * a partir de metadatos del portal o análisis semántico del puesto y descripción.
 */
object AreaClassifier {

    fun clasificarArea(puesto: String, descripcion: String = "", areaOriginal: String = ""): String {
        if (areaOriginal.isNotBlank()) {
            val normOrig = Texto.norm(areaOriginal)
            when {
                normOrig.contains("logistica") || normOrig.contains("almacen") || normOrig.contains("compras") || normOrig.contains("distribucion") || normOrig.contains("expedicion") || normOrig.contains("supply") || normOrig.contains("inventario") -> return "Logística / Cadena de Suministro"
                normOrig.contains("operacion") || normOrig.contains("produccion") || normOrig.contains("planta") || normOrig.contains("mantenimiento") || normOrig.contains("manufactura") -> return "Operaciones y Producción"
                normOrig.contains("calidad") || normOrig.contains("sig") || normOrig.contains("ssoma") || normOrig.contains("procesos") -> return "Calidad y Procesos"
                normOrig.contains("sistemas") || normOrig.contains("tecnologia") || normOrig.contains("software") || normOrig.contains("ti") || normOrig.contains("data") -> return "Tecnología / TI"
                normOrig.contains("comercial") || normOrig.contains("ventas") || normOrig.contains("marketing") -> return "Comercial / Ventas"
                normOrig.contains("administra") || normOrig.contains("finanza") || normOrig.contains("contabi") -> return "Administración y Finanzas"
                normOrig.contains("recursos humanos") || normOrig.contains("rrhh") || normOrig.contains("gestion humana") -> return "Recursos Humanos"
            }
            return areaOriginal.trim()
        }

        val text = Texto.norm("$puesto $descripcion")
        return when {
            text.contains(Regex("\\b(logistica|logistico|almacen|almacenes|inventario|inventarios|compras|despacho|distribucion|embarque|flotas|transporte|cadena de suministro|supply chain|kardex|picking|packing)\\b")) -> "Logística / Cadena de Suministro"
            text.contains(Regex("\\b(operaciones|operacion|produccion|planta|mantenimiento|manufactura|procesos|mejora continua|planeamiento de la produccion|pcp)\\b")) -> "Operaciones y Producción"
            text.contains(Regex("\\b(calidad|aseguramiento|sig|ssoma|seguridad industrial|control de calidad|auditoria de procesos)\\b")) -> "Calidad y Procesos"
            text.contains(Regex("\\b(sistemas|software|desarrollador|programador|tecnologia|it|ti|devops|data|analitica|bi|backend|frontend|fullstack)\\b")) -> "Tecnología / TI"
            text.contains(Regex("\\b(ventas|comercial|ejecutivo de ventas|kam|key account|marketing|telemarketing)\\b")) -> "Comercial / Ventas"
            text.contains(Regex("\\b(administra|administracion|finanzas|contabilidad|tesoreria|facturacion|cobranzas)\\b")) -> "Administración y Finanzas"
            text.contains(Regex("\\b(recursos humanos|rrhh|gestion humana|talento humano|seleccion|reclutamiento)\\b")) -> "Recursos Humanos"
            else -> "Ingeniería / Operaciones"
        }
    }
}
