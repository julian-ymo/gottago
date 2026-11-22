package com.gottago.app

import java.util.Calendar

/**
 * Small reader for OpenStreetMap opening_hours, e.g.
 * "24/7", "Mo-Su 08:00-22:00", "Mo-Fr 08:00-20:00; Sa 10:00-18:00; Su off", "10:00-01:00".
 * Anything it doesn't understand returns UNKNOWN (never wrongly "closed").
 */
object OpeningHours {
    private val dayNames = listOf("mo", "tu", "we", "th", "fr", "sa", "su")

    fun state(spec: String?, cal: Calendar = Calendar.getInstance()): OpenState {
        if (spec.isNullOrBlank()) return OpenState.UNKNOWN
        val s = spec.trim().lowercase()
        if (s == "24/7" || s == "mo-su 00:00-24:00") return OpenState.OPEN
        return try {
            evaluate(s, cal)
        } catch (e: Exception) {
            OpenState.UNKNOWN
        }
    }

    private fun evaluate(s: String, cal: Calendar): OpenState {
        val schedule = arrayOfNulls<List<Pair<Int, Int>>>(7)
        var parsedAny = false

        for (raw in s.split(";")) {
            val rule = raw.trim()
            if (rule.isEmpty()) continue
            if (rule.startsWith("ph") || rule.startsWith("sh")) continue // public/school holidays: ignore

            val firstDigit = rule.indexOfFirst { it.isDigit() }
            val dayPart: String
            val timePart: String
            when {
                firstDigit == 0 -> { dayPart = ""; timePart = rule }
                firstDigit < 0 -> {
                    if (!(rule.endsWith("off") || rule.endsWith("closed"))) throw IllegalArgumentException(rule)
                    dayPart = rule.removeSuffix("off").removeSuffix("closed").trim()
                    timePart = "off"
                }
                else -> {
                    dayPart = rule.substring(0, firstDigit).trim().trimEnd(':')
                    timePart = rule.substring(firstDigit).trim()
                }
            }

            val days = if (dayPart.isEmpty()) (0..6).toList() else parseDays(dayPart)
            val intervals = if (timePart == "off" || timePart == "closed") emptyList() else parseTimes(timePart)
            days.forEach { schedule[it] = intervals }
            parsedAny = true
        }
        if (!parsedAny) return OpenState.UNKNOWN

        val today = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Monday = 0
        val yesterday = (today + 6) % 7
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        schedule[today]?.forEach { (a, b) ->
            if (b > a && now in a until b) return OpenState.OPEN
            if (b <= a && now >= a) return OpenState.OPEN // runs past midnight
        }
        schedule[yesterday]?.forEach { (a, b) ->
            if (b <= a && now < b) return OpenState.OPEN // yesterday's late opening still going
        }
        return OpenState.CLOSED
    }

    private fun parseDays(part: String): List<Int> {
        val out = mutableListOf<Int>()
        for (token in part.split(",")) {
            val t = token.trim()
            if (t.isEmpty()) continue
            if (t.contains("-")) {
                val (a, b) = t.split("-").map { day(it.trim()) }
                var d = a
                while (true) {
                    out += d
                    if (d == b) break
                    d = (d + 1) % 7
                }
            } else {
                out += day(t)
            }
        }
        return out
    }

    private fun day(t: String): Int {
        val i = dayNames.indexOf(t.take(2))
        if (i < 0) throw IllegalArgumentException(t)
        return i
    }

    private fun parseTimes(part: String): List<Pair<Int, Int>> =
        part.split(",").map { range ->
            val r = range.trim()
            val openEnded = r.endsWith("+")
            val pieces = r.removeSuffix("+").split("-")
            val start = minutes(pieces[0])
            val end = if (openEnded || pieces.size < 2) 24 * 60 else minutes(pieces[1])
            Pair(start, end)
        }

    private fun minutes(t: String): Int {
        val (h, m) = t.trim().split(":").map { it.trim().toInt() }
        if (h !in 0..48 || m !in 0..59) throw IllegalArgumentException(t)
        return (h * 60 + m).coerceAtMost(24 * 60)
    }
}
