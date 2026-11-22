package com.gottago.app

import android.content.Intent
import android.location.LocationListener
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.max
import kotlin.math.roundToInt

class EmergencyActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var toilet: Toilet
    private lateinit var tvDistance: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvStep: TextView
    private lateinit var tvStepSub: TextView
    private var userMarker: Marker? = null
    private var listener: LocationListener? = null
    private var route: Route? = null
    private var rank = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val t = Repo.find(intent.getStringExtra("id"))
        if (t == null) { finish(); return }
        toilet = t
        rank = intent.getIntExtra("rank", 0)

        setContentView(R.layout.activity_emergency)
        window.statusBarColor = 0xFFC9341A.toInt()

        map = findViewById(R.id.map)
        tvDistance = findViewById(R.id.tvDistance)
        tvTime = findViewById(R.id.tvTime)
        tvStep = findViewById(R.id.tvStep)
        tvStepSub = findViewById(R.id.tvStepSub)

        findViewById<TextView>(R.id.tvHeadline).text = when {
            !t.confirmed -> "Closest likely toilet"
            rank > 0 -> "Next closest toilet"
            else -> "Closest open toilet found"
        }
        val sub = findViewById<TextView>(R.id.tvSub)
        sub.text = listOfNotNull(t.name, t.feeText(), t.openText(), if (!t.confirmed) "ask staff" else null)
            .joinToString(" · ") + "  ›"
        sub.setOnClickListener {
            startActivity(Intent(this, DetailsActivity::class.java).putExtra("id", t.id))
        }

        findViewById<TextView>(R.id.btnCancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnGoogle).setOnClickListener { openGoogleMaps() }
        findViewById<Button>(R.id.btnNext).setOnClickListener { nextClosest() }

        MapUtil.setup(map)
        map.overlays.add(MapUtil.toiletMarker(map, t, best = true, onClick = null))
        moveUser(Repo.userLat, Repo.userLon)
        MapUtil.fit(map, GeoPoint(Repo.userLat, Repo.userLon), GeoPoint(t.lat, t.lon))
        showStraight(t.distanceM)

        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { RouteClient.walk(Repo.userLat, Repo.userLon, t.lat, t.lon) }.getOrNull()
            }
            if (r != null) showRoute(r)
            else {
                tvStep.text = "Head towards the yellow pin"
                tvStepSub.text = "Walking route unavailable – try Google Maps"
                drawLine(listOf(GeoPoint(Repo.userLat, Repo.userLon), GeoPoint(t.lat, t.lon)))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::map.isInitialized) return
        map.onResume()
        listener = LocationHelper.start(this) { loc ->
            Repo.setUser(loc.latitude, loc.longitude)
            moveUser(loc.latitude, loc.longitude)
            val left = Geo.distance(loc.latitude, loc.longitude, toilet.lat, toilet.lon)
            if (left < 25) {
                tvDistance.text = "Here!"
                tvTime.text = "0 min"
                tvStep.text = "You've arrived"
                tvStepSub.text = "Look around for signs – good luck!"
            } else if (route == null || left < (route?.distance ?: 0.0) * 0.8) {
                showStraight(left)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (!::map.isInitialized) return
        map.onPause()
        LocationHelper.stop(this, listener)
        listener = null
    }

    private fun showStraight(m: Double) {
        tvDistance.text = Geo.fmt(m)
        tvTime.text = "${Geo.walkMin(m)} min"
    }

    private fun showRoute(r: Route) {
        route = r
        tvDistance.text = Geo.fmt(r.distance)
        tvTime.text = "${max(1, (r.duration / 60.0).roundToInt())} min"
        tvStep.text = r.step
        tvStepSub.text = r.stepSub
        drawLine(r.points)
    }

    private fun drawLine(points: List<GeoPoint>) {
        val line = Polyline(map)
        line.setPoints(points)
        line.outlinePaint.color = MapUtil.YELLOW
        line.outlinePaint.strokeWidth = Ui.dp(this, 6).toFloat()
        line.outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
        line.setOnClickListener { _, _, _ -> true }
        map.overlays.add(0, line)
        map.invalidate()
    }

    private fun moveUser(lat: Double, lon: Double) {
        userMarker?.let { map.overlays.remove(it) }
        userMarker = MapUtil.userMarker(map, lat, lon).also { map.overlays.add(it) }
        map.invalidate()
    }

    private fun openGoogleMaps() {
        val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${toilet.lat},${toilet.lon}&travelmode=walking")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(this, "No maps app found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun nextClosest() {
        val next = Repo.ranked().getOrNull(rank + 1)
        if (next == null) {
            Toast.makeText(this, "No other open toilets nearby", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, EmergencyActivity::class.java).putExtra("id", next.id).putExtra("rank", rank + 1))
        finish()
    }
}
