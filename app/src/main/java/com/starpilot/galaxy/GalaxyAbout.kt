package com.starpilot.galaxy

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.sin
import kotlin.random.Random

internal object GalaxyAbout {
    fun showAbout(activity: Activity) {
        var taps = 0
        var lastTap = 0L
        val dp = activity.resources.displayMetrics.density
        val version = TextView(activity).apply {
            text = activity.getString(R.string.galaxy_version, BuildConfig.VERSION_NAME)
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Palette.ON)
            setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
            isFocusable = true
        }
        val about = AlertDialog.Builder(activity, R.style.AppDialog)
            .setTitle(R.string.about_galaxy)
            .setView(version)
            .setPositiveButton(R.string.starfield_close, null)
            .create()
        version.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            taps = if (now - lastTap > 3000) 1 else taps + 1
            lastTap = now
            if (taps == 7) {
                taps = 0
                about.dismiss()
                showStars(activity)
            }
        }
        about.show()
    }

    private fun showStars(activity: Activity) {
        val dp = activity.resources.displayMetrics.density
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.SPACE)
            addView(StarfieldView(activity), LinearLayout.LayoutParams(-1, (260 * dp).toInt()))
            addView(TextView(activity).apply {
                setText(R.string.starfield_signature)
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Palette.VIOLET_SOFT)
                setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), (24 * dp).toInt())
            }, LinearLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(activity, R.style.AppDialog)
            .setTitle(R.string.starfield_title)
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton(R.string.starfield_close, null)
            .show()
    }
}

private class StarfieldView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val random = Random(0x535041)
    private val stars = List(100) { floatArrayOf(random.nextFloat(), random.nextFloat(), random.nextFloat(), random.nextFloat()) }
    // A fixed seven-star signature, shared by every official edition.
    private val constellation = arrayOf(
        floatArrayOf(.22f, .58f), floatArrayOf(.34f, .36f), floatArrayOf(.46f, .49f),
        floatArrayOf(.56f, .25f), floatArrayOf(.66f, .43f), floatArrayOf(.77f, .33f),
        floatArrayOf(.70f, .68f)
    )
    private var phase = 0f
    private val shimmer = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
        duration = 8000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    private fun updateMotion() {
        if (isAttachedToWindow && windowVisibility == VISIBLE && isShown && ValueAnimator.areAnimatorsEnabled()) {
            if (!shimmer.isStarted) shimmer.start()
        } else shimmer.cancel()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateMotion() }
    override fun onDetachedFromWindow() { shimmer.cancel(); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); updateMotion() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Palette.SPACE)
        for (star in stars) {
            val brightness = (130 + 65 * sin(phase + star[3] * 6.283185f)).toInt()
            paint.color = Palette.alpha(Palette.ON, brightness)
            canvas.drawCircle(star[0] * width, star[1] * height, (.5f + star[2]) * dp, paint)
        }
        paint.strokeWidth = dp
        paint.color = Palette.alpha(Palette.VIOLET, 100)
        for (i in 1 until constellation.size) {
            val a = constellation[i - 1]
            val b = constellation[i]
            canvas.drawLine(a[0] * width, a[1] * height, b[0] * width, b[1] * height, paint)
        }
        for (star in constellation) {
            paint.color = Palette.alpha(Palette.CYAN, 24)
            canvas.drawCircle(star[0] * width, star[1] * height, 10 * dp, paint)
            paint.color = Palette.CYAN
            canvas.drawCircle(star[0] * width, star[1] * height, 2.5f * dp, paint)
        }
    }
}
