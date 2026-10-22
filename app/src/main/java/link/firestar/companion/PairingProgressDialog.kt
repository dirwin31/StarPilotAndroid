package link.firestar.companion

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

class PairingProgressDialog(activity: Activity, cancel: () -> Unit) {
    private val dialog = AlertDialog.Builder(activity, R.style.AppDialog)
        .setTitle("Pairing with comma")
        .setCancelable(false)
        .setNegativeButton("Cancel") { _, _ -> cancel() }
        .create()
    private val context = dialog.context
    private fun px(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private val stages = listOf("Check comma", "Open pairing window", "Connect over Bluetooth", "Pair Galaxy", "Set up music")
    private val labels = stages.map { TextView(context).apply { textSize = 14f; setPadding(0, px(5), 0, px(5)) } }
    private val detail = TextView(context).apply {
        textSize = 15f
        setTextColor(Palette.ON)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private var stage = 0

    init {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(8), px(24), px(16))
            labels.forEach { addView(it) }
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, px(16), 0, 0)
                addView(ProgressBar(context).apply {
                    isIndeterminate = true
                    indeterminateTintList = ColorStateList.valueOf(Palette.VIOLET)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(px(22), px(22)).apply { marginEnd = px(12) })
                addView(detail, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
        dialog.setView(ScrollView(context).apply { addView(content) })
    }

    fun update(text: String, nextStage: Int? = null) {
        if (nextStage != null) stage = nextStage.coerceIn(stages.indices)
        labels.forEachIndexed { index, label ->
            label.text = "${if (index < stage) "✓" else "${index + 1}."} ${stages[index]}"
            label.setTextColor(when { index < stage -> Palette.CYAN; index == stage -> Palette.VIOLET_SOFT; else -> Palette.MUTED })
        }
        detail.text = text
        if (!dialog.isShowing) dialog.show()
    }

    fun dismiss() = dialog.dismiss()
}
