package com.jobai.hunter.domain

/**
 * Catalogo de los 50 distritos de Lima Metropolitana (43) y Callao (7).
 * Se usa como base para la deteccion de ubicaciones en las ofertas y como
 * ultimo recurso cuando no hay coordenadas exactas en la DB del usuario.
 */
object LimaGeo {
    val LIMA_CENTER = Pair(-12.0463, -77.0427)

    private val districtMap = mapOf(
        // LIMA METROPOLITANA (43)
        "ancon" to Pair(-11.7731, -77.1761),
        "ate vitarte" to Pair(-12.0253, -76.9205),
        "ate" to Pair(-12.0253, -76.9205),
        "barranco" to Pair(-12.1481, -77.0211),
        "brena" to Pair(-12.0601, -77.0449),
        "carabayllo" to Pair(-11.8504, -77.0315),
        "chaclacayo" to Pair(-11.9723, -76.7686),
        "chorrillos" to Pair(-12.1923, -77.0219),
        "cieneguilla" to Pair(-12.0911, -76.7699),
        "comas" to Pair(-11.9332, -77.0448),
        "el agustino" to Pair(-12.0487, -76.9996),
        "independencia" to Pair(-11.9922, -77.0504),
        "jesus maria" to Pair(-12.0781, -77.0464),
        "la molina" to Pair(-12.0838, -76.9248),
        "la victoria" to Pair(-12.0651, -77.0305),
        "lince" to Pair(-12.0845, -77.0334),
        "los olivos" to Pair(-11.9644, -77.0706),
        "lurigancho chosica" to Pair(-11.9392, -76.7028),
        "chosica" to Pair(-11.9392, -76.7028),
        "lurin" to Pair(-12.2741, -76.8715),
        "magdalena del mar" to Pair(-12.0923, -77.0733),
        "magdalena" to Pair(-12.0923, -77.0733),
        "miraflores" to Pair(-12.1214, -77.0259),
        "pachacamac" to Pair(-12.1293, -76.8587),
        "pucusana" to Pair(-12.4831, -76.7971),
        "pueblo libre" to Pair(-12.0747, -77.0601),
        "puente piedra" to Pair(-11.8661, -77.0768),
        "punta hermosa" to Pair(-12.3361, -76.8252),
        "punta negra" to Pair(-12.3654, -76.7963),
        "rimac" to Pair(-12.0203, -77.0354),
        "san bartolo" to Pair(-12.3921, -76.7818),
        "san borja" to Pair(-12.1064, -76.9989),
        "san isidro" to Pair(-12.1166, -77.0513),
        "san juan de lurigancho" to Pair(-12.0163, -76.9854),
        "sjl" to Pair(-12.0163, -76.9854),
        "san juan de miraflores" to Pair(-12.1555, -76.9678),
        "sjm" to Pair(-12.1555, -76.9678),
        "san luis" to Pair(-12.0754, -76.9959),
        "san martin de porres" to Pair(-11.9961, -77.0713),
        "smp" to Pair(-11.9961, -77.0713),
        "san miguel" to Pair(-12.0786, -77.0952),
        "santa anita" to Pair(-12.0441, -76.9686),
        "santa maria del mar" to Pair(-12.4111, -76.7761),
        "santa rosa" to Pair(-11.8081, -77.1654),
        "santiago de surco" to Pair(-12.1444, -77.0051),
        "surco" to Pair(-12.1444, -77.0051),
        "surquillo" to Pair(-12.1141, -77.0205),
        "villa el salvador" to Pair(-12.2111, -76.9361),
        "ves" to Pair(-12.2111, -76.9361),
        "villa maria del triunfo" to Pair(-12.1581, -76.9275),
        "vmt" to Pair(-12.1581, -76.9275),
        "cercado de lima" to Pair(-12.0463, -77.0427),
        "lima" to Pair(-12.0463, -77.0427),

        // CALLAO (7)
        "callao" to Pair(-12.0630, -77.1469),
        "bellavista" to Pair(-12.0625, -77.1291),
        "carmen de la legua" to Pair(-12.0427, -77.0984),
        "la perla" to Pair(-12.0671, -77.1118),
        "la punta" to Pair(-12.0706, -77.1601),
        "ventanilla" to Pair(-11.8654, -77.1251),
        "mi peru" to Pair(-11.8541, -77.1154)
    )

    /** Lista de todos los nombres de distritos conocidos. */
    fun nombresDeDistritos(): Set<String> = districtMap.keys

    /** Recibe un texto YA normalizado (minusculas, sin tildes). */
    fun centroDeDistrito(textoNormalizado: String): Pair<Double, Double>? {
        if (textoNormalizado.isBlank()) return null
        // Ordenar por longitud descendente para que "san juan de lurigancho"
        // gane a "lima" si ambos estan presentes.
        for (d in districtMap.keys.sortedByDescending { it.length }) {
            if (textoNormalizado.contains(d)) return districtMap[d]
        }
        return null
    }
}
