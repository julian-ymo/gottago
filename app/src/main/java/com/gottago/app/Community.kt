package com.gottago.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Shared add / confirm / remove data, stored in Firebase Firestore through its REST API.
 * Every phone signs in anonymously, so each person gets exactly one vote per toilet.
 *
 * One collection, "places":
 *  - user-added toilets: source = "user", lat, lon, name, kind, fee, h24, wheelchair, baby
 *  - votes on OpenStreetMap toilets: id = "osm_node_123", source = "osm", lat, lon
 *  - both have confirmVoters / removeVoters maps of { uid: true }
 */
object Community {
    data class Doc(
        val id: String,
        val source: String,
        val lat: Double,
        val lon: Double,
        val name: String,
        val kind: Kind,
        val fee: Boolean?,
        val h24: Boolean,
        val wheelchair: Boolean?,
        val baby: Boolean?,
        val confirmVoters: Set<String>,
        val removeVoters: Set<String>
    )

    private lateinit var prefs: SharedPreferences
    private var idToken: String? = null
    private var expiresAt = 0L

    val enabled: Boolean
        get() = BuildConfig.FIREBASE_API_KEY.isNotBlank() && BuildConfig.FIREBASE_PROJECT_ID.isNotBlank()

    val uid: String? get() = prefs.getString("uid", null)

    private val key get() = BuildConfig.FIREBASE_API_KEY
    private val base get() =
        "https://firestore.googleapis.com/v1/projects/${BuildConfig.FIREBASE_PROJECT_ID}/databases/(default)/documents"

    fun init(context: Context) {
        prefs = context.getSharedPreferences("community", Context.MODE_PRIVATE)
    }

    // ---------- sign-in (anonymous) ----------

    @Synchronized
    private fun token(): String {
        val now = System.currentTimeMillis()
        idToken?.let { if (now < expiresAt) return it }

        val refresh = prefs.getString("refresh", null)
        if (refresh != null) {
            try {
                val s = Http.call(
                    "POST", "https://securetoken.googleapis.com/v1/token?key=$key",
                    "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refresh, "UTF-8"),
                    "application/x-www-form-urlencoded"
                )
                val j = JSONObject(s)
                save(j.getString("id_token"), j.getString("refresh_token"), j.getString("user_id"), j.getString("expires_in"))
                return idToken!!
            } catch (e: Exception) {
                // fall through and make a new anonymous account
            }
        }
        val s = Http.call(
            "POST", "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$key",
            "{\"returnSecureToken\":true}"
        )
        val j = JSONObject(s)
        save(j.getString("idToken"), j.getString("refreshToken"), j.getString("localId"), j.getString("expiresIn"))
        return idToken!!
    }

    private fun save(token: String, refresh: String, userId: String, expiresIn: String) {
        idToken = token
        expiresAt = System.currentTimeMillis() + ((expiresIn.toLongOrNull() ?: 3600L) - 120L) * 1000L
        prefs.edit().putString("refresh", refresh).putString("uid", userId).apply()
    }

    private fun auth(): Map<String, String> = mapOf("Authorization" to "Bearer ${token()}")

    // ---------- read ----------

    fun nearby(lat: Double, lon: Double, radiusM: Int): List<Doc> {
        val dLat = radiusM / 111_000.0
        fun bound(op: String, v: Double) = JSONObject().put(
            "fieldFilter", JSONObject()
                .put("field", JSONObject().put("fieldPath", "lat"))
                .put("op", op)
                .put("value", JSONObject().put("doubleValue", v))
        )
        val query = JSONObject().put(
            "structuredQuery", JSONObject()
                .put("from", JSONArray().put(JSONObject().put("collectionId", "places")))
                .put(
                    "where", JSONObject().put(
                        "compositeFilter", JSONObject()
                            .put("op", "AND")
                            .put(
                                "filters", JSONArray()
                                    .put(bound("GREATER_THAN_OR_EQUAL", lat - dLat))
                                    .put(bound("LESS_THAN_OR_EQUAL", lat + dLat))
                            )
                    )
                )
                .put("limit", 1000)
        )
        val arr = JSONArray(Http.call("POST", "$base:runQuery", query.toString()))
        val out = ArrayList<Doc>()
        for (i in 0 until arr.length()) {
            val doc = arr.getJSONObject(i).optJSONObject("document") ?: continue
            parse(doc)?.let { if (Geo.distance(lat, lon, it.lat, it.lon) <= radiusM) out += it }
        }
        return out
    }

    private fun parse(doc: JSONObject): Doc? {
        val f = doc.optJSONObject("fields") ?: return null
        fun dbl(k: String): Double? {
            val v = f.optJSONObject(k) ?: return null
            return when {
                v.has("doubleValue") -> v.getDouble("doubleValue")
                v.has("integerValue") -> v.getString("integerValue").toDoubleOrNull()
                else -> null
            }
        }
        fun str(k: String): String = f.optJSONObject(k)?.optString("stringValue", "") ?: ""
        fun bool(k: String): Boolean? {
            val v = f.optJSONObject(k) ?: return null
            return if (v.has("booleanValue")) v.getBoolean("booleanValue") else null
        }
        fun voters(k: String): Set<String> {
            val m = f.optJSONObject(k)?.optJSONObject("mapValue")?.optJSONObject("fields") ?: return emptySet()
            val out = HashSet<String>()
            val it = m.keys()
            while (it.hasNext()) out += it.next()
            return out
        }
        return Doc(
            id = doc.getString("name").substringAfterLast("/"),
            source = str("source"),
            lat = dbl("lat") ?: return null,
            lon = dbl("lon") ?: return null,
            name = str("name"),
            kind = Kind.parse(str("kind")),
            fee = bool("fee"),
            h24 = bool("h24") ?: false,
            wheelchair = bool("wheelchair"),
            baby = bool("baby"),
            confirmVoters = voters("confirmVoters"),
            removeVoters = voters("removeVoters")
        )
    }

    // ---------- write ----------

    private fun v(d: Double) = JSONObject().put("doubleValue", d)
    private fun v(s: String) = JSONObject().put("stringValue", s)
    private fun v(b: Boolean) = JSONObject().put("booleanValue", b)
    private fun voterMap(uid: String?) = JSONObject().put(
        "mapValue",
        if (uid == null) JSONObject() else JSONObject().put("fields", JSONObject().put(uid, v(true)))
    )

    /** Adds a new toilet for everyone. Returns the new document id. */
    fun add(
        lat: Double, lon: Double, name: String, kind: Kind,
        fee: Boolean?, h24: Boolean, wheelchair: Boolean?, baby: Boolean?
    ): String {
        val headers = auth()
        val me = uid!!
        val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val fields = JSONObject()
            .put("source", v("user"))
            .put("lat", v(lat))
            .put("lon", v(lon))
            .put("name", v(name.take(80)))
            .put("kind", v(kind.name))
            .put("h24", v(h24))
            .put("createdBy", v(me))
            .put("createdAt", JSONObject().put("timestampValue", time))
            .put("confirmVoters", voterMap(me))
            .put("removeVoters", voterMap(null))
        fee?.let { fields.put("fee", v(it)) }
        wheelchair?.let { fields.put("wheelchair", v(it)) }
        baby?.let { fields.put("baby", v(it)) }

        val res = JSONObject(Http.call("POST", "$base/places", JSONObject().put("fields", fields).toString(), headers = headers))
        return res.getString("name").substringAfterLast("/")
    }

    /** One vote per person: confirm (still here) or remove. Changing your mind moves your vote. */
    fun vote(t: Toilet, remove: Boolean) {
        val headers = auth()
        val me = uid!!
        val fields = JSONObject()
        val mask = mutableListOf("confirmVoters.$me", "removeVoters.$me")
        if (remove) fields.put("removeVoters", voterMap(me)) else fields.put("confirmVoters", voterMap(me))
        if (!t.community) {
            fields.put("source", v("osm")).put("lat", v(t.lat)).put("lon", v(t.lon))
            mask += listOf("source", "lat", "lon")
        }
        val query = mask.joinToString("&") { "updateMask.fieldPaths=" + URLEncoder.encode(it, "UTF-8") }
        Http.call("PATCH", "$base/places/${t.id}?$query", JSONObject().put("fields", fields).toString(), headers = headers)
    }
}
