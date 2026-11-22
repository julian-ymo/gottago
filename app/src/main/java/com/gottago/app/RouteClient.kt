package com.gottago.app

import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.IOException

data class Route(
    val distance: Double,
    val duration: Double,
    val points: List<GeoPoint>,
    val step: String,
    val stepSub: String
)

/** Free walking directions from the OpenStreetMap routing service (OSRM, foot profile). */
object RouteClient {
    fun walk(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Route {
        val url = "https://routing.openstreetmap.de/routed-foot/route/v1/foot/" +
            "$lon1,$lat1;$lon2,$lat2?overview=full&geometries=geojson&steps=true"
        val j = JSONObject(Http.call("GET", url))
        if (j.optString("code") != "Ok") throw IOException("No route")
        val route = j.getJSONArray("routes").getJSONObject(0)

        val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
        val points = ArrayList<GeoPoint>(coords.length())
        for (i in 0 until coords.length()) {
            val c = coords.getJSONArray(i)
            points += GeoPoint(c.getDouble(1), c.getDouble(0))
        }

        val steps = route.getJSONArray("legs").getJSONObject(0).getJSONArray("steps")
        var step = "Head towards the pin"
        var sub = ""
        if (steps.length() > 0) {
            val first = steps.getJSONObject(0)
            val street = first.optString("name")
            step = "Walk ${Geo.fmt(first.getDouble("distance"))}" + if (street.isNotBlank()) " along $street" else ""
            if (steps.length() > 1) {
                val m = steps.getJSONObject(1).getJSONObject("maneuver")
                val type = m.optString("type")
                val mod = m.optString("modifier")
                sub = when {
                    type == "arrive" && (mod == "left" || mod == "right") -> "then the toilet is on your $mod"
                    type == "arrive" -> "then you've arrived"
                    else -> "then " + turn(mod)
                }
            }
        }
        return Route(route.getDouble("distance"), route.getDouble("duration"), points, step, sub)
    }

    private fun turn(mod: String) = when (mod) {
        "left" -> "turn left"
        "right" -> "turn right"
        "slight left" -> "bear left"
        "slight right" -> "bear right"
        "sharp left" -> "turn sharp left"
        "sharp right" -> "turn sharp right"
        "uturn" -> "turn around"
        else -> "go straight on"
    }
}
