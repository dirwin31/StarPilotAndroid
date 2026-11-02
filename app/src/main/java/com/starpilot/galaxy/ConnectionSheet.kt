package com.starpilot.galaxy

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import kotlin.math.roundToInt

/** How the app reaches comma, as stored in the "transport" preference. */
enum class Transport(val key: String, val title: String, val icon: Int, val summary: String) {
    AUTO("auto", "Automatic", R.drawable.ic_route_auto, "Wi-Fi and LAN, then Remote, then Bluetooth."),
    WIFI("wifi", "Wi-Fi & LAN", R.drawable.ic_route_wifi, "Galaxy Wi-Fi or your home network. Never Bluetooth."),
    REMOTE("remote", "Remote", R.drawable.ic_route_auto, "Remote · firestar.link"),
    BLUETOOTH("bluetooth", "Bluetooth", R.drawable.ic_route_bluetooth, "Bluetooth only, for when there's no Wi-Fi in reach.");
    companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: AUTO }
}

/** Galaxy-styled bottom sheet with every connection choice, replacing the stock list dialog. */
class ConnectionSheet(private val activity: Activity, current: Transport, order: List<Transport> = Transport.entries.filter { it != Transport.AUTO }) {
    private val dp = activity.resources.displayMetrics.density
    private fun px(value: Number) = (value.toFloat() * dp).roundToInt()
    private val dialog = Dialog(activity)
    private val sheet: ScrollView
    private val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; setPadding(px(20), px(22), px(20), px(20)) }
    private val tiles = mutableMapOf<Transport, LinearLayout>()
    private val summary = text(13f, Palette.MUTED)
    private var selected = current
    private var order = order
    private var saveOrder: (List<Transport>) -> Unit = {}
    private val grid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }

    init {
        content.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(22f, Palette.ON, Palette.condensed).apply { setText(R.string.connections_title) },
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_chevron_down)
                imageTintList = ColorStateList.valueOf(Palette.ON)
                contentDescription = activity.getString(R.string.close_connections)
                isClickable = true; isFocusable = true
                setPadding(px(12), px(12), px(12), px(12))
                background = RippleDrawable(ColorStateList.valueOf(Palette.alpha(Palette.VIOLET, 70)),
                    GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.alpha(Palette.VIOLET, 45)) }, null)
                setOnClickListener { dismiss() }
            }, LinearLayout.LayoutParams(px(44), px(44)))
        })
        Transport.entries.forEach { tiles[it] = tile(it) }
        layoutTiles()
        content.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(16) })
        content.addView(summary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
        content.setOnApplyWindowInsetsListener { view, insets ->
            val bars = systemBarInsets(insets)
            view.setPadding(px(20) + bars.left, px(22), px(20) + bars.right, px(20) + bars.bottom)
            insets
        }
        paint()
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        sheet = object : ScrollView(activity) {
            private var startRawY = 0f
            private var dragging = false
            private val touchSlop = android.view.ViewConfiguration.get(activity).scaledTouchSlop

            override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawY = event.rawY
                        dragging = false
                    }
                    MotionEvent.ACTION_MOVE -> if (scrollY == 0 && event.rawY - startRawY > touchSlop) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
                return super.onInterceptTouchEvent(event)
            }

            override fun performClick(): Boolean {
                super.performClick()
                dismiss()
                return true
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (!dragging) return super.onTouchEvent(event)
                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> translationY = (event.rawY - startRawY).coerceAtLeast(0f)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val shouldDismiss = event.actionMasked == MotionEvent.ACTION_UP &&
                            event.rawY - startRawY > px(72)
                        dragging = false
                        if (shouldDismiss) performClick() else animate().translationY(0f).setDuration(180).start()
                    }
                }
                return true
            }
        }.apply {
            addView(content)
            background = GradientDrawable().apply {
                val r = px(28).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setColor(Palette.SURFACE); setStroke(px(1), Palette.LINE)
            }
        }
        dialog.setContentView(sheet)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
    }

    private fun layoutTiles() {
        tiles.values.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        grid.removeAllViews()
        (listOf(Transport.AUTO) + order).chunked(2).forEachIndexed { rowIndex, pair ->
            grid.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL; clipChildren = false
                pair.forEachIndexed { index, transport ->
                    addView(tiles.getValue(transport), LinearLayout.LayoutParams(0, px(72), 1f).apply {
                        if (index > 0) marginStart = px(8)
                    })
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { if (rowIndex > 0) topMargin = px(8) })
        }
    }

    /** Dashed outline drawn behind the tiles, marking the slot the dragged tile will land in. */
    private class SlotOutline(private val stroke: Float, private val color: Int, private val radius: Float) : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = stroke; this.color = color
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(stroke * 4, stroke * 3), 0f)
        }
        var slot: android.graphics.RectF? = null
        override fun draw(canvas: android.graphics.Canvas) { slot?.let { canvas.drawRoundRect(it, radius, radius, paint) } }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter }
        @Suppress("OVERRIDE_DEPRECATION") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    /**
     * Press and hold a tile other than Automatic, then drag it. The other tiles slide aside as you cross slots, and a
     * dashed outline marks where it will land; letting go saves that order as Automatic's preference.
     */
    private fun enableReorder(transport: Transport, tile: LinearLayout) {
        var dragging = false
        var downX = 0f; var downY = 0f
        var slots = listOf<Pair<Float, Float>>()
        var live = order
        val outline = SlotOutline(px(1.5f).toFloat(), Palette.VIOLET, px(18).toFloat())
        fun center(view: View) = IntArray(2).also { view.getLocationOnScreen(it) }.let { it[0] + view.width / 2f to it[1] + view.height / 2f }
        fun showSlot(index: Int) {
            val gridAt = IntArray(2).also { grid.getLocationOnScreen(it) }
            val (cx, cy) = slots[index]
            outline.slot = android.graphics.RectF(cx - gridAt[0] - tile.width / 2f, cy - gridAt[1] - tile.height / 2f,
                cx - gridAt[0] + tile.width / 2f, cy - gridAt[1] + tile.height / 2f)
            grid.invalidate()
        }
        fun settle(commit: Boolean) {
            dragging = false
            val reordered = if (commit) live else order
            val before = tiles.mapValues { it.value.animate().cancel(); center(it.value) }
            grid.background = null
            tile.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            if (reordered != order) { order = reordered; saveOrder(reordered) }
            layoutTiles(); paint()
            grid.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    grid.viewTreeObserver.removeOnPreDrawListener(this)
                    tiles.forEach { (t, view) ->
                        view.translationX = 0f; view.translationY = 0f
                        val now = center(view); val was = before.getValue(t)
                        view.translationX = was.first - now.first; view.translationY = was.second - now.second
                        view.animate().translationX(0f).translationY(0f).setDuration(180).start()
                    }
                    return true
                }
            })
        }
        tile.setOnLongClickListener {
            dragging = true
            live = order
            slots = (listOf(Transport.AUTO) + order).map { center(tiles.getValue(it)) }
            grid.background = outline
            showSlot(order.indexOf(transport) + 1)
            tile.parent.requestDisallowInterceptTouchEvent(true)
            tile.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            tile.bringToFront(); tile.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).start()
            tile.elevation = px(16).toFloat()
            true
        }
        tile.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY }
                MotionEvent.ACTION_MOVE -> if (dragging) {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    tile.translationX = dx; tile.translationY = dy
                    val start = slots[order.indexOf(transport) + 1]
                    val target = (1..order.size).minBy {
                        (slots[it].first - start.first - dx).let { x -> x * x } + (slots[it].second - start.second - dy).let { y -> y * y }
                    } - 1
                    if (live.indexOf(transport) != target) {
                        live = order.toMutableList().apply { remove(transport); add(target, transport) }
                        tile.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        showSlot(target + 1)
                        live.forEachIndexed { i, other ->
                            if (other == transport) return@forEachIndexed
                            val from = slots[order.indexOf(other) + 1]; val to = slots[i + 1]
                            tiles.getValue(other).animate().translationX(to.first - from.first).translationY(to.second - from.second).setDuration(160).start()
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                    settle(commit = event.actionMasked == MotionEvent.ACTION_UP)
                    return@setOnTouchListener true
                }
            }
            dragging
        }
    }

    /** Called with the new Automatic preference order after a drag. */
    fun onOrder(save: (List<Transport>) -> Unit) = apply { saveOrder = save }

    /** Called with the new mode after the selection has had a moment on screen. */
    fun onTransport(choose: (Transport) -> Unit) = apply {
        tiles.forEach { (transport, tile) ->
            tile.setOnClickListener {
                selected = transport; paint()
                tile.postDelayed({ dismiss(); choose(transport) }, 180)
            }
            if (transport != Transport.AUTO) enableReorder(transport, tile)
        }
    }

    fun group(title: String, build: Group.() -> Unit) = apply {
        content.addView(text(14f, Palette.VIOLET_SOFT, Palette.medium).apply { text = title },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(24); marginStart = px(4) })
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = px(18).toFloat(); setColor(Palette.SPACE); setStroke(px(1), Palette.LINE) }
        }
        Group(list).build()
        content.addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) })
        list.elevation = px(8).toFloat()
    }

    inner class Group(private val list: LinearLayout) {
        fun action(title: String, detail: String? = null, danger: Boolean = false, run: () -> Unit) {
            if (list.childCount > 0) list.addView(View(activity).apply { setBackgroundColor(Palette.LINE) },
                LinearLayout.LayoutParams(-1, px(1)).apply { marginStart = px(16) })
            list.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = px(56); setPadding(px(16), px(10), px(16), px(10))
                isClickable = true; isFocusable = true
                background = RippleDrawable(ColorStateList.valueOf(Palette.alpha(if (danger) Palette.ERROR else Palette.VIOLET, 60)), null, GradientDrawable().apply { setColor(Color.WHITE) })
                addView(text(16f, if (danger) Palette.ERROR else Palette.ON, Palette.medium).apply { text = title })
                if (detail != null) addView(text(13f, Palette.MUTED).apply { text = detail }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(2) })
                contentDescription = if (detail == null) title else "$title. $detail"
                setOnClickListener { dismiss(); run() }
            }, LinearLayout.LayoutParams(-1, -2))
        }
    }

    fun show() {
        if (activity.isFinishing || activity.isDestroyed) return
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        sheet.post {
            sheet.translationY = sheet.height.toFloat()
            sheet.animate().translationY(0f).setDuration(220).start()
        }
    }
    private fun dismiss() {
        if (!dialog.isShowing) return
        sheet.animate().translationY(sheet.height.toFloat()).setDuration(180)
            .withEndAction { if (dialog.isShowing) dialog.dismiss() }.start()
    }

    private fun tile(transport: Transport) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        isClickable = true; isFocusable = true
        addView(ImageView(activity).apply { setImageResource(transport.icon) }, LinearLayout.LayoutParams(px(24), px(24)))
        addView(text(14f, Palette.ON, Palette.medium).apply { text = transport.title; gravity = Gravity.CENTER; maxLines = 1 },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(8) })
    }

    private fun paint() {
        tiles.forEach { (transport, tile) ->
            val on = transport == selected
            val color = if (on) Palette.VIOLET_SOFT else Palette.MUTED
            tile.elevation = if (on) px(8).toFloat() else 0f
            (tile.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(color)
            (tile.getChildAt(1) as TextView).setTextColor(if (on) Palette.ON else Palette.MUTED)
            tile.isSelected = on
            tile.contentDescription = if (on) "${transport.title}, selected" else transport.title
            tile.background = RippleDrawable(ColorStateList.valueOf(Palette.alpha(Palette.VIOLET, 60)), GradientDrawable().apply {
                cornerRadius = px(18).toFloat()
                setColor(if (on) Palette.alpha(Palette.VIOLET, 40) else Palette.SPACE)
                setStroke(px(if (on) 1.5f else 1f), if (on) Palette.VIOLET else Palette.LINE)
            }, null)
        }
        val route = if (selected == Transport.AUTO) "Tries ${order.joinToString(" → ") { it.title }}." else selected.summary
        summary.text = "$route\nTo set Automatic's order, press and hold Wi-Fi & LAN, Remote or Bluetooth, then drag."
    }

    private fun text(size: Float, color: Int, face: android.graphics.Typeface? = null) = TextView(activity).apply {
        textSize = size; setTextColor(color); face?.let { typeface = it }
    }
}
