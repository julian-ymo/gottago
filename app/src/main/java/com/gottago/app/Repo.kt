package com.gottago.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Holds the last search and decides which toilet is "best" right now. */
object Repo {
    var userLat = 0.0
        private set
    var userLon = 0.0
        private set
    var hasLocation = false
        private set
    var searchedAt = 0L
        private set
    var radiusUsed = 0
        private set

    /** Visible toilets, nearest first. */
    var toilets: List<Toilet> = emptyList()
        private set

    private val radii = listOf(1000, 2500, 5000)

    fun setUser(lat: Double, lon: Double) {
        userLat = lat; userLon = lon; hasLocation = true
        toilets.forEach { it.distanceM = Geo.distance(lat, lon, it.lat, it.lon) }
    }

    suspend fun search(lat: Double, lon: Double): List<Toilet> = withContext(Dispatchers.IO) {
        setUser(lat, lon)
        var result: List<Toilet> = emptyList()
        for (r in radii) {
            val osm = Overpass.search(lat, lon, r)
            val community = if (Community.enabled) {
                runCatching { Community.nearby(lat, lon, r) }.getOrDefault(emptyList())
            } else emptyList()

            val merged = merge(osm, community)
            merged.forEach { it.distanceM = Geo.distance(lat, lon, it.lat, it.lon) }
            result = merged.filter { !it.hidden && it.distanceM <= r * 1.05 }.sortedBy { it.distanceM }
            radiusUsed = r
            // Stop widening once a real toilet that isn't closed is found.
            if (result.any { it.confirmed && it.openState() != OpenState.CLOSED }) break
        }
        toilets = result
        searchedAt = System.currentTimeMillis()
        result
    }

    private fun merge(osm: List<Toilet>, docs: List<Community.Doc>): List<Toilet> {
        val me = Community.uid
        val byId = LinkedHashMap<String, Toilet>()
        osm.forEach { byId[it.id] = it }
        for (d in docs) {
            val myVote = when {
                me != null && me in d.removeVoters -> "remove"
                me != null && me in d.confirmVoters -> "confirm"
                else -> null
            }
            if (d.id.startsWith("osm_")) {
                byId[d.id]?.apply {
                    confirmVotes = d.confirmVoters.size
                    removeVotes = d.removeVoters.size
                    this.myVote = myVote
                }
            } else if (d.source == "user") {
                byId[d.id] = Toilet(
                    id = d.id,
                    lat = d.lat,
                    lon = d.lon,
                    name = d.name.ifBlank { d.kind.label },
                    kind = d.kind,
                    confirmed = true,
                    community = true,
                    fee = d.fee,
                    wheelchair = d.wheelchair,
                    baby = d.baby,
                    unisex = null,
                    openingHours = if (d.h24) "24/7" else null,
                    customersOnly = false,
                    confirmVotes = d.confirmVoters.size,
                    removeVotes = d.removeVoters.size,
                    myVote = myVote
                )
            }
        }
        return byId.values.toList()
    }

    /** Toilets that aren't closed, best first (distance, weighted by how sure we are). */
    fun ranked(): List<Toilet> =
        toilets.filter { it.openState() != OpenState.CLOSED }.sortedBy { score(it) }

    private fun score(t: Toilet): Double {
        var f = 1.0
        if (!t.confirmed) f *= 1.7                         // mall / mosque / petrol station: probably has one
        if (t.isNew) f *= 1.3                              // added by a user, not yet confirmed
        if (t.openState() == OpenState.UNKNOWN) f *= 1.2   // no opening hours known
        if (t.customersOnly) f *= 1.15
        if (t.myVote == "remove") f *= 10.0
        return t.distanceM * f
    }

    fun find(id: String?): Toilet? = toilets.firstOrNull { it.id == id }

    fun addLocal(t: Toilet) {
        t.distanceM = Geo.distance(userLat, userLon, t.lat, t.lon)
        toilets = (toilets + t).sortedBy { it.distanceM }
    }

    fun removeLocal(id: String) {
        toilets = toilets.filter { it.id != id }
    }
}
