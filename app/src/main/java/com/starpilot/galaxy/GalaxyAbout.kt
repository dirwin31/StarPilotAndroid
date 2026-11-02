package com.starpilot.galaxy

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.sin
import kotlin.math.cos
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
            setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), (8 * dp).toInt())
            addView(StarfieldView(activity).apply {
                background = GradientDrawable().apply {
                    setColor(Palette.SPACE)
                    cornerRadius = 20 * dp
                }
                clipToOutline = true
            }, LinearLayout.LayoutParams(-1, (260 * dp).toInt()))
            addView(TextView(activity).apply {
                setText(R.string.starfield_signature)
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Palette.VIOLET_SOFT)
                setPadding(0, (20 * dp).toInt(), 0, (8 * dp).toInt())
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
    // Loose, broken clouds of tiny stars suggest initials only in the background density.
    // They have no connecting strokes, uniform outline, or bright letter-shaped anchors.
    private val signatureDust = List(100) { index ->
        val t = random.nextFloat()
        val angle = (t * Math.PI - Math.PI / 2).toFloat()
        val (x, y) = when (index % 4) {
            0 -> .15f + .025f * sin(t * 5f) to .13f + t * .32f
            1 -> .16f + .21f * cos(angle) to .29f + .16f * sin(angle)
            2 -> .63f + .025f * t to .56f + t * .31f
            else -> .65f + .20f * cos(angle) to .65f + .09f * sin(angle)
        }
        floatArrayOf(x + (random.nextFloat() - .5f) * .10f,
            y + (random.nextFloat() - .5f) * .09f, random.nextFloat(), random.nextFloat())
    }
    // An independent, asymmetric foreground constellation crosses the star clouds.
    private val constellation = arrayOf(
        floatArrayOf(.16f, .60f), floatArrayOf(.31f, .46f), floatArrayOf(.43f, .57f),
        floatArrayOf(.53f, .35f), floatArrayOf(.66f, .45f), floatArrayOf(.80f, .30f),
        floatArrayOf(.75f, .66f)
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
            val inset = 12 * dp
            canvas.drawCircle(inset + star[0] * (width - 2 * inset),
                inset + star[1] * (height - 2 * inset), (.5f + star[2]) * dp, paint)
        }
        for (star in signatureDust) {
            val brightness = (75 + 35 * sin(phase + star[3] * 6.283185f)).toInt()
            paint.color = Palette.alpha(Palette.VIOLET_SOFT, brightness)
            canvas.drawCircle(star[0] * width, star[1] * height, (.35f + star[2] * .65f) * dp, paint)
        }
        paint.strokeWidth = .8f * dp
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Palette.alpha(Palette.VIOLET_SOFT, 85)
        for (i in 1 until constellation.size) {
            val a = constellation[i - 1]
            val b = constellation[i]
            canvas.drawLine(a[0] * width, a[1] * height, b[0] * width, b[1] * height, paint)
        }
        for ((i, star) in constellation.withIndex()) {
            val glow = (22 + 8 * sin(phase + i)).toInt()
            paint.color = Palette.alpha(Palette.CYAN, glow)
            canvas.drawCircle(star[0] * width, star[1] * height, 9 * dp, paint)
            paint.color = Palette.CYAN
            canvas.drawCircle(star[0] * width, star[1] * height, 2.3f * dp, paint)
            paint.color = Palette.ON
            canvas.drawCircle(star[0] * width, star[1] * height, .85f * dp, paint)
        }
    }
}
