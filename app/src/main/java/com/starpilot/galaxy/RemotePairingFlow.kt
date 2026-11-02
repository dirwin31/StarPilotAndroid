package com.starpilot.galaxy

import android.app.Activity
import android.app.AlertDialog
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** A cancellable LAN import. All UI and persistence callbacks run on the main thread. */
internal class RemotePairingFlow(
    private val activity: Activity,
    private val save: (TunnelCredential, String) -> Unit,
    private val connect: () -> Unit,
    private val scan: () -> Unit,
    private val paste: () -> Unit,
    private val finished: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var active = true
    private val progressToken = Any()
    private var nextProgressAt = 0L
    private val stepDisplayMs = 700L
    private var dialog: AlertDialog? = null
    private var discovery: LanDiscovery? = null
    private val steps = listOf("Find Galaxy on LAN", "Open a local session", "Fetch the remote key", "Save pairing on this phone")

    fun cancel() {
        if (!active) return
        active = false
        handler.removeCallbacksAndMessages(null)
        discovery?.close(); discovery = null
        dialog?.dismiss(); dialog = null
        finished()
    }

    private fun progress(step: Int) {
        if (!active) return
        if (step == 0) {
            handler.removeCallbacksAndMessages(progressToken)
            nextProgressAt = 0L
        }
        val displayAt = maxOf(SystemClock.uptimeMillis(), nextProgressAt)
        nextProgressAt = displayAt + stepDisplayMs
        handler.postAtTime({
            if (active) dialog?.setMessage(steps.mapIndexed { index, title ->
                "${if (index < step) "✓" else if (index == step) "→" else "○"} $title"
            }.joinToString("\n\n"))
        }, progressToken, displayAt)
    }

    fun start() {
        dialog = AlertDialog.Builder(activity).setTitle("Import Galaxy remote key")
            .setMessage("").setNegativeButton("Cancel") { _, _ -> cancel() }
            .setOnCancelListener { cancel() }.create().also { it.setCanceledOnTouchOutside(false); it.show() }
        progress(0)
        val wifi = Networks.wifi(activity, false)?.first
        if (wifi == null) { unavailable("This phone isn't on Wi-Fi. Join the same LAN as StarPilot, or scan its Galaxy App key QR code."); return }
        val known = Networks.commaLan(activity)
        if (known != null) fetch(known.first, known.second, fallback = { discover(wifi) }) else discover(wifi)
    }

    private fun discover(network: Network) {
        if (!active) return
        progress(0)
        discovery = LanDiscovery(activity, network) { hosts, error ->
            discovery = null
            if (active) when {
                hosts.isEmpty() -> unavailable(error ?: "No Galaxy was found on this LAN. Check that StarPilot is on the same Wi-Fi and Galaxy is enabled, or scan its App key QR code.")
                hosts.size == 1 -> fetch(network, hosts.single())
                else -> {
                    dialog?.dismiss()
                    dialog = AlertDialog.Builder(activity).setTitle("Choose your Galaxy")
                        .setItems(hosts.toTypedArray()) { _, index ->
                            dialog = AlertDialog.Builder(activity).setTitle("Import Galaxy remote key").setMessage("")
                                .setNegativeButton("Cancel") { _, _ -> cancel() }.setOnCancelListener { cancel() }.show()
                            fetch(network, hosts[index])
                        }.setNegativeButton("Cancel") { _, _ -> cancel() }.setOnCancelListener { cancel() }.show()
                }
            }
        }.also { it.start() }
    }

    private fun fetch(network: Network, origin: String, fallback: (() -> Unit)? = null) {
        Thread {
            val result = runCatching {
                TunnelImport.read(network, origin) { step -> handler.post { progress(step) } }
            }.getOrNull()
            handler.post {
                if (!active || activity.isDestroyed || activity.isFinishing) return@post
                if (result == null) {
                    if (fallback != null) fallback() else unavailable("Galaxy couldn't provide a remote key. Enable remote access in StarPilot → Galaxy → Access, then try again or scan the App key QR code.")
                    return@post
                }
                progress(3)
                // Pace only the presentation; HTTP requests run at their normal speed.
                handler.postAtTime({
                    if (active && !activity.isDestroyed && !activity.isFinishing) complete(result, origin)
                }, progressToken, nextProgressAt)
            }
        }.start()
    }

    private fun complete(result: TunnelCredential, origin: String) {
        if (runCatching { save(result, origin) }.isFailure) {
            unavailable("Couldn't save the pairing on this phone. Try again."); return
        }
        dialog?.dismiss()
        dialog = AlertDialog.Builder(activity).setTitle("Galaxy pairing saved")
            .setMessage(steps.joinToString("\n\n") { "✓ $it" } + "\n\nPairing is remembered when you close or restart the app. Remote connects when both devices have internet.")
            .setPositiveButton("Connect") { _, _ -> cancel(); connect() }
            .setNegativeButton("Done") { _, _ -> cancel() }.setOnCancelListener { cancel() }.show()
    }

    private fun unavailable(message: String) {
        if (!active) return
        handler.removeCallbacksAndMessages(progressToken)
        dialog?.dismiss()
        dialog = AlertDialog.Builder(activity).setTitle("Automatic import unavailable").setMessage(message)
            .setPositiveButton("Scan QR code") { _, _ -> cancel(); scan() }
            .setNeutralButton("Paste key") { _, _ -> cancel(); paste() }
            .setNegativeButton("Cancel") { _, _ -> cancel() }.setOnCancelListener { cancel() }.show()
    }
}
