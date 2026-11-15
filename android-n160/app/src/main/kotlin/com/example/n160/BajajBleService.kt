package com.example.n160

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat

@SuppressLint("MissingPermission")
class BajajBleService : Service() {

    companion object {
        private const val TAG = "BajajBleService"
        const val CHANNEL_ID = "pulsar_n160_ble_service"
        const val NOTIFICATION_ID = 2024

        const val ACTION_START = "com.example.n160.action.START"
        const val ACTION_STOP = "com.example.n160.action.STOP"

        @Volatile
        var instance: BajajBleService? = null
            private set
    }

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    lateinit var bleManager: BajajBleManager
        private set

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
        startInForeground("Pulsar N160", "Service active")
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
                "Pulsar N160 Bluetooth & Navigation Link",
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
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
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
