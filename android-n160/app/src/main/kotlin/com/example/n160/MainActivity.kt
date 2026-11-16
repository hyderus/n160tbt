package com.example.n160

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.util.Log
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextUtils
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothAdapter
import java.io.File

import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : ComponentActivity(), BajajBleManager.BleListener, GoogleMapsNotificationListener.Companion.GmapsListener, BajajBleService.RideListener {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 101
    }

    private lateinit var webView: WebView
    private lateinit var bleManager: BajajBleManager
    private lateinit var updateManager: AppUpdateManager
    private var serviceBound = false
    private var rootLayout: android.widget.FrameLayout? = null
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var cameraTempUri: Uri? = null

    private var pendingCameraDocKey: String? = null
    private var pendingCameraSide: String? = null
    private var directCameraTempUri: Uri? = null

    private val directCameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val docKey = pendingCameraDocKey
        val side = pendingCameraSide
        pendingCameraDocKey = null
        pendingCameraSide = null

        if (result.resultCode == android.app.Activity.RESULT_OK && docKey != null) {
            val photoFile = File(cacheDir, "direct_scan_temp.jpg")
            if (photoFile.exists() && photoFile.length() > 0) {
                try {
                    val bytes = photoFile.readBytes()
                    val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    val dataUrl = "data:image/jpeg;base64,$b64"
                    webView.post {
                        webView.evaluateJavascript("if (window.onCameraPhotoCaptured) { window.onCameraPhotoCaptured('$docKey', '${side ?: "front"}', '$dataUrl'); }", null)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed reading direct camera photo", e)
                }
            }
        }
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileUploadCallback
        if (callback == null) return@registerForActivityResult

        var results: Array<Uri>? = null
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val intentData = result.data
            val dataUri = intentData?.data
            if (dataUri != null) {
                results = arrayOf(dataUri)
            } else if (intentData?.clipData != null) {
                val clipData = intentData.clipData!!
                val list = ArrayList<Uri>()
                for (i in 0 until clipData.itemCount) {
                    list.add(clipData.getItemAt(i).uri)
                }
                results = list.toTypedArray()
            } else if (cameraTempUri != null) {
                val photoFile = File(cacheDir, "scan_temp.jpg")
                if (photoFile.exists() && photoFile.length() > 0) {
                    results = arrayOf(cameraTempUri!!)
                }
            }
        }
        callback.onReceiveValue(results)
        fileUploadCallback = null
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "Connected to BajajBleService")
            serviceBound = true
            val binder = service as? BajajBleService.LocalBinder
            val svc = binder?.getService()
            if (svc != null) {
                bleManager.removeListener(this@MainActivity)
                bleManager = svc.bleManager
                bleManager.addListener(this@MainActivity)
                svc.addRideListener(this@MainActivity)

                // Sync current state if already connected
                if (bleManager.isConnected) {
                    onConnected(bleManager.connectedName, bleManager.connectedAddress)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Disconnected from BajajBleService")
            BajajBleService.instance?.removeRideListener(this@MainActivity)
            serviceBound = false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize fallback bleManager until service connects
        bleManager = BajajBleService.instance?.bleManager ?: BajajBleManager(this)
        bleManager.addListener(this)

        // Initialize update manager
        updateManager = AppUpdateManager(this)

        // Register Google Maps notification listener callback
        GoogleMapsNotificationListener.setListener(this)

        // Start Foreground Service to keep Bluetooth active when user switches apps or locks phone
        startBleForegroundService()

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                setGeolocationEnabled(true)
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            webViewClient = WebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?,
                    callback: GeolocationPermissions.Callback?
                ) {
                    callback?.invoke(origin, true, true)
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    // Cancel any pending callback to avoid lockups
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = filePathCallback

                    try {
                        val isCapture = fileChooserParams?.isCaptureEnabled == true

                        // Setup camera destination file
                        val photoFile = File(cacheDir, "scan_temp.jpg")
                        if (photoFile.exists()) {
                            photoFile.delete()
                        }
                        photoFile.createNewFile()
                        cameraTempUri = FileProvider.getUriForFile(
                            this@MainActivity,
                            "${applicationContext.packageName}.fileprovider",
                            photoFile
                        )

                        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                            putExtra(MediaStore.EXTRA_OUTPUT, cameraTempUri)
                            clipData = ClipData.newRawUri("scan_photo", cameraTempUri)
                            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }

                        val resolved = packageManager.queryIntentActivities(cameraIntent, PackageManager.MATCH_DEFAULT_ONLY)
                        for (r in resolved) {
                            grantUriPermission(r.activityInfo.packageName, cameraTempUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }

                        if (isCapture) {
                            fileChooserLauncher.launch(cameraIntent)
                        } else {
                            val getContentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf"))
                            }
                            val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
                                putExtra(Intent.EXTRA_INTENT, getContentIntent)
                                putExtra(Intent.EXTRA_TITLE, "Select Document or Photo")
                                putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
                            }
                            fileChooserLauncher.launch(chooserIntent)
                        }
                        return true
                    } catch (e: Exception) {
                        Log.e(TAG, "onShowFileChooser failed: ${e.message}", e)
                        fileUploadCallback?.onReceiveValue(null)
                        fileUploadCallback = null
                        return false
                    }
                }
            }
            addJavascriptInterface(WebAppInterface(), "AndroidBle")
            loadUrl("file:///android_asset/index.html")
        }

        // Enable edge-to-edge layout
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.parseColor("#0b111e")
        window.navigationBarColor = android.graphics.Color.parseColor("#0b111e")

        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val initialStatusBarHeight = if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0

        val layout = android.widget.FrameLayout(this).apply {
            rootLayout = this
            setBackgroundColor(android.graphics.Color.parseColor("#0b111e"))
            setPadding(0, initialStatusBarHeight, 0, 0)
            addView(
                webView,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        setContentView(layout)

        ViewCompat.setOnApplyWindowInsetsListener(layout) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val topInset = if (insets.top > 0) insets.top else initialStatusBarHeight
            view.setPadding(insets.left, topInset, insets.right, insets.bottom)
            windowInsets
        }
        ViewCompat.requestApplyInsets(layout)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    // Do not kill activity on back press: move task to back so service keeps running
                    moveTaskToBack(true)
                }
            }
        })

        checkAndRequestPermissions()
        handleUpdateIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUpdateIntent(intent)
    }

    private fun handleUpdateIntent(intent: Intent?) {
        if (intent?.action == "ACTION_OPEN_UPDATE" || intent?.hasExtra("NEW_VERSION_NAME") == true) {
            val name = intent.getStringExtra("NEW_VERSION_NAME") ?: "Latest"
            val code = intent.getIntExtra("NEW_VERSION_CODE", 0)
            val notes = (intent.getStringExtra("RELEASE_NOTES") ?: "").replace("'", "\\'").replace("\n", " ")
            val apkUrl = (intent.getStringExtra("APK_URL") ?: "").replace("'", "\\'")
            webView.postDelayed({
                val js = "if (window.onAppUpdateAvailable) { window.onAppUpdateAvailable('$name', $code, '$notes', '$apkUrl'); if (window.openUpdateModal) { window.openUpdateModal(); } }"
                webView.evaluateJavascript(js, null)
            }, 800)
        }
    }

    private fun startBleForegroundService() {
        val svcIntent = Intent(this, BajajBleService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, svcIntent)
            } else {
                startService(svcIntent)
            }
            bindService(svcIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting BajajBleService", e)
        }
    }

    override fun onResume() {
        super.onResume()
        webView.evaluateJavascript("if (window.checkGmapsSyncStatus) { window.checkGmapsSyncStatus(); }", null)
        if (::bleManager.isInitialized && !bleManager.isConnectedOrConnecting()) {
            bleManager.startScan()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            BajajBleService.instance?.startGpsTracking()
        }

        // Check for pending APK install after returning from settings
        if (::updateManager.isInitialized) {
            updateManager.checkPendingInstallOnResume()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        GoogleMapsNotificationListener.setListener(null)
        BajajBleService.instance?.removeRideListener(this)
        if (serviceBound) {
            try {
                unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding service", e)
            }
            serviceBound = false
        }
        bleManager.removeListener(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            BajajBleService.instance?.startGpsTracking()
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()

        // Camera for Document Vault scanning
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }

        // Bluetooth permissions for cluster link (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        // Notification & Storage permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        // GPS Location permissions for Turn-by-Turn & Ride Tracker
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    // --- BajajBleManager.BleListener Implementation ---

    override fun onDeviceDiscovered(name: String, address: String, rssi: Int) {
        val js = "if (window.onBleDeviceFound) { window.onBleDeviceFound('$name', '$address', $rssi); }"
        webView.post { webView.evaluateJavascript(js, null) }
    }

    override fun onStatusUpdate(status: String) {
        val js = "if (window.onBleStatusUpdate) { window.onBleStatusUpdate('$status'); }"
        webView.post { webView.evaluateJavascript(js, null) }
    }

    override fun onConnected(name: String, address: String) {
        BajajBleService.instance?.updateNotification("Pulsar N160", "Service active • Connected to $name")
        val js = "if (window.onBleConnected) { window.onBleConnected('$name', '$address'); }"
        webView.post {
            webView.evaluateJavascript(js, null)
            Toast.makeText(this, "Connected to $name", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDisconnected(address: String) {
        BajajBleService.instance?.updateNotification("Pulsar N160", "Service active")
        val js = "if (window.onBleDisconnected) { window.onBleDisconnected('$address'); }"
        webView.post { webView.evaluateJavascript(js, null) }
    }

    override fun onTelemetryReceived(speed: Int, rpm: Int, gear: Int, fuelPct: Int, odoKm: Long) {
        val js = "if (window.onBleTelemetry) { window.onBleTelemetry($speed, $rpm, $gear, $fuelPct, $odoKm); }"
        webView.post { webView.evaluateJavascript(js, null) }
    }

    override fun onError(message: String) {
        val js = "if (window.onBleError) { window.onBleError('$message'); }"
        webView.post {
            webView.evaluateJavascript(js, null)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onHeartbeatTick(batteryPct: Int, batteryBars: Int, heartbeatCount: Int) {
        val js = "if (window.onBleHeartbeat) { window.onBleHeartbeat($batteryPct, $batteryBars, $heartbeatCount); }"
        webView.post { webView.evaluateJavascript(js, null) }
    }

    // --- GoogleMapsNotificationListener.Companion.GmapsListener ---

    override fun onGmapsNavUpdate(
        maneuver: Char,
        distanceMeters: Int,
        street: String,
        roundaboutExit: Int,
        fullText: String,
        totalDistanceMeters: Int,
        etaMinutes: Int
    ) {
        val cleanStreet = street.replace("'", "\\'").replace("\"", "\\\"")
        val cleanText = fullText.replace("'", "\\'").replace("\"", "\\\"").replace("\n", " ").replace("\r", "")
        val js = "if (window.onGmapsNavUpdate) { window.onGmapsNavUpdate('$maneuver', $distanceMeters, '$cleanStreet', $roundaboutExit, '$cleanText', $totalDistanceMeters, $etaMinutes); }"
        webView.post { webView.evaluateJavascript(js, null) }
    }

    override fun onGmapsNavEnded() {
        val js = "if (window.onGmapsNavEnded) { window.onGmapsNavEnded(); }"
        webView.post { webView.evaluateJavascript(js, null) }
    }

    // --- BajajBleService.RideListener ---

    override fun onLocationUpdate(lat: Double, lng: Double, speedKmh: Float, accuracy: Float) {
        val js = "if (window.onGpsLocationUpdate) { window.onGpsLocationUpdate($lat, $lng, $speedKmh, $accuracy); }"
        webView.post { webView.evaluateJavascript(js, null) }
    }

    override fun onTripMetrics(isActive: Boolean, distMeters: Double, currentSpeedKmh: Float, topSpeedKmh: Float, elapsedSec: Long) {
        val js = "if (window.onLiveTripMetrics) { window.onLiveTripMetrics($isActive, $distMeters, $currentSpeedKmh, $topSpeedKmh, $elapsedSec); }"
        webView.post { webView.evaluateJavascript(js, null) }
    }

    // --- JavaScript Interface for UI Interactions ---

    inner class WebAppInterface {

                @JavascriptInterface
        fun isNotificationListenerEnabled(): Boolean {
            val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            return flat != null && flat.contains(packageName)
        }

        @JavascriptInterface
        fun isNotificationAccessGranted(): Boolean = isNotificationListenerEnabled()

        @JavascriptInterface
        fun isBonded(address: String): Boolean {
            return if (::bleManager.isInitialized) bleManager.isBonded(address) else false
        }

        @JavascriptInterface
        fun getBondedClusterAddress(): String? {
            return if (::bleManager.isInitialized) bleManager.getBondedClusterAddress() else null
        }

        @JavascriptInterface
        fun openNotificationAccessSettings() {
            try {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                startActivity(intent)
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Open Settings -> Notification Access to enable", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun sendTurnByTurnWithExit(
            maneuverCode: String,
            distanceMeters: Int,
            streetName: String,
            roundaboutExit: Int,
            totalDistanceMeters: Int = 0,
            etaMinutes: Int = 15
        ) {
            bleManager.sendTurnByTurn(maneuverCode, distanceMeters, streetName, roundaboutExit, totalDistanceMeters, etaMinutes)
        }

        @JavascriptInterface
        fun startScan() {
            runOnUiThread {
                checkAndRequestPermissions()
                bleManager.startScan()
            }
        }

        @JavascriptInterface
        fun stopScan() {
            runOnUiThread { bleManager.stopScan() }
        }

        @JavascriptInterface
        fun connect(address: String) {
            runOnUiThread { bleManager.connect(address) }
        }

        @JavascriptInterface
        fun disconnect() {
            runOnUiThread { bleManager.disconnect() }
        }

        @JavascriptInterface
        fun sendTurnByTurn(maneuverId: Int, distanceMeters: Int, streetName: String) {
            bleManager.sendTurnByTurn(maneuverId, distanceMeters, streetName)
        }

        @JavascriptInterface
        fun clearTurnByTurn() {
            bleManager.clearTurnByTurn()
        }

        @JavascriptInterface
        fun sendNotificationAlert(type: Int, senderName: String) {
            bleManager.sendNotificationAlert(type, senderName)
        }

        @JavascriptInterface
        fun simulateSmsAlert(senderName: String) {
            bleManager.sendNotificationAlert(1, senderName)
        }

        @JavascriptInterface
        fun simulateWhatsAppAlert(senderName: String) {
            bleManager.sendNotificationAlert(2, senderName)
        }

        @JavascriptInterface
        fun simulateIncomingCall(callerName: String) {
            bleManager.simulateIncomingCall(callerName)
        }

        @JavascriptInterface
        fun endCall() {
            bleManager.endCall()
        }

        @JavascriptInterface
        fun sendTimeSync() {
            bleManager.sendTimeSync()
        }

        @JavascriptInterface
        fun sendPhoneStatus(batteryPct: Int, signalLevel: Int) {
            bleManager.sendPhoneStatus(batteryPct, signalLevel)
        }

        @JavascriptInterface
        fun isAccessibilityServiceEnabled(): Boolean {
            val expectedComponentName = "${packageName}/${MapSearchAccessibilityService::class.java.canonicalName}"
            val enabledServicesSetting = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)
            while (colonSplitter.hasNext()) {
                val componentName = colonSplitter.next()
                if (componentName.equals(expectedComponentName, ignoreCase = true) ||
                    componentName.contains("MapSearchAccessibilityService", ignoreCase = true)) {
                    return true
                }
            }
            return false
        }

        @JavascriptInterface
        fun openAccessibilitySettings() {
            runOnUiThread {
                try {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Open Settings -> Accessibility to enable", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun openGoogleMapsApp() {
            runOnUiThread {
                try {
                    // Arm accessibility service to automatically click the search bar when Google Maps appears
                    MapSearchAccessibilityService.triggerSearchFocus()

                    val launchIntent = packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        startActivity(launchIntent)
                    } else {
                        val mapIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))
                        mapIntent.setPackage("com.google.android.apps.maps")
                        mapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        if (mapIntent.resolveActivity(packageManager) != null) {
                            startActivity(mapIntent)
                        } else {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com")))
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Opening Google Maps...", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun openGoogleMaps(destQuery: String) {
            if (destQuery.isBlank()) {
                openGoogleMapsApp()
                return
            }
            try {
                val gmmIntentUri = Uri.parse("google.navigation:q=" + Uri.encode(destQuery))
                val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                mapIntent.setPackage("com.google.android.apps.maps")
                if (mapIntent.resolveActivity(packageManager) != null) {
                    startActivity(mapIntent)
                } else {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(destQuery)))
                    startActivity(browserIntent)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Opening Google Maps in browser...", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun openGoogleMapsCoords(lat: Double, lng: Double) {
            try {
                val gmmIntentUri = Uri.parse("google.navigation:q=$lat,$lng")
                val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                mapIntent.setPackage("com.google.android.apps.maps")
                if (mapIntent.resolveActivity(packageManager) != null) {
                    startActivity(mapIntent)
                } else {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lng"))
                    startActivity(browserIntent)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Opening Google Maps...", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun showToast(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun setSystemBarTheme(isDark: Boolean) {
            runOnUiThread {
                try {
                    val color = if (isDark) android.graphics.Color.BLACK else android.graphics.Color.parseColor("#F7F5F0")
                    window.statusBarColor = color
                    window.navigationBarColor = color
                    rootLayout?.setBackgroundColor(color)
                    val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                    insetsController.isAppearanceLightStatusBars = !isDark
                    insetsController.isAppearanceLightNavigationBars = !isDark
                } catch (e: Exception) {
                    Log.w(TAG, "setSystemBarTheme error", e)
                }
            }
        }

        @JavascriptInterface
        fun startTrip(): Boolean {
            BajajBleService.instance?.startRide("WebUI Request")
            return true
        }

        @JavascriptInterface
        fun stopTrip(): String {
            return BajajBleService.instance?.stopRide("WebUI Request") ?: ""
        }

        @JavascriptInterface
        fun getNativeTrips(): String {
            val prefs = getSharedPreferences("n160_prefs", Context.MODE_PRIVATE)
            return prefs.getString("n160_real_trips", "[]") ?: "[]"
        }

        @JavascriptInterface
        fun saveTripLog(tripJson: String) {
            try {
                val prefs = getSharedPreferences("n160_prefs", Context.MODE_PRIVATE)
                val existing = prefs.getString("n160_real_trips", "[]") ?: "[]"
                val arr = org.json.JSONArray(existing)
                val newArr = org.json.JSONArray()
                newArr.put(org.json.JSONObject(tripJson))
                for (i in 0 until minOf(arr.length(), 49)) {
                    newArr.put(arr.getJSONObject(i))
                }
                prefs.edit().putString("n160_real_trips", newArr.toString()).apply()
            } catch (e: Exception) {
                Log.e(TAG, "Failed saving trip log from web", e)
            }
        }

        @JavascriptInterface
        fun clearNativeTrips() {
            val prefs = getSharedPreferences("n160_prefs", Context.MODE_PRIVATE)
            prefs.edit().remove("n160_real_trips").apply()
        }

        @JavascriptInterface
        fun isNativeApp(): Boolean = true

        @JavascriptInterface
        fun getAppVersionName(): String {
            return if (::updateManager.isInitialized) updateManager.getCurrentVersionName() else "1.3.2"
        }

        @JavascriptInterface
        fun getAppVersionCode(): Int {
            return if (::updateManager.isInitialized) updateManager.getCurrentVersionCode() else 6
        }

        @JavascriptInterface
        fun checkForUpdate(isManual: Boolean) {
            if (!::updateManager.isInitialized) return
            updateManager.checkForUpdate(isManualCheck = isManual) { result ->
                when (result) {
                    is AppUpdateManager.UpdateCheckResult.Available -> {
                        val cleanNotes = result.info.releaseNotes.replace("'", "\\'").replace("\n", " ")
                        val js = "if (window.onUpdateCheckResult) { window.onUpdateCheckResult('AVAILABLE', JSON.stringify({versionName: '${result.info.versionName}', versionCode: ${result.info.versionCode}, releaseNotes: '$cleanNotes', apkUrl: '${result.info.apkUrl}'})); }"
                        webView.post { webView.evaluateJavascript(js, null) }
                    }
                    is AppUpdateManager.UpdateCheckResult.Latest -> {
                        val js = "if (window.onUpdateCheckResult) { window.onUpdateCheckResult('LATEST', JSON.stringify({versionName: '${result.currentName}', versionCode: ${result.currentCode}})); }"
                        webView.post { webView.evaluateJavascript(js, null) }
                    }
                    is AppUpdateManager.UpdateCheckResult.Error -> {
                        val cleanErr = result.error.replace("'", "\\'").replace("\n", " ")
                        val js = "if (window.onUpdateCheckResult) { window.onUpdateCheckResult('ERROR', JSON.stringify({error: '$cleanErr'})); }"
                        webView.post { webView.evaluateJavascript(js, null) }
                    }
                }
            }
        }

        @JavascriptInterface
        fun downloadAndInstallUpdate(apkUrl: String) {
            if (!::updateManager.isInitialized) return
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Downloading Pulsar N160 update...", Toast.LENGTH_SHORT).show()
                updateManager.downloadAndInstallUpdate(
                    apkUrl = apkUrl,
                    onProgress = { progress, currentBytes, totalBytes ->
                        val js = "if (window.onUpdateDownloadProgress) { window.onUpdateDownloadProgress($progress, $currentBytes, $totalBytes); }"
                        webView.post { webView.evaluateJavascript(js, null) }
                    },
                    onError = { errorMsg ->
                        val cleanErr = errorMsg.replace("'", "\\'").replace("\n", " ")
                        val js = "if (window.onUpdateDownloadError) { window.onUpdateDownloadError('$cleanErr'); }"
                        webView.post {
                            webView.evaluateJavascript(js, null)
                            Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }
        }
        @JavascriptInterface
        fun launchCameraScan(docKey: String, side: String) {
            runOnUiThread {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.CAMERA), PERMISSION_REQUEST_CODE)
                    return@runOnUiThread
                }
                try {
                    pendingCameraDocKey = docKey
                    pendingCameraSide = side
                    val photoFile = File(cacheDir, "direct_scan_temp.jpg")
                    if (photoFile.exists()) photoFile.delete()
                    photoFile.createNewFile()

                    directCameraTempUri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${applicationContext.packageName}.fileprovider",
                        photoFile
                    )

                    val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(MediaStore.EXTRA_OUTPUT, directCameraTempUri)
                        clipData = ClipData.newRawUri("direct_scan_photo", directCameraTempUri)
                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    val resolved = packageManager.queryIntentActivities(cameraIntent, PackageManager.MATCH_DEFAULT_ONLY)
                    for (r in resolved) {
                        grantUriPermission(r.activityInfo.packageName, directCameraTempUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    directCameraLauncher.launch(cameraIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error launching direct camera scan", e)
                    Toast.makeText(this@MainActivity, "Unable to start camera: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun saveImageToGallery(base64Data: String, filename: String) {
            runOnUiThread {
                try {
                    val cleanBase64 = if (base64Data.contains(",")) {
                        base64Data.substringAfter(",")
                    } else {
                        base64Data
                    }
                    val imageBytes = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
                    val safeName = filename.replace(Regex("[^a-zA-Z0-9_-]"), "_") + "_" + System.currentTimeMillis() + ".png"

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, safeName)
                            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PulsarN160")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        if (uri != null) {
                            contentResolver.openOutputStream(uri).use { out ->
                                out?.write(imageBytes)
                            }
                            values.clear()
                            values.put(MediaStore.Images.Media.IS_PENDING, 0)
                            contentResolver.update(uri, values, null, null)
                            Toast.makeText(this@MainActivity, "Saved to Gallery (Pictures/PulsarN160)!", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@MainActivity, "Failed to create gallery entry", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                        val appDir = File(picturesDir, "PulsarN160").apply { if (!exists()) mkdirs() }
                        val file = File(appDir, safeName)
                        file.outputStream().use { it.write(imageBytes) }
                        val scanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                            data = Uri.fromFile(file)
                        }
                        sendBroadcast(scanIntent)
                        Toast.makeText(this@MainActivity, "Saved to Gallery (Pictures/PulsarN160)!", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving to gallery", e)
                    Toast.makeText(this@MainActivity, "Error saving image: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
