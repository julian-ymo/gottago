package com.gottago.app

enum class Kind(val label: String) {
    TOILET("Public toilet"),
    MALL("Mall"),
    MOSQUE("Mosque"),
    FUEL("Petrol station"),
    CAFE("Café / restaurant"),
    OTHER("Toilet");

    companion object {
        fun parse(s: String?): Kind = entries.firstOrNull { it.name == s } ?: TOILET
    }
}

enum class OpenState { OPEN, CLOSED, UNKNOWN }

/** One place where you can (or probably can) use a toilet. */
data class Toilet(
    val id: String,                 // "osm_node_123" for OpenStreetMap, Firestore id for user-added
    val lat: Double,
    val lon: Double,
    val name: String,
    val kind: Kind,
    val confirmed: Boolean,         // true = a toilet is mapped here; false = "likely" (mall, mosque, petrol station)
    val community: Boolean,         // added by a GottaGo user
    val fee: Boolean?,
    val wheelchair: Boolean?,
    val baby: Boolean?,
    val unisex: Boolean?,
    val openingHours: String?,
    val customersOnly: Boolean,
    var confirmVotes: Int = 0,
    var removeVotes: Int = 0,
    var myVote: String? = null      // "confirm", "remove" or null
) {
    var distanceM: Double = 0.0

    /** Removed for everyone once removal votes beat confirmations by 3. */
    val hidden: Boolean get() = removeVotes - confirmVotes >= 3

    /** User-added and not yet confirmed by 3 people. */
    val isNew: Boolean get() = community && confirmVotes < 3

    fun openState(): OpenState = OpeningHours.state(openingHours)

    fun feeText(): String? = when (fee) {
        true -> "Paid"
        false -> "Free"
        null -> null
    }

    fun openText(): String = when (openState()) {
        OpenState.OPEN -> if (openingHours?.trim() == "24/7") "Open 24h" else "Open now"
        OpenState.CLOSED -> "Closed now"
        OpenState.UNKNOWN -> "Hours unknown"
    }

    fun meta(): String = listOfNotNull(
        Geo.fmt(distanceM),
        feeText(),
        openText(),
        if (!confirmed) "Ask staff" else null,
        if (isNew) "New" else null
    ).joinToString(" · ")
}
