package com.mrarron.wristclaude.core

/** Where the phone is, at city level (from the phone's location, reverse geocoded on the phone). */
data class Place(
    val city: String,
    val region: String = "",
    /** ISO 3166-1 alpha-2, e.g. "US"; "" if unknown. */
    val countryCode: String = "",
    val neighborhood: String = "",
) {
    /** "Merritt Island, Florida, US" (neighborhood first when known). */
    fun label(): String = listOf(neighborhood, city, region, countryCode)
        .map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(", ")

    companion object {
        private fun clean(v: String?, max: Int) = TextUtil.oneLineCapped(v.orEmpty(), max)

        /** Builds a Place from geocoder fields; null without a city (or at least a region). */
        fun of(city: String?, region: String?, countryCode: String?, neighborhood: String? = null): Place? {
            val c = clean(city, 60)
            val r = clean(region, 60)
            if (c.isEmpty() && r.isEmpty()) return null
            val cc = countryCode.orEmpty().trim().uppercase().takeIf { it.length == 2 && it.all { ch -> ch in 'A'..'Z' } }.orEmpty()
            return Place(c, r, cc, clean(neighborhood, 60))
        }
    }
}
