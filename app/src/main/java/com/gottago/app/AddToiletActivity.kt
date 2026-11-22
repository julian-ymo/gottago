package com.gottago.app

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

class AddToiletActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private val kindChips = LinkedHashMap<Kind, TextView>()
    private lateinit var chipFree: TextView
    private lateinit var chip24: TextView
    private lateinit var chipWheel: TextView
    private lateinit var chipBaby: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add)

        map = findViewById(R.id.map)
        MapUtil.setup(map)
        map.controller.setZoom(18.5)
        map.controller.setCenter(GeoPoint(Repo.userLat, Repo.userLon))
        map.overlays.add(MapUtil.userMarker(map, Repo.userLat, Repo.userLon))

        // Fixed pin in the centre; its tip marks the spot, so lift it by half its height.
        val pin = findViewById<ImageView>(R.id.centerPin)
        val icon = MapUtil.pin(this, "+", MapUtil.GREEN, MapUtil.INK, 40f)
        pin.setImageDrawable(icon)
        pin.translationY = -icon.intrinsicHeight / 2f

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnMyLoc).setOnClickListener {
            map.controller.animateTo(GeoPoint(Repo.userLat, Repo.userLon))
        }

        val kinds = findViewById<LinearLayout>(R.id.kindChips)
        listOf(Kind.TOILET to "Public", Kind.MALL to "Mall", Kind.MOSQUE to "Mosque",
            Kind.FUEL to "Petrol station", Kind.CAFE to "Café / restaurant").forEach { (kind, label) ->
            val c = Ui.chip(this, label, kind == Kind.TOILET)
            c.setOnClickListener { kindChips.values.forEach { it.isSelected = false }; c.isSelected = true }
            kindChips[kind] = c
            kinds.addView(c)
        }

        val features = findViewById<LinearLayout>(R.id.featureChips)
        chipFree = toggle(features, "Free", true)
        chip24 = toggle(features, "24 hours", false)
        chipWheel = toggle(features, "Wheelchair", false)
        chipBaby = toggle(features, "Baby changing", false)

        findViewById<Button>(R.id.btnSubmit).setOnClickListener { submit(it as Button) }
    }

    private fun toggle(parent: LinearLayout, label: String, on: Boolean): TextView {
        val c = Ui.chip(this, label, on)
        c.setOnClickListener { c.isSelected = !c.isSelected }
        parent.addView(c)
        return c
    }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }

    private fun submit(button: Button) {
        val center = map.mapCenter
        val lat = center.latitude
        val lon = center.longitude
        if (Geo.distance(Repo.userLat, Repo.userLon, lat, lon) > 3000) {
            Toast.makeText(this, "You can only add toilets within 3 km of where you are", Toast.LENGTH_LONG).show()
            return
        }
        val kind = kindChips.entries.firstOrNull { it.value.isSelected }?.key ?: Kind.TOILET
        val name = findViewById<EditText>(R.id.etName).text.toString().trim()
        val free = if (chipFree.isSelected) false else null // fee = false means free
        val h24 = chip24.isSelected
        val wheel = if (chipWheel.isSelected) true else null
        val baby = if (chipBaby.isSelected) true else null

        button.isEnabled = false
        button.text = "Adding…"
        lifecycleScope.launch {
            val id = withContext(Dispatchers.IO) {
                runCatching { Community.add(lat, lon, name, kind, free, h24, wheel, baby) }.getOrNull()
            }
            if (id == null) {
                button.isEnabled = true
                button.text = "Add toilet for everyone"
                Toast.makeText(this@AddToiletActivity, "Couldn't add it. Check your internet and try again.", Toast.LENGTH_LONG).show()
                return@launch
            }
            Repo.addLocal(
                Toilet(
                    id = id, lat = lat, lon = lon,
                    name = name.ifBlank { kind.label },
                    kind = kind, confirmed = true, community = true,
                    fee = free, wheelchair = wheel, baby = baby, unisex = null,
                    openingHours = if (h24) "24/7" else null, customersOnly = false,
                    confirmVotes = 1, removeVotes = 0, myVote = "confirm"
                )
            )
            Toast.makeText(this@AddToiletActivity, "Added! Everyone can see it now.", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
