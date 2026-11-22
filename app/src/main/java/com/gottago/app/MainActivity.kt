package com.gottago.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var tvStatus: TextView
    private lateinit var card: LinearLayout
    private lateinit var tvNearName: TextView
    private lateinit var tvNearMeta: TextView

    private val markers = mutableListOf<Marker>()
    private var userMarker: Marker? = null
    private var loading = false
    private var afterLoad: (() -> Unit)? = null

    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (LocationHelper.hasPermission(this)) refresh()
        else tvStatus.text = "Allow location so GottaGo can find toilets near you"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        map = findViewById(R.id.map)
        tvStatus = findViewById(R.id.tvStatus)
        card = findViewById(R.id.cardNearest)
        tvNearName = findViewById(R.id.tvNearName)
        tvNearMeta = findViewById(R.id.tvNearMeta)

        val title = SpannableString("GottaGo")
        title.setSpan(ForegroundColorSpan(MapUtil.YELLOW), 5, 7, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        findViewById<TextView>(R.id.tvTitle).text = title

        MapUtil.setup(map)
        map.controller.setZoom(16.0)
        map.controller.setCenter(GeoPoint(25.2048, 55.2708))

        findViewById<View>(R.id.btnEmergency).setOnClickListener { emergency() }
        findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener { refresh() }
        findViewById<ImageButton>(R.id.btnCenter).setOnClickListener {
            if (Repo.hasLocation) {
                map.controller.setZoom(17.0)
                map.controller.animateTo(GeoPoint(Repo.userLat, Repo.userLon))
            } else refresh()
        }
        findViewById<View>(R.id.btnAdd).setOnClickListener {
            when {
                !Community.enabled -> Ui.setupNeeded(this)
                !Repo.hasLocation -> Toast.makeText(this, "Waiting for your location…", Toast.LENGTH_SHORT).show()
                else -> startActivity(Intent(this, AddToiletActivity::class.java))
            }
        }
        card.setOnClickListener {
            Repo.ranked().firstOrNull()?.let { openDetails(it) }
        }

        if (LocationHelper.hasPermission(this)) refresh()
        else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        if (Repo.toilets.isNotEmpty()) render(fit = false) // reflects new adds / votes
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
    }

    private fun refresh() {
        if (loading) return
        if (!LocationHelper.hasPermission(this)) {
            permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        loading = true
        tvStatus.text = "Finding your location…"
        lifecycleScope.launch {
            try {
                val loc = LocationHelper.current(this@MainActivity)
                if (loc == null) {
                    tvStatus.text = "Can't get your location. Turn on GPS and tap refresh."
                    afterLoad = null
                    return@launch
                }
                Repo.setUser(loc.latitude, loc.longitude)
                showUser()
                map.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
                tvStatus.text = "Searching for toilets nearby…"
                Repo.search(loc.latitude, loc.longitude)
                render(fit = true)
                afterLoad?.invoke()
            } catch (e: Exception) {
                tvStatus.text = "Couldn't load toilets. Check your internet and tap refresh."
            } finally {
                afterLoad = null
                loading = false
            }
        }
    }

    private fun showUser() {
        userMarker?.let { map.overlays.remove(it) }
        userMarker = MapUtil.userMarker(map, Repo.userLat, Repo.userLon).also { map.overlays.add(it) }
        map.invalidate()
    }

    private fun render(fit: Boolean) {
        markers.forEach { map.overlays.remove(it) }
        markers.clear()
        val best = Repo.ranked().firstOrNull()

        // draw others first so the best pin sits on top
        Repo.toilets.sortedBy { it.id == best?.id }.forEach { t ->
            val m = MapUtil.toiletMarker(map, t, t.id == best?.id) { openDetails(it) }
            markers += m
            map.overlays.add(m)
        }
        if (Repo.hasLocation) showUser()
        map.invalidate()

        val within500 = Repo.toilets.count { it.confirmed && it.distanceM <= 500 }
        tvStatus.text = when {
            Repo.toilets.isEmpty() -> "No toilets found within 5 km"
            within500 == 1 -> "1 toilet within 500 m of you"
            within500 > 1 -> "$within500 toilets within 500 m of you"
            else -> "Nearest is ${Geo.fmt(Repo.toilets.first().distanceM)} away"
        }

        if (best != null) {
            card.visibility = View.VISIBLE
            tvNearName.text = best.name
            tvNearMeta.text = best.meta()
            if (fit) MapUtil.fit(map, GeoPoint(Repo.userLat, Repo.userLon), GeoPoint(best.lat, best.lon))
        } else {
            card.visibility = View.GONE
        }
    }

    private fun emergency() {
        val stale = System.currentTimeMillis() - Repo.searchedAt > 3 * 60_000
        if (Repo.toilets.isEmpty() || stale) {
            Toast.makeText(this, "Finding the closest toilet…", Toast.LENGTH_SHORT).show()
            afterLoad = { goEmergency() }
            if (!loading) {
                val pending = afterLoad
                refresh()
                afterLoad = pending
            }
        } else {
            goEmergency()
        }
    }

    private fun goEmergency() {
        val best = Repo.ranked().firstOrNull()
        if (best == null) {
            Toast.makeText(this, "No open toilets found within 5 km", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, EmergencyActivity::class.java).putExtra("id", best.id).putExtra("rank", 0))
    }

    private fun openDetails(t: Toilet) {
        startActivity(Intent(this, DetailsActivity::class.java).putExtra("id", t.id))
    }
}
