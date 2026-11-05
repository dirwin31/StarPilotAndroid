package com.starpilot.galaxy

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
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
    private fun text(value: String, size: Float = 15f) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Palette.ON)
        setPadding(0, dp(8), 0, dp(8))
    }
    private fun button(label: String, action: () -> Unit) {
        body.addView(Button(this).apply { text = label; isEnabled = !busy; setOnClickListener { action() } })
    }

    private fun render() {
        if (isFinishing || isDestroyed) return
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setPadding(dp(20), dp(16), dp(20), dp(24)); setBackgroundColor(Palette.SPACE)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true; addView(body)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = systemBarInsets(insets)
                val bottom = if (Build.VERSION.SDK_INT >= 30) maxOf(bars.bottom, insets.getInsets(android.view.WindowInsets.Type.ime()).bottom) else bars.bottom
                view.setPadding(bars.left, bars.top, bars.right, bottom); insets
            }
        }
        setContentView(scroll)
        body.addView(text("Report a problem", 24f))
        body.addView(text("Your description and Discord identity appear in a public support thread. Diagnostics go to the private maintainer channel. Tap Send report to submit your description and diagnostics. Do not include passwords, App keys, or other private information in your description."))
        if (busy) body.addView(ProgressBar(this))
        if (status.isNotEmpty()) body.addView(text(status))
        try {
            val credentials = store.credentials()
            body.addView(text(if (credentials == null) "Connect Discord to send a report." else "Discord connected · ${credentials.userId}"))
            if (credentials == null) {
                button("Connect with Discord", ::login)
                button("Cancel sign-in") { store.cancelLogin(); status = "Sign-in cancelled."; render() }
            } else {
                button("Reconnect Discord", ::login)
                button("Disconnect Discord", ::disconnect)
            }
            val pending = store.pending()
            if (pending != null) renderPending(pending, credentials) else renderDraft(credentials)
        } catch (_: Exception) {
            body.addView(text("Saved Discord data could not be read. Reopen the app or check Android's storage settings. Check Discord before clearing app data, since a saved report may already have arrived."))
        }
        button("Back to Galaxy") { finish() }
    }

    private fun renderDraft(credentials: DiscordReportClient.Credentials?) {
        body.addView(text("What happened? (up to 1000 characters)"))
        body.addView(EditText(this).apply {
            setText(description); hint = "What were you doing, and what went wrong?"
            setTextColor(Palette.ON); setHintTextColor(Palette.MUTED)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3; maxLines = 8; filters = arrayOf(InputFilter.LengthFilter(1000)); isEnabled = !busy
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { description = s.toString() }
                override fun afterTextChanged(s: Editable?) {}
            })
        })
        body.addView(text("Post to"))
        body.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@DiscordReportActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("New support thread") + threads.map { it.title })
            setSelection(threads.indexOfFirst { it.id == target }.let { if (it < 0) 0 else it + 1 })
            isEnabled = !busy
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { target = threads.getOrNull(position - 1)?.id }
                override fun onNothingSelected(parent: AdapterView<*>?) { target = null }
            }
        })
        if (credentials != null) button("Refresh my report threads", ::loadThreads)
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
            if (credentials == null) "Couldn't refresh Discord sign-in. Check your connection or reconnect Discord."
            else {
                val result = client.reports(credentials.access)
                if (result == null) "Couldn't load report threads. Check your connection or reconnect Discord."
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
