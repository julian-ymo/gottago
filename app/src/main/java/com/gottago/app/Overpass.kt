package com.gottago.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** Finds toilets (and places that usually have one) in OpenStreetMap via the free Overpass API. */
object Overpass {
    private val endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
    )

    /** If a server hasn't answered after this long, also ask the next one; first answer wins. */
    private const val STAGGER_MS = 2500L
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun search(lat: Double, lon: Double, radius: Int): List<Toilet> {
        val a = "(around:$radius,$lat,$lon)"
        val query = """
            [out:json][timeout:15];
            (
              nwr["amenity"="toilets"]$a;
              nwr["toilets"="yes"]$a;
              nwr["shop"="mall"]$a;
              nwr["amenity"="fuel"]$a;
              nwr["amenity"="place_of_worship"]["religion"="muslim"]$a;
            );
            out center 400;
        """.trimIndent()
        val body = "data=" + URLEncoder.encode(query, "UTF-8")

        val answers = Channel<Result<List<Toilet>>>(endpoints.size)
        // Detached jobs: a slow server's blocking request can't hold up the winner.
        val jobs = endpoints.mapIndexed { i, endpoint ->
            bg.launch {
                delay(i * STAGGER_MS)
                answers.trySend(runCatching {
                    parse(Http.call("POST", endpoint, body, "application/x-www-form-urlencoded"))
                })
            }
        }
        var last: Throwable? = null
        try {
            repeat(endpoints.size) {
                val r = answers.receive()
                if (r.isSuccess) return r.getOrThrow()
                last = r.exceptionOrNull()
            }
        } finally {
            jobs.forEach { it.cancel() }
        }
        throw (last ?: IOException("Overpass search failed"))
    }

    private fun parse(json: String): List<Toilet> {
        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<Toilet>()
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            val center = el.optJSONObject("center")
            val lat = when {
                el.has("lat") -> el.getDouble("lat")
                center != null -> center.getDouble("lat")
                else -> continue
            }
            val lon = when {
                el.has("lon") -> el.getDouble("lon")
                center != null -> center.getDouble("lon")
                else -> continue
            }
            val id = "osm_${el.getString("type")}_${el.getLong("id")}"
            val tags = el.optJSONObject("tags") ?: JSONObject()
            fromTags(id, lat, lon, tags)?.let { out += it }
        }
        return out.distinctBy { it.id }
    }

    private fun fromTags(id: String, lat: Double, lon: Double, tags: JSONObject): Toilet? {
        fun g(k: String) = tags.optString(k, "").trim().lowercase()
        fun yn(vararg keys: String): Boolean? {
            for (k in keys) when (g(k)) {
                "yes", "designated", "limited", "room", "bench" -> return true
                "no" -> return false
            }
            return null
        }

        if (g("toilets") == "no") return null
        val amenity = g("amenity")
        val access = if (amenity == "toilets") g("access") else g("toilets:access")
        if (access == "private" || access == "no") return null

        val kind = when {
            amenity == "toilets" -> Kind.TOILET
            g("shop") == "mall" -> Kind.MALL
            amenity == "place_of_worship" -> Kind.MOSQUE
            amenity == "fuel" -> Kind.FUEL
            amenity in setOf("cafe", "restaurant", "fast_food", "food_court", "pub", "bar") -> Kind.CAFE
            else -> Kind.OTHER
        }
        val confirmed = amenity == "toilets" || g("toilets") == "yes"
        val isToilet = kind == Kind.TOILET

        val rawName = tags.optString("name:en").ifBlank { tags.optString("name") }.trim()
        val name = when {
            rawName.isNotEmpty() -> rawName
            isToilet -> "Public toilet"
            else -> kind.label
        }

        return Toilet(
            id = id,
            lat = lat,
            lon = lon,
            name = name,
            kind = kind,
            confirmed = confirmed,
            community = false,
            fee = if (isToilet) yn("fee") else yn("toilets:fee"),
            wheelchair = if (isToilet) yn("wheelchair") else yn("toilets:wheelchair"),
            baby = yn("changing_table", "diaper"),
            unisex = yn("unisex"),
            openingHours = tags.optString("opening_hours").trim().ifBlank { null },
            customersOnly = access == "customers"
        )
    }
}
