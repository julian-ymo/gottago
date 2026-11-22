package com.gottago.app

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    /** Straight-line distance in metres. */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    fun fmt(m: Double): String =
        if (m < 1000) "${(m / 10).roundToInt() * 10} m"
        else String.format(Locale.US, "%.1f km", m / 1000)

    /** Walking minutes at about 80 m per minute. */
    fun walkMin(m: Double): Int = max(1, ceil(m / 80.0).toInt())
}
