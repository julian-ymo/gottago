package com.gottago.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
    /** Where the current list was searched from. */
    var searchLat = 0.0
        private set
    var searchLon = 0.0
        private set

    /** Visible toilets, nearest first. */
    var toilets: List<Toilet> = emptyList()
        private set

    private val radii = listOf(1500, 5000)

    fun setUser(lat: Double, lon: Double) {
        userLat = lat; userLon = lon; hasLocation = true
        toilets.forEach { it.distanceM = Geo.distance(lat, lon, it.lat, it.lon) }
    }

    suspend fun search(lat: Double, lon: Double): List<Toilet> = withContext(Dispatchers.IO) {
        setUser(lat, lon)
        var result: List<Toilet> = emptyList()
        for (r in radii) {
            val osm = try {
                Overpass.search(lat, lon, r)
            } catch (e: Exception) {
                if (r == radii.first()) throw e // nothing at all: report the error
                break                           // keep the smaller search's results
            }
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
        searchLat = lat; searchLon = lon
        result
    }

    /** True if the saved list is close enough to use straight away. */
    fun usableFor(lat: Double, lon: Double): Boolean =
        toilets.isNotEmpty() && searchedAt > 0 &&
            Geo.distance(lat, lon, searchLat, searchLon) < 400 &&
            System.currentTimeMillis() - searchedAt < 24 * 3600_000L

    // ---------- on-disk cache: show toilets instantly when the app opens ----------

    private fun file(ctx: Context) = File(ctx.filesDir, "last_search.json")

    fun save(ctx: Context) {
        runCatching {
            val arr = JSONArray()
            toilets.forEach { t ->
                arr.put(JSONObject()
                    .put("id", t.id).put("lat", t.lat).put("lon", t.lon).put("name", t.name)
                    .put("kind", t.kind.name).put("confirmed", t.confirmed).put("community", t.community)
                    .put("fee", t.fee ?: JSONObject.NULL).put("wheelchair", t.wheelchair ?: JSONObject.NULL)
                    .put("baby", t.baby ?: JSONObject.NULL).put("unisex", t.unisex ?: JSONObject.NULL)
                    .put("hours", t.openingHours ?: JSONObject.NULL).put("customers", t.customersOnly)
                    .put("cv", t.confirmVotes).put("rv", t.removeVotes).put("my", t.myVote ?: JSONObject.NULL))
            }
            val root = JSONObject().put("lat", searchLat).put("lon", searchLon)
                .put("at", searchedAt).put("r", radiusUsed).put("toilets", arr)
            file(ctx).writeText(root.toString())
        }
    }

    fun load(ctx: Context) {
        if (toilets.isNotEmpty()) return
        runCatching {
            val f = file(ctx)
            if (!f.exists()) return
            val root = JSONObject(f.readText())
            val arr = root.getJSONArray("toilets")
            fun JSONObject.b(k: String): Boolean? = if (isNull(k)) null else getBoolean(k)
            fun JSONObject.s(k: String): String? = if (isNull(k)) null else getString(k)
            val list = ArrayList<Toilet>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list += Toilet(
                    id = o.getString("id"), lat = o.getDouble("lat"), lon = o.getDouble("lon"),
                    name = o.getString("name"), kind = Kind.parse(o.getString("kind")),
                    confirmed = o.getBoolean("confirmed"), community = o.getBoolean("community"),
                    fee = o.b("fee"), wheelchair = o.b("wheelchair"), baby = o.b("baby"), unisex = o.b("unisex"),
                    openingHours = o.s("hours"), customersOnly = o.getBoolean("customers"),
                    confirmVotes = o.getInt("cv"), removeVotes = o.getInt("rv"), myVote = o.s("my")
                )
            }
            searchLat = root.getDouble("lat"); searchLon = root.getDouble("lon")
            searchedAt = root.getLong("at"); radiusUsed = root.getInt("r")
            toilets = list
        }
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
