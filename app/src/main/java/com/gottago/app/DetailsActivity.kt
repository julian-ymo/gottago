package com.gottago.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
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

class DetailsActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var toilet: Toilet

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val t = Repo.find(intent.getStringExtra("id"))
        if (t == null) { finish(); return }
        toilet = t
        setContentView(R.layout.activity_details)

        map = findViewById(R.id.map)
        MapUtil.setup(map, interactive = false)
        map.controller.setZoom(17.5)
        map.controller.setCenter(GeoPoint(t.lat, t.lon))
        map.overlays.add(MapUtil.toiletMarker(map, t, best = true, onClick = null))
        if (Repo.hasLocation) map.overlays.add(MapUtil.userMarker(map, Repo.userLat, Repo.userLon))

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnGo).setOnClickListener {
            startActivity(Intent(this, EmergencyActivity::class.java).putExtra("id", t.id).putExtra("rank", 0))
        }
        findViewById<Button>(R.id.btnConfirm).setOnClickListener { confirm() }
        findViewById<Button>(R.id.btnRemove).setOnClickListener {
            if (!Community.enabled) Ui.setupNeeded(this)
            else startActivity(Intent(this, RemoveToiletActivity::class.java).putExtra("id", t.id))
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::map.isInitialized) return
        map.onResume()
        if (Repo.find(toilet.id) == null) { finish(); return } // removed meanwhile
        bind()
    }

    override fun onPause() {
        super.onPause()
        if (!::map.isInitialized) return
        map.onPause()
    }

    private fun bind() {
        val t = toilet
        findViewById<TextView>(R.id.tvName).text = t.name

        val state = t.openState()
        val color = when (state) {
            OpenState.OPEN -> MapUtil.GREEN
            OpenState.CLOSED -> 0xFFFF6B4A.toInt()
            OpenState.UNKNOWN -> 0xFFA9B4C6.toInt()
        }
        val meta = findViewById<TextView>(R.id.tvMeta)
        meta.text = "${t.openText()} · ${Geo.fmt(t.distanceM)} · ${Geo.walkMin(t.distanceM)} min walk"
        meta.setTextColor(color)

        val chips = findViewById<LinearLayout>(R.id.chips)
        chips.removeAllViews()
        listOfNotNull(
            t.feeText(),
            if (t.wheelchair == true) "Wheelchair access" else null,
            if (t.baby == true) "Baby changing" else null,
            if (t.unisex == true) "Unisex" else null,
            if (t.customersOnly) "Customers only" else null,
            if (!t.confirmed) "${t.kind.label} – ask staff" else null,
            if (t.isNew) "New – ${t.confirmVotes} of 3 confirmations" else null
        ).forEach { chips.addView(Ui.chip(this, it)) }

        val info = findViewById<LinearLayout>(R.id.infoCard)
        info.removeAllViews()
        row(info, "Type", t.kind.label)
        row(info, "Opening hours", t.openingHours ?: "Not known")
        row(info, "Fee", t.feeText() ?: "Not known")
        row(info, "Source", if (t.community) "Added by GottaGo users" else "OpenStreetMap")
        if (t.confirmVotes > 0 || t.removeVotes > 0) {
            row(info, "Community votes", "${t.confirmVotes} still here · ${t.removeVotes} remove")
        }

        val confirm = findViewById<Button>(R.id.btnConfirm)
        confirm.text = if (t.myVote == "confirm") "You confirmed it's here ✓" else "Still here? Confirm it"
        confirm.isEnabled = t.myVote != "confirm"
    }

    private fun row(parent: LinearLayout, label: String, value: String) {
        val v = LayoutInflater.from(this).inflate(android.R.layout.simple_list_item_2, parent, false)
        v.findViewById<TextView>(android.R.id.text1).apply {
            text = label; textSize = 13f; setTextColor(0xFFA9B4C6.toInt())
        }
        v.findViewById<TextView>(android.R.id.text2).apply {
            text = value; textSize = 15f; setTextColor(MapUtil.WHITE)
        }
        parent.addView(v)
    }

    private fun confirm() {
        if (!Community.enabled) { Ui.setupNeeded(this); return }
        val t = toilet
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { Community.vote(t, remove = false) }.isSuccess }
            if (ok) {
                if (t.myVote == "remove") t.removeVotes = maxOf(0, t.removeVotes - 1)
                if (t.myVote != "confirm") t.confirmVotes += 1
                t.myVote = "confirm"
                Toast.makeText(this@DetailsActivity, "Thanks! Confirmed for everyone.", Toast.LENGTH_SHORT).show()
                bind()
            } else {
                Toast.makeText(this@DetailsActivity, "Couldn't send. Check your internet.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
