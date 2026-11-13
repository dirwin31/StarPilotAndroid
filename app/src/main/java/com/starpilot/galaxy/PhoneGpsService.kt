package com.starpilot.galaxy

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Sends each new phone GPS fix to comma as it arrives, over Bluetooth or Galaxy Wi-Fi. */
class PhoneGpsService : Service(), LocationListener {
    companion object {
        fun enabled(prefs: android.content.SharedPreferences) = prefs.getBoolean("phoneGps", false)
        // Receivers that support it report at 2 Hz; most phones still give 1 Hz.
        const val FIX_INTERVAL_MS = 500L
    }
    private val prefs by lazy { getSharedPreferences("companion", MODE_PRIVATE) }
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val stream = PhoneGpsStream()
    private val tickQueued = AtomicBoolean()
    private var legacyPayload = false
    @Volatile private var stopped = false
    private var sent = 0L
    private val sender by lazy { LocalCompanionSender(this) }
    private var status = ""

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(Notifications.GPS_CHANNEL, "Phone GPS", NotificationManager.IMPORTANCE_LOW))
        val first = notification("Waiting for GPS")
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(Notifications.PHONE_GPS, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(Notifications.PHONE_GPS, first)
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) throw SecurityException("Location permission")
            getSystemService(LocationManager::class.java).requestLocationUpdates(LocationManager.GPS_PROVIDER, FIX_INTERVAL_MS, 0f, this, Looper.getMainLooper())
        } catch (_: Exception) {
            // Android refuses location in the background after the permission is withdrawn.
            prefs.edit().putBoolean("phoneGps", false).apply(); stopped = true; stopSelf(); return
        }
        // Fixes are sent as they arrive; this tick only retries and refreshes the status.
        executor.scheduleWithFixedDelay({ tick() }, 1, 1, TimeUnit.SECONDS)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop" || !enabled(prefs)) {
            stopped = true
            prefs.edit().putBoolean("phoneGps", false).apply(); stopSelf(); return START_NOT_STICKY
        }
        return START_STICKY
    }
    override fun onLocationChanged(location: Location) {
        if (stopped) return
        val fix = PhoneGps.Fix(location.latitude, location.longitude, location.accuracy, location.time,
            location.altitude.takeIf { location.hasAltitude() }, location.verticalAccuracyMeters.takeIf { location.hasVerticalAccuracy() },
            location.speed.takeIf { location.hasSpeed() }, location.speedAccuracyMetersPerSecond.takeIf { location.hasSpeedAccuracy() },
            location.bearing.takeIf { location.hasBearing() }, location.bearingAccuracyDegrees.takeIf { location.hasBearingAccuracy() })
        if (stream.offer(fix, location.elapsedRealtimeNanos, SystemClock.elapsedRealtimeNanos()) && tickQueued.compareAndSet(false, true))
            runCatching { executor.execute { tickQueued.set(false); tick() } }.onFailure { tickQueued.set(false) }
    }
    @Deprecated("Required before Android 10") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun tick() {
        if (stopped) return
        if (stream.sequence() == 0L) return update("Waiting for GPS")
        if (stream.payload(SystemClock.elapsedRealtimeNanos()) == null) return update("Waiting for a fresh GPS fix")
        if (stream.sequence() == sent) return
        try {
            var accepted = false
            sent = sender.post(PhoneGps.PATH, deadlineSeconds = 5, timingProbe = !legacyPayload,
                onResponse = { _, reply -> accepted = reply.optBoolean("accepted", true) }, latestBody = ::latestBody)
            update(if (accepted) "Sharing GPS with StarPilot" else "Waiting for StarPilot or a fresh GPS fix")
        } catch (failure: Exception) {
            if (failure is CompanionHttpException && failure.status == 400 && !legacyPayload) {
                legacyPayload = true
                return tick()
            }
            update("Waiting for StarPilot or a fresh GPS fix")
        }
    }
    private fun latestBody(serverMonoTimeNs: Long?): Pair<Long, ByteArray> {
        check(!stopped)
        val (sequence, payload) = stream.payload(SystemClock.elapsedRealtimeNanos(), !legacyPayload, serverMonoTimeNs)
            ?: error("Waiting for fresh GPS")
        return sequence to JSONObject(payload).toString().toByteArray()
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, PhoneGpsService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, Notifications.GPS_CHANNEL).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Galaxy phone GPS").setContentText(text).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }
    private fun update(text: String) {
        if (stopped || text == status) return
        status = text
        getSystemService(NotificationManager::class.java).notify(Notifications.PHONE_GPS, notification(text))
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        stopped = true
        getSystemService(LocationManager::class.java).removeUpdates(this)
        runCatching { executor.execute { sender.close() } }
        executor.shutdown()
        super.onDestroy()
    }
}
