package com.example.n160

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.CopyOnWriteArraySet

@SuppressLint("MissingPermission")
class BajajBleService : Service() {

    companion object {
        private const val TAG = "BajajBleService"
        const val CHANNEL_ID = "n160_ugp_service_v4"
        const val NOTIFICATION_ID = 2026

        const val ACTION_START = "com.example.n160.action.START"
        const val ACTION_STOP = "com.example.n160.action.STOP"

        @Volatile
        var instance: BajajBleService? = null
            private set
    }

    interface RideListener {
        fun onLocationUpdate(lat: Double, lng: Double, speedKmh: Float, accuracy: Float)
        fun onTripMetrics(isActive: Boolean, distMeters: Double, currentSpeedKmh: Float, topSpeedKmh: Float, elapsedSec: Long)
    }

    private val rideListeners = CopyOnWriteArraySet<RideListener>()

    fun addRideListener(listener: RideListener) {
        rideListeners.add(listener)
    }

    fun removeRideListener(listener: RideListener) {
        rideListeners.remove(listener)
    }

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    lateinit var bleManager: BajajBleManager
        private set

    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null

    var isRideActive: Boolean = false
        private set
    var rideStartTime: Long = 0L
        private set
    var rideDistanceMeters: Double = 0.0
        private set
    var rideTopSpeedKmh: Float = 0.0f
        private set
    var currentSpeedKmh: Float = 0.0f
        private set
    private var lastLocation: Location? = null
    private var lastLocationTime: Long = 0L

    private var batteryReceiver: BroadcastReceiver? = null
    var currentBatteryPct: Int = 85
        private set

    inner class LocalBinder : Binder() {
        fun getService(): BajajBleService = this@BajajBleService
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "BajajBleService onCreate: Initializing persistent background BLE manager")

        bleManager = BajajBleManager(applicationContext)

        // Acquire partial wakelock so CPU does not sleep during background navigation
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PulsarN160:BleWakeLock"
        )?.apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L) // up to 24 hours while service runs
        }

        registerBatteryMonitor()
        createNotificationChannel()
        startInForeground("N160 UGP", "Service active")
        startGpsTracking()

        bleManager.addListener(object : BajajBleManager.BleListener {
            override fun onConnected(name: String, address: String) {
                startRide("Cluster Connected: $name")
            }

            override fun onDisconnected(address: String) {
                stopRide("Cluster Disconnected")
            }

            override fun onError(message: String) {}
            override fun onHeartbeatTick(batteryPct: Int, batteryBars: Int, heartbeatCount: Int) {}
            override fun onDeviceDiscovered(name: String, address: String, rssi: Int) {}
            override fun onStatusUpdate(status: String) {}
            override fun onTelemetryReceived(speed: Int, rpm: Int, gear: Int, fuelPct: Int, odoKm: Long) {}
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundService()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    private fun registerBatteryMonitor() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level >= 0 && scale > 0) {
                        currentBatteryPct = (level * 100) / scale
                        bleManager.updateBatteryLevel(currentBatteryPct)
                    }
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, filter)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "N160 UGP Bluetooth & Navigation Link",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the motorcycle instrument cluster connected and streaming Turn-by-Turn signals in the background"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    fun startInForeground(title: String, message: String) {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val largeIconBitmap = BitmapFactory.decodeResource(resources, R.drawable.ic_notification_large)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(largeIconBitmap)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun startGpsTracking() {
        if (locationManager == null) {
            locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        }
        val lm = locationManager ?: return
        if (locationListener != null) return

        locationListener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                processLocationUpdate(loc)
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    0f,
                    locationListener!!,
                    Looper.getMainLooper()
                )
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    2000L,
                    0f,
                    locationListener!!,
                    Looper.getMainLooper()
                )
            }
            Log.i(TAG, "Native GPS tracking registered successfully")
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission not granted for GPS tracking", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering GPS location listener", e)
        }
    }

    private fun processLocationUpdate(loc: Location) {
        val now = System.currentTimeMillis()
        var speed = 0.0f

        if (loc.hasSpeed() && loc.speed >= 0.2f) {
            speed = loc.speed * 3.6f
        } else if (lastLocation != null && lastLocationTime > 0L) {
            val dtSec = (now - lastLocationTime) / 1000.0f
            if (dtSec in 0.8f..15.0f) {
                val d = lastLocation!!.distanceTo(loc)
                if (d in 2.0f..350.0f) {
                    speed = (d / dtSec) * 3.6f
                }
            }
        }

        if (speed > 180f) speed = 0.0f
        currentSpeedKmh = speed

        if (isRideActive) {
            if (lastLocation != null) {
                val d = lastLocation!!.distanceTo(loc)
                if (d in 2.0f..350.0f) {
                    rideDistanceMeters += d
                }
            }
            if (currentSpeedKmh > rideTopSpeedKmh) {
                rideTopSpeedKmh = currentSpeedKmh
            }
            val elapsedSec = (now - rideStartTime) / 1000L

            val distKmStr = String.format(java.util.Locale.US, "%.1f km", rideDistanceMeters / 1000.0)
            val speedStr = "${currentSpeedKmh.toInt()} km/h"
            val topStr = "${rideTopSpeedKmh.toInt()} km/h"
            updateNotification("Pulsar N160 • $speedStr", "Trip: $distKmStr (Top: $topStr)")

            for (listener in rideListeners) {
                listener.onTripMetrics(true, rideDistanceMeters, currentSpeedKmh, rideTopSpeedKmh, elapsedSec)
            }
        }

        lastLocation = loc
        lastLocationTime = now

        for (listener in rideListeners) {
            listener.onLocationUpdate(loc.latitude, loc.longitude, currentSpeedKmh, loc.accuracy)
        }
    }

    fun startRide(reason: String = "Auto Connect") {
        if (isRideActive) return
        isRideActive = true
        rideStartTime = System.currentTimeMillis()
        rideDistanceMeters = 0.0
        rideTopSpeedKmh = 0.0f
        currentSpeedKmh = 0.0f
        lastLocation = null
        lastLocationTime = 0L
        startGpsTracking()
        Log.i(TAG, "Ride trip started ($reason)")
    }

    fun stopRide(reason: String = "Disconnect"): String? {
        if (!isRideActive) return null
        isRideActive = false
        val durationMs = System.currentTimeMillis() - rideStartTime
        val elapsedSec = durationMs / 1000L

        var savedSummary: String? = null
        if (elapsedSec >= 10 || rideDistanceMeters >= 50.0) {
            val distKm = String.format(java.util.Locale.US, "%.1f km", rideDistanceMeters / 1000.0)
            val mins = elapsedSec / 60
            val secs = elapsedSec % 60
            val durStr = if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
            val topStr = "${rideTopSpeedKmh.toInt()} km/h"
            val elapsedHours = durationMs / 3600000.0
            val avgSpeed = if (elapsedHours > 0.0 && rideDistanceMeters > 50.0) {
                (rideDistanceMeters / 1000.0 / elapsedHours).toInt()
            } else 0
            val avgStr = "$avgSpeed km/h"

            val dateFmt = java.text.SimpleDateFormat("d MMM, hh:mm a", java.util.Locale.US)
            val dateStr = dateFmt.format(java.util.Date())

            val tripJson = org.json.JSONObject().apply {
                put("date", dateStr)
                put("distance", distKm)
                put("duration", durStr)
                put("topSpeed", topStr)
                put("avgSpeed", avgStr)
            }

            saveTripToPrefs(tripJson.toString())
            savedSummary = "Ride Saved: $distKm in $durStr (Top: $topStr)"
            Log.i(TAG, "Saved ride trip: $savedSummary")
        }

        updateNotification("Pulsar N160", "Service active")
        return savedSummary
    }

    private fun saveTripToPrefs(tripJsonStr: String) {
        try {
            val prefs = getSharedPreferences("n160_prefs", Context.MODE_PRIVATE)
            val existing = prefs.getString("n160_real_trips", "[]") ?: "[]"
            val arr = org.json.JSONArray(existing)
            val newArr = org.json.JSONArray()
            newArr.put(org.json.JSONObject(tripJsonStr))
            for (i in 0 until minOf(arr.length(), 49)) {
                newArr.put(arr.getJSONObject(i))
            }
            prefs.edit().putString("n160_real_trips", newArr.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving trip to prefs", e)
        }
    }

    fun updateNotification(title: String, message: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val largeIconBitmap = BitmapFactory.decodeResource(resources, R.drawable.ic_notification_large)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(largeIconBitmap)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    private fun stopForegroundService() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping foreground", e)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        try {
            locationListener?.let { locationManager?.removeUpdates(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing location updates", e)
        }
        try {
            batteryReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering battery receiver", e)
        }
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wakelock", e)
        }
        bleManager.cleanup()
        Log.i(TAG, "BajajBleService onDestroy")
    }
}
