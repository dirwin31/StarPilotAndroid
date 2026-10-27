package com.starpilot.auto

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/** Small phone status page; Android Auto remains the only place for car controls. */
class ReviewSetupActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var statusMark: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var recheckButton: Button
    private lateinit var openButton: Button
    private lateinit var modeDetail: TextView
    private lateinit var modeButton: Button
    private var checkId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(36), dp(24), dp(32))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(getString(R.string.app_name), 25f, 0xFFF3F2F8.toInt()))
            addView(label(getString(R.string.review_subtitle), 14f, 0xFF9D9BB8.toInt()))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(14)
        })
        content.addView(header)

        val connection = card()
        connection.addView(label(getString(R.string.review_connection_heading), 18f, 0xFFF3F2F8.toInt()))
        val status = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(18), 0, dp(12))
        }
        statusMark = label("•", 28f, 0xFF9D72FF.toInt())
        status.addView(statusMark, LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT))
        status.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            statusTitle = label(getString(R.string.review_checking), 16f, 0xFFF3F2F8.toInt())
            statusDetail = label(getString(R.string.review_checking_detail), 13f, 0xFF9D9BB8.toInt())
            addView(statusTitle)
            addView(statusDetail)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        connection.addView(status)
        recheckButton = actionButton(getString(R.string.review_recheck), true) { checkConnection() }
        connection.addView(recheckButton, fullWidth())
        openButton = actionButton(getString(R.string.review_open_galaxy), false) { openGalaxy() }
        connection.addView(openButton, fullWidth().apply { topMargin = dp(8) })
        content.addView(connection, fullWidth().apply { topMargin = dp(28) })

        val mode = card()
        mode.addView(label(getString(R.string.review_mode_heading), 18f, 0xFFF3F2F8.toInt()))
        modeDetail = label("", 14f, 0xFFB9B7CA.toInt()).apply {
            setPadding(0, dp(10), 0, dp(14))
        }
        mode.addView(modeDetail)
        modeButton = actionButton("", false) {
            ReviewMode.setActive(this, !ReviewMode.isActive(this))
            showMode()
        }
        mode.addView(modeButton, fullWidth())
        content.addView(mode, fullWidth().apply { topMargin = dp(16) })

        content.addView(label(getString(R.string.review_car_hint), 14f, 0xFF9D9BB8.toInt()).apply {
            setPadding(0, dp(22), 0, 0)
        })
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(0xFF070712.toInt())
            addView(content)
        })
        showMode()
    }

    override fun onResume() {
        super.onResume()
        checkConnection()
    }

    override fun onDestroy() {
        checkId++
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun showMode() {
        val active = ReviewMode.isActive(this)
        modeDetail.setText(if (active) R.string.review_mode_active else R.string.review_mode_intro)
        modeButton.setText(if (active) R.string.exit_test_mode else R.string.enter_test_mode)
    }

    private fun checkConnection() {
        val current = ++checkId
        statusMark.text = "•"
        statusMark.setTextColor(0xFF9D72FF.toInt())
        statusTitle.setText(R.string.review_checking)
        statusDetail.setText(R.string.review_checking_detail)
        recheckButton.isEnabled = false
        worker.execute {
            val installed = runCatching {
                packageManager.getPackageUid(GalaxyBridgeClient.PRIMARY_PACKAGE, 0)
            }.isSuccess
            val outcome = runCatching {
                GalaxyBridgeClient(applicationContext).use { it.handshake() }
            }
            runOnUiThread {
                if (current != checkId || isFinishing || isDestroyed) return@runOnUiThread
                recheckButton.isEnabled = true
                openButton.setText(if (installed) R.string.review_open_galaxy else R.string.review_get_galaxy)
                if (outcome.isSuccess) {
                    statusMark.text = "✓"
                    statusMark.setTextColor(0xFF70D7A5.toInt())
                    statusTitle.setText(R.string.review_connected)
                    statusDetail.setText(R.string.review_connected_detail)
                } else {
                    statusMark.text = "!"
                    statusMark.setTextColor(0xFFFFB87D.toInt())
                    statusTitle.setText(if (installed) R.string.review_unavailable else R.string.review_not_found)
                    statusDetail.text = if (installed) {
                        outcome.exceptionOrNull()?.message ?: getString(R.string.review_unavailable_detail)
                    } else getString(R.string.review_not_found_detail)
                }
            }
        }
    }

    private fun openGalaxy() {
        packageManager.getLaunchIntentForPackage(GalaxyBridgeClient.PRIMARY_PACKAGE)?.let {
            startActivity(it)
            return
        }
        val packageId = GalaxyBridgeClient.PRIMARY_PACKAGE
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageId"))
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageId"))
        runCatching { startActivity(market) }.onFailure { runCatching { startActivity(web) } }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(20))
        background = GradientDrawable().apply {
            setColor(0xFF110E1F.toInt())
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), 0x1FFFFFFF)
        }
    }

    private fun actionButton(title: String, primary: Boolean, onClick: () -> Unit) =
        Button(this).apply {
            text = title
            isAllCaps = false
            setTextColor(if (primary) 0xFF070712.toInt() else Color.WHITE)
            background = RippleDrawable(
                ColorStateList.valueOf(0x40FFFFFF),
                GradientDrawable().apply {
                    cornerRadius = dp(26).toFloat()
                    setColor(if (primary) 0xFF9D72FF.toInt() else 0xFF302640.toInt())
                }, null
            )
            minHeight = dp(52)
            setOnClickListener { onClick() }
        }

    private fun label(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun fullWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
