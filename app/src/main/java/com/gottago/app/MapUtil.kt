package com.gottago.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.infowindow.MarkerInfoWindow

object MapUtil {
    const val YELLOW = 0xFFFFD23F.toInt()
    const val WHITE = 0xFFF4F6FA.toInt()
    const val GREEN = 0xFF3DDC97.toInt()
    const val INK = 0xFF0E1624.toInt()
    const val BLUE = 0xFF4DA3FF.toInt()

    fun setup(map: MapView, interactive: Boolean = true) {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS) // dark map
        map.setMultiTouchControls(interactive)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.isTilesScaledToDpi = true
        map.setMinZoomLevel(3.0)
        map.setMaxZoomLevel(20.0)
        if (!interactive) map.setOnTouchListener { _, _ -> true }
        (map.parent as? android.view.View)?.clipToOutline = true
    }

    private fun dp(ctx: Context, v: Float) = v * ctx.resources.displayMetrics.density

    /** Map pin: a round head with a pointed tail, with a short label inside. */
    fun pin(ctx: Context, label: String, fill: Int, text: Int, sizeDp: Float = 36f, alpha: Int = 255): BitmapDrawable {
        val w = dp(ctx, sizeDp)
        val h = w * 1.3f
        val bmp = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill; this.alpha = alpha }
        val r = w / 2f
        val path = Path().apply {
            addCircle(r, r, r - 1, Path.Direction.CW)
            moveTo(r - r * 0.45f, r * 1.6f)
            lineTo(r, h)
            lineTo(r + r * 0.45f, r * 1.6f)
            close()
        }
        c.drawPath(path, p)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(ctx, 2f); color = INK; this.alpha = alpha
        }
        c.drawCircle(r, r, r - dp(ctx, 1.5f), border)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = text; this.alpha = alpha; textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD; textSize = w * (if (label.length > 2) 0.28f else 0.36f)
        }
        c.drawText(label, r, r - (tp.descent() + tp.ascent()) / 2f, tp)
        return BitmapDrawable(ctx.resources, bmp)
    }

    fun dot(ctx: Context): BitmapDrawable {
        val s = dp(ctx, 30f)
        val bmp = Bitmap.createBitmap(s.toInt(), s.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = s / 2f
        c.drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLUE; alpha = 60 })
        c.drawCircle(r, r, r * 0.45f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        c.drawCircle(r, r, r * 0.33f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLUE })
        return BitmapDrawable(ctx.resources, bmp)
    }

    fun toiletMarker(map: MapView, t: Toilet, best: Boolean, onClick: ((Toilet) -> Unit)?): Marker {
        val ctx = map.context
        val m = Marker(map)
        m.position = GeoPoint(t.lat, t.lon)
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        m.icon = when {
            best -> pin(ctx, "WC", YELLOW, INK, 42f)
            t.isNew -> pin(ctx, "NEW", GREEN, INK, 34f)
            !t.confirmed -> pin(ctx, "WC", WHITE, INK, 30f, 150)
            else -> pin(ctx, "WC", WHITE, INK, 34f)
        }
        m.setInfoWindow(null as MarkerInfoWindow?)
        m.setOnMarkerClickListener { _, _ ->
            onClick?.invoke(t)
            true
        }
        return m
    }

    fun userMarker(map: MapView, lat: Double, lon: Double): Marker {
        val m = Marker(map)
        m.position = GeoPoint(lat, lon)
        m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        m.icon = dot(map.context)
        m.setInfoWindow(null as MarkerInfoWindow?)
        m.setOnMarkerClickListener { _, _ -> true }
        return m
    }

    /** Zoom so both points are visible. */
    fun fit(map: MapView, a: GeoPoint, b: GeoPoint) {
        map.post {
            val d = Geo.distance(a.latitude, a.longitude, b.latitude, b.longitude)
            if (d < 60) {
                map.controller.setZoom(18.0)
                map.controller.setCenter(a)
            } else {
                val box = BoundingBox.fromGeoPoints(listOf(a, b)).increaseByScale(1.8f)
                map.zoomToBoundingBox(box, false)
            }
        }
    }
}
