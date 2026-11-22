package com.gottago.app

import android.app.Application
import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val c = Configuration.getInstance()
        c.load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        c.userAgentValue = "GottaGo/1.0 ($packageName)" // required by the OpenStreetMap tile policy
        c.osmdroidBasePath = File(cacheDir, "osmdroid")
        c.osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        Community.init(this)
    }
}

object Ui {
    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    /** Pill-shaped chip; isSelected switches it to yellow. */
    fun chip(ctx: Context, text: String, selected: Boolean = false): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = dp(ctx, 38)
            setPadding(dp(ctx, 14), 0, dp(ctx, 14), 0)
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_chip)
            setTextColor(ContextCompat.getColorStateList(ctx, R.color.chip_text))
            isSelected = selected
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(ctx, 8) }
        }

    fun setupNeeded(ctx: Context) {
        AlertDialog.Builder(ctx)
            .setTitle("Community features are off")
            .setMessage("Adding and removing toilets needs the free Firebase setup described in the README. Finding toilets works without it.")
            .setPositiveButton("OK", null)
            .show()
    }
}
