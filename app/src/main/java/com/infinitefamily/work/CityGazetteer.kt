package com.infinitefamily.work

import kotlin.math.*

/**
 * Small offline list of cities (name, latitude, longitude) so city-level matching needs no network,
 * no maps and no geocoding service. Profile locations that are not listed here simply cannot be matched
 * automatically; the UI says so.
 */
object CityGazetteer {
    data class City(val name: String, val lat: Double, val lon: Double)

    private val aliases = mapOf(
        "bangalore" to "bengaluru", "madras" to "chennai", "bombay" to "mumbai", "cochin" to "kochi",
        "ernakulam" to "kochi", "trivandrum" to "thiruvananthapuram", "calcutta" to "kolkata", "calicut" to "kozhikode",
        "pondicherry" to "puducherry", "mysore" to "mysuru", "trichy" to "tiruchirappalli", "tiruchirapalli" to "tiruchirappalli",
        "coimbatore" to "coimbatore", "vizag" to "visakhapatnam", "baroda" to "vadodara", "poona" to "pune", "benaras" to "varanasi",
        "kovai" to "coimbatore", "nellai" to "tirunelveli", "thrissur" to "thrissur", "trichur" to "thrissur", "alleppey" to "alappuzha",
        "quilon" to "kollam", "palghat" to "palakkad", "tanjore" to "thanjavur", "new delhi" to "delhi", "gurgaon" to "gurugram",
    )

    val cities: List<City> = listOf(
        City("chennai", 13.0827, 80.2707), City("coimbatore", 11.0168, 76.9558), City("madurai", 9.9252, 78.1198),
        City("tiruchirappalli", 10.7905, 78.7047), City("salem", 11.6643, 78.1460), City("tirunelveli", 8.7139, 77.7567),
        City("erode", 11.3410, 77.7172), City("vellore", 12.9165, 79.1325), City("thanjavur", 10.7870, 79.1378),
        City("tiruppur", 11.1085, 77.3411), City("kanchipuram", 12.8342, 79.7036), City("nagercoil", 8.1833, 77.4119),
        City("hosur", 12.7409, 77.8253), City("puducherry", 11.9416, 79.8083), City("kumbakonam", 10.9617, 79.3881),
        City("thoothukudi", 8.7642, 78.1348), City("karur", 10.9601, 78.0766), City("dindigul", 10.3673, 77.9803),
        City("kochi", 9.9312, 76.2673), City("thiruvananthapuram", 8.5241, 76.9366), City("kozhikode", 11.2588, 75.7804),
        City("thrissur", 10.5276, 76.2144), City("kollam", 8.8932, 76.6141), City("alappuzha", 9.4981, 76.3388),
        City("palakkad", 10.7867, 76.6548), City("kannur", 11.8745, 75.3704), City("kottayam", 9.5916, 76.5222),
        City("malappuram", 11.0510, 76.0711), City("pathanamthitta", 9.2648, 76.7870), City("kasaragod", 12.4996, 74.9869),
        City("bengaluru", 12.9716, 77.5946), City("mysuru", 12.2958, 76.6394), City("mangaluru", 12.9141, 74.8560),
        City("hubballi", 15.3647, 75.1240), City("hyderabad", 17.3850, 78.4867), City("visakhapatnam", 17.6868, 83.2185),
        City("vijayawada", 16.5062, 80.6480), City("tirupati", 13.6288, 79.4192), City("mumbai", 19.0760, 72.8777),
        City("pune", 18.5204, 73.8567), City("nagpur", 21.1458, 79.0882), City("ahmedabad", 23.0225, 72.5714),
        City("surat", 21.1702, 72.8311), City("vadodara", 22.3072, 73.1812), City("delhi", 28.6139, 77.2090),
        City("gurugram", 28.4595, 77.0266), City("noida", 28.5355, 77.3910), City("jaipur", 26.9124, 75.7873),
        City("lucknow", 26.8467, 80.9462), City("kanpur", 26.4499, 80.3319), City("varanasi", 25.3176, 82.9739),
        City("kolkata", 22.5726, 88.3639), City("bhubaneswar", 20.2961, 85.8245), City("patna", 25.5941, 85.1376),
        City("bhopal", 23.2599, 77.4126), City("indore", 22.7196, 75.8577), City("chandigarh", 30.7333, 76.7794),
        City("goa", 15.4909, 73.8278), City("guwahati", 26.1445, 91.7362),
        City("colombo", 6.9271, 79.8612), City("singapore", 1.3521, 103.8198), City("dubai", 25.2048, 55.2708),
        City("kuala lumpur", 3.1390, 101.6869), City("london", 51.5074, -0.1278), City("new york", 40.7128, -74.0060),
        City("san francisco", 37.7749, -122.4194), City("toronto", 43.6532, -79.3832), City("sydney", -33.8688, 151.2093),
        City("doha", 25.2854, 51.5310), City("abu dhabi", 24.4539, 54.3773), City("muscat", 23.5859, 58.4059),
    )

    private val byName = cities.associateBy { it.name }

    fun canonical(raw: String): String {
        // Accept "Chennai, Tamil Nadu" / "Chennai (Adyar)" style input by taking the first part.
        val first = raw.lowercase().substringBefore(',').substringBefore('(').trim().replace(Regex("\\s+"), " ")
        return aliases[first] ?: first
    }

    fun find(raw: String): City? = byName[canonical(raw)]

    fun distanceKm(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(bLat - aLat); val dLon = Math.toRadians(bLon - aLon)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }

    /** Nearest listed city within [maxKm] of a coordinate. */
    fun nearest(lat: Double, lon: Double, maxKm: Double = 30.0): City? =
        cities.minByOrNull { distanceKm(lat, lon, it.lat, it.lon) }?.takeIf { distanceKm(lat, lon, it.lat, it.lon) <= maxKm }
}
