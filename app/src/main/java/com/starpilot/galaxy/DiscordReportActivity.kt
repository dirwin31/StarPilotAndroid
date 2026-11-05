package com.starpilot.galaxy

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.view.View
import android.widget.*
import java.util.UUID

/** Native reporting surface. OAuth always runs in the external browser, outside the Galaxy WebView. */
class DiscordReportActivity : Activity() {
    companion object {
        // Serialize token rotation and submissions across activities, including a closed screen's work.
        private val requests = java.util.concurrent.Executors.newSingleThreadExecutor()
    }
    private val store by lazy { DiscordReportStore(applicationContext) }
    private val client by lazy { DiscordReportClient(DiscordRelayHttp(applicationContext)) }
    private var busy = false
    private var description = ""
    private var target: String? = null
    private var threads = emptyList<DiscordReportClient.ThreadEntry>()
    private var status = ""
    private lateinit var content: LinearLayout
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        description = savedInstanceState?.getString("description").orEmpty().take(1000)
        target = savedInstanceState?.getString("target")
        if (intent.data != null) callback(intent.data!!) else { render(); loadThreads() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("description", description)
        outState.putString("target", target)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let(::callback)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val focused = currentFocus
        if (event.actionMasked == MotionEvent.ACTION_DOWN && focused is EditText) {
            val bounds = android.graphics.Rect()
            focused.getGlobalVisibleRect(bounds)
            if (!bounds.contains(event.rawX.toInt(), event.rawY.toInt())) {
                focused.clearFocus()
                getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(focused.windowToken, 0)
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun rounded(color: Int = Palette.SPACE, stroke: Int = Palette.LINE) = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat(); setColor(color); setStroke(dp(1), stroke)
    }
    private fun text(value: String, size: Float = 15f) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Palette.MUTED)
        setLineSpacing(dp(2).toFloat(), 1f)
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }
    private fun section(title: String) {
        content.addView(text(title, 14f).apply {
            setTextColor(Palette.VIOLET_SOFT); typeface = Palette.medium
            setPadding(dp(4), dp(24), dp(4), dp(8))
        })
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = rounded(); clipToOutline = true
            elevation = dp(8).toFloat()
        }
        content.addView(body, LinearLayout.LayoutParams(-1, -2))
    }
    private fun button(label: String, action: () -> Unit) {
        val primary = label in setOf("Send report", "Connect with Discord", "Start another report")
        val back = label == "Back to Galaxy"
        val outlined = back || label == "Refresh my report threads"
        val danger = label in setOf("Disconnect Discord", "Discard saved report")
        if (!primary && !outlined && body !== content && body.childCount > 0) {
            body.addView(View(this).apply { setBackgroundColor(Palette.LINE) },
                LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(16) })
        }
        body.addView(Button(this).apply {
            text = if (back) "‹  $label" else label; contentDescription = label; textSize = 16f; typeface = Palette.medium; isAllCaps = false
            isEnabled = !busy; alpha = if (busy) 0.45f else 1f
            setTextColor(if (primary) Palette.SPACE else if (danger) Palette.ERROR else if (outlined) Palette.VIOLET_SOFT else Palette.ON)
            gravity = if (primary || outlined) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
            minHeight = dp(56); minimumHeight = dp(56); stateListAnimator = null
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = RippleDrawable(ColorStateList.valueOf(Palette.alpha(Palette.VIOLET, 60)),
                if (primary) rounded(Palette.VIOLET_SOFT) else if (outlined) rounded(Palette.alpha(Palette.VIOLET, 20), Palette.alpha(Palette.VIOLET, 110)) else GradientDrawable().apply { setColor(Palette.SPACE) }, null)
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, -2).apply {
            if (primary || outlined) { setMargins(dp(12), dp(12), dp(12), dp(12)) }
        })
    }

    private fun render() {
        if (isFinishing || isDestroyed) return
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setPadding(dp(20), dp(16), dp(20), dp(24)); setBackgroundColor(Palette.SURFACE)
        }
        content = body
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Palette.SURFACE)
            isFillViewport = true; addView(body)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = systemBarInsets(insets)
                val bottom = if (Build.VERSION.SDK_INT >= 30) maxOf(bars.bottom, insets.getInsets(android.view.WindowInsets.Type.ime()).bottom) else bars.bottom
                view.setPadding(bars.left, bars.top, bars.right, bottom); insets
            }
        }
        setContentView(scroll)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and
            (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
        content.addView(text("Report a problem", 24f).apply {
            typeface = Palette.condensed; setTextColor(Palette.ON); setPadding(dp(4), dp(6), dp(4), dp(4))
        })
        val threadsUpdated = status == "Report threads updated."
        content.addView(text(if (threadsUpdated) "✓ Report threads updated" else "Get help with Galaxy", 13f).apply {
            setPadding(dp(4), 0, dp(4), dp(4))
            if (threadsUpdated) setTextColor(Palette.MUTED)
        })
        if (busy) body.addView(ProgressBar(this).apply {
            indeterminateTintList = ColorStateList.valueOf(Palette.VIOLET_SOFT)
        }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { gravity = Gravity.CENTER; bottomMargin = dp(12) })
        if (status.isNotEmpty() && !threadsUpdated) body.addView(text(status).apply { setTextColor(Palette.VIOLET_SOFT) })
        try {
            val credentials = store.credentials()
            val pending = store.pending()
            section(if (pending == null) "Your report" else "Report status")
            if (pending != null) renderPending(pending, credentials) else renderDraft(credentials)
        } catch (_: Exception) {
            body.addView(text("Saved Discord data could not be read. Reopen the app or check Android's storage settings. Check Discord before clearing app data, since a saved report may already have arrived."))
        }
        body = content
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(20)))
        button("Back to Galaxy") { finish() }
        section("Discord account")
        runCatching { store.credentials() }.onSuccess { credentials ->
            body.addView(text(if (credentials == null) "Connect Discord to send a report." else "Discord connected · ${credentials.userId}"))
            if (credentials == null) button("Connect with Discord", ::login)
            else button("Disconnect Discord") {
                AlertDialog.Builder(this).setTitle("Disconnect Discord?")
                    .setMessage("You'll need to connect Discord again to send reports. Your saved report will stay on this phone.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Disconnect") { _, _ -> disconnect() }
                    .show()
            }
        }.onFailure { body.addView(text("Saved Discord account could not be read.")) }
    }

    private fun renderDraft(credentials: DiscordReportClient.Credentials?) {
        body.addView(text("What happened? (up to 1000 characters)"))
        body.addView(EditText(this).apply {
            setText(description); hint = "What were you doing, and what went wrong?"
            setTextColor(Palette.ON); setHintTextColor(Palette.MUTED)
            textSize = 16f; gravity = Gravity.TOP or Gravity.START
            background = rounded(Palette.SURFACE, Palette.alpha(Palette.VIOLET, 100))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3; maxLines = 8; filters = arrayOf(InputFilter.LengthFilter(1000)); isEnabled = !busy
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { description = s.toString() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), 0, dp(12), dp(8)) })
        body.addView(text("Post to"))
        body.addView(Spinner(this).apply {
            background = rounded(Palette.SURFACE)
            minimumHeight = dp(56)
            adapter = object : ArrayAdapter<String>(this@DiscordReportActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("New support thread") + threads.map { it.title }) {
                private fun row(position: Int, dropdown: Boolean) = text(getItem(position).orEmpty() + if (dropdown) "" else "  ▾", 15f).apply {
                    setTextColor(Palette.ON); typeface = Palette.medium; minHeight = dp(56)
                    gravity = Gravity.CENTER_VERTICAL
                    if (dropdown) setBackgroundColor(Palette.SURFACE)
                }
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = row(position, false)
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = row(position, true)
            }
            setSelection(threads.indexOfFirst { it.id == target }.let { if (it < 0) 0 else it + 1 })
            isEnabled = !busy
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { target = threads.getOrNull(position - 1)?.id }
                override fun onNothingSelected(parent: AdapterView<*>?) { target = null }
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), 0, dp(12), dp(8)) })
        if (credentials != null) button("Refresh my report threads", ::loadThreads)
        body.addView(text("Your description and Discord identity are public in the StarPilot Discord. Diagnostics are private. Don’t include passwords or App keys.", 13f))
        button("Send report") {
            if (credentials == null) { status = "Connect with Discord first."; render(); return@button }
            if (description.isBlank()) { status = "Add a short description first."; render(); return@button }
            val reference = UUID.randomUUID().toString()
            val reportDescription = description.trim()
            val reportTarget = target
            var captured: DiscordReportStore.Pending? = null
            task({
                ReportDiagnostics.refreshSoftware()
                val report = DiscordReportClient.Report(reference, reportDescription, diagnostics(reference), reportTarget)
                captured = DiscordReportStore.Pending(report, credentials.userId, System.currentTimeMillis())
                ""
            }, {
                captured?.let { pending ->
                    try {
                        // Save once before sending; retries retain this exact capture and UUID.
                        store.pending(pending)
                        send(pending)
                    } catch (_: Exception) {
                        status = "Could not save the report. Nothing was sent."; render()
                    }
                }
            })
        }
    }

    private fun renderPending(pending: DiscordReportStore.Pending, credentials: DiscordReportClient.Credentials?) {
        body.addView(text("Saved report: ${pending.report.reference}"))
        val sameUser = credentials?.userId == pending.userId
        body.addView(text(when {
            pending.state == "sent" -> "Report sent. You can open its Discord thread below."
            pending.state == "unconfirmed" -> "Delivery is unconfirmed. Check Discord before discarding this report or creating another. Diagnostics may already have arrived privately."
            pending.state == "rate_limited" && pending.retryAt > System.currentTimeMillis() -> "The hourly report limit was reached. Reopen this screen after ${java.util.Date(pending.retryAt)} to retry the saved report."
            !pending.retryAllowed() -> "This report cannot be retried. Check Discord before discarding it."
            !sameUser -> "Reconnect the original Discord account (${pending.userId}) to send this saved report."
            else -> "The original description and diagnostics are saved. A retry sends those same contents."
        }))
        button("Review saved report") { preview(pending, null) }
        if (pending.state == "sent" && DiscordReportClient.validThreadUrl(pending.threadUrl)) {
            button("Open Discord thread") { browse(Uri.parse(pending.threadUrl)) }
        }
        if (pending.retryAllowed() && sameUser) button("Review and retry saved report") { preview(pending) { send(pending) } }
        button(if (pending.state == "sent") "Start another report" else "Discard saved report") {
            if (pending.state == "sent") {
                store.pending(null); description = ""; status = ""; render(); loadThreads()
                return@button
            }
            AlertDialog.Builder(this).setTitle("Clear saved report?")
                .setMessage("This only removes the copy on this phone. Check Discord first if delivery was unconfirmed. It does not remove anything already posted.")
                .setNegativeButton("Keep", null).setPositiveButton("Clear") { _, _ ->
                    store.pending(null); description = ""; status = ""; render(); loadThreads()
                }.show()
        }
    }

    private fun preview(pending: DiscordReportStore.Pending, send: (() -> Unit)?) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(text("Public description:\n${pending.report.description}\n\nPrivate diagnostics:\n${pending.report.logs}").apply { setTextIsSelectable(true) })
        }
        AlertDialog.Builder(this).setTitle("Review report")
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("Close", null)
            .apply { if (send != null) setPositiveButton("Send report") { _, _ ->
                try { send() } catch (_: Exception) { status = "Could not save the report. Nothing was sent."; render() }
            } }.show()
    }

    private fun diagnostics(reference: String): String {
        return ReportDiagnostics.capture(this, reference)
    }

    private fun login() {
        try {
            val attempt = store.beginLogin()
            status = "Finish signing in in your browser, then return here."
            render()
            browse(Uri.parse(client.startUrl(attempt.state, DiscordReportClient.challenge(attempt.verifier))))
        } catch (_: Exception) { status = "Could not start Discord sign-in."; render() }
    }

    private fun callback(uri: Uri) {
        intent.data = null
        if (busy) return
        val exchange = runCatching { store.consumeCallback(uri) }.getOrNull()
        if (exchange == null) { status = "Discord sign-in was cancelled, expired, or could not be verified. Connect again to start a new sign-in."; render(); return }
        task({
            val credentials = client.exchange(exchange.first, exchange.second)
            if (credentials == null) "Discord sign-in could not be completed. Check your internet connection and connect again."
            else { store.credentials(credentials); "Discord connected." }
        }) { if (status == "Discord connected.") loadThreads() }
    }

    /** Refresh before expiry; never replace a saved report's account with the current login. */
    private fun authorization(): DiscordReportClient.Credentials? {
        val saved = store.credentials() ?: return null
        if (saved.expiresAtMillis > System.currentTimeMillis() + 60_000) return saved
        val refreshed = client.refresh(saved) ?: return null
        if (refreshed.userId != saved.userId) return null
        store.credentials(refreshed)
        return refreshed
    }

    private fun loadThreads() {
        if (busy || runCatching { store.credentials() }.getOrNull() == null) return
        task({
            val credentials = authorization()
            if (credentials == null) "Couldn't refresh Discord sign-in. Check your connection, or disconnect and connect Discord again."
            else {
                val result = client.reports(credentials.access)
                if (result == null) "Couldn't load report threads. Check your connection, or disconnect and connect Discord again."
                else { threads = result; "Report threads updated." }
            }
        })
    }

    private fun disconnect() {
        task({
            val saved = store.credentials()
            store.cancelLogin(); store.credentials(null); threads = emptyList(); target = null
            if (saved == null || client.revoke(saved.refresh)) "Discord disconnected."
            else "Discord removed from this phone. Revocation could not be confirmed; remove this app under Discord's Authorized Apps settings."
        })
    }

    private fun send(pending: DiscordReportStore.Pending) {
        if (busy || !pending.retryAllowed()) return
        // A process death after this point must never enable an automatic or blind retry.
        val saved = store.beginSubmission(pending.report.reference, pending.userId) ?: run {
            status = "The saved report changed or is already being sent. Check its status before continuing."
            render(); return
        }
        task({
            val credentials = authorization()
            if (credentials == null || credentials.userId != saved.userId) {
                store.updatePending(saved.report.reference, "signin")
                "Reconnect the original Discord account to send this report."
            } else {
                val result = client.submit(credentials.access, saved.report)
                if (result is DiscordReportClient.SendResult.SignIn) {
                    store.credentials(DiscordReportClient.Credentials(credentials.access, credentials.refresh, 0, credentials.userId))
                }
                val state = when (result) {
                    is DiscordReportClient.SendResult.Sent -> "sent"
                    is DiscordReportClient.SendResult.Retryable -> "retryable"
                    is DiscordReportClient.SendResult.SignIn -> "signin"
                    is DiscordReportClient.SendResult.Unconfirmed -> "unconfirmed"
                    is DiscordReportClient.SendResult.Failed -> if (result.retryAfterMillis == null) "failed" else "rate_limited"
                }
                store.updatePending(saved.report.reference, state, (result as? DiscordReportClient.SendResult.Sent)?.threadUrl.orEmpty(),
                    (result as? DiscordReportClient.SendResult.Failed)?.retryAfterMillis?.let { System.currentTimeMillis() + it } ?: 0)
                when (result) {
                    is DiscordReportClient.SendResult.Sent -> "Report sent."
                    is DiscordReportClient.SendResult.Retryable -> result.message
                    is DiscordReportClient.SendResult.SignIn -> result.message
                    is DiscordReportClient.SendResult.Unconfirmed -> result.message
                    is DiscordReportClient.SendResult.Failed -> result.message
                }
            }
        })
    }

    private fun task(work: () -> String, complete: () -> Unit = {}) {
        if (busy) return
        busy = true; status = "Working…"; render()
        requests.execute {
            val result = try { work() } catch (_: Exception) { "Could not complete the request. Check your connection and the saved report status." }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) { busy = false; status = result; render(); complete() }
            }
        }
    }

    private fun browse(uri: Uri) {
        try { startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) }
        catch (_: ActivityNotFoundException) { status = "Install or enable a web browser to continue."; render() }
    }
}
