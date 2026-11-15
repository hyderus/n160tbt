package com.example.n160

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

@SuppressLint("MissingPermission")
class BajajBleManager(private val context: Context, initialListener: BleListener? = null) {

    companion object {
        private const val TAG = "BajajBleManager"

        // Official OTC Engineering / Bajaj Smart Cluster GATT UUIDs (decompiled from official Bajaj APK)
        val SERVICE_UUID: UUID = UUID.fromString("0010676e-6972-6565-6e69-676e4543544f")
        val CHAR_TBT: UUID = UUID.fromString("0110676e-6972-6565-6e69-676e4543544f")          // TBT_INFO_CHAR
        val CHAR_GENERAL: UUID = UUID.fromString("0210676e-6972-6565-6e69-676e4543544f")      // GENERAL_CHAR
        val CHAR_MISSED_CALLS: UUID = UUID.fromString("0310676e-6972-6565-6e69-676e4543544f") // MISSED_CALL_CHAR
        val CHAR_NOTIFICATIONS: UUID = UUID.fromString("0410676e-6972-6565-6e69-676e4543544f")// ALERTS_INFO_CHAR
        val CHAR_MEDIA: UUID = UUID.fromString("0610676e-6972-6565-6e69-676e4543544f")        // MEDIA_INFO_CHAR
        val CHAR_CONTROLS: UUID = UUID.fromString("0a10676e-6972-6565-6e69-676e4543544f")     // Handlebar button inputs
        val CLIENT_CONFIG_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Heartbeat interval: exactly 1000ms (1.0 second, matching BleFeatures$2 from official Bajaj APK)
        private const val HEARTBEAT_INTERVAL_MS = 1000L
        private const val TBT_REPEAT_INTERVAL_MS = 2500L

        /**
         * Resolves any maneuver representation (Char, numeric ID, or string name)
         * to the official Bajaj / OTC Engineering PrimaryTurns ASCII code:
         * 'I': Left, 'J': Right, 'C': Slight Left, 'D': Slight Right, 'E': Sharp Left, 'F': Sharp Right,
         * 'G': Straight, 'V': Merge, 'Z': Keep Left, 'X': Keep Right, 'K': Ramp Left, 'L': Ramp Right,
         * 'N': Roundabout Left, 'U': Roundabout Right, 'O': U-Turn Left, 'P': U-Turn Right,
         * 'Q': Fork Left, 'R': Fork Right, 'Y': Ferry, 'T': Train, 'H': Destination, 'B': Wrong Way
         */
        fun resolveManeuverChar(maneuver: Any): Char {
            return when (maneuver) {
                is Char -> maneuver
                is Int -> when (maneuver) {
                    0, 73 -> 'I' // TURN_LEFT
                    1, 74 -> 'J' // TURN_RIGHT
                    2, 67 -> 'C' // TURN_SLIGHT_LEFT
                    3, 68 -> 'D' // TURN_SLIGHT_RIGHT
                    4, 69 -> 'E' // TURN_SHARP_LEFT
                    5, 70 -> 'F' // TURN_SHARP_RIGHT
                    6, 71 -> 'G' // STRAIGHT
                    7, 86 -> 'V' // MERGE
                    8, 90 -> 'Z' // KEEP_LEFT
                    9, 88 -> 'X' // KEEP_RIGHT
                    10, 75 -> 'K' // RAMP_LEFT
                    11, 76 -> 'L' // RAMP_RIGHT
                    12, 78 -> 'N' // ROUNDABOUT_LEFT
                    13, 85 -> 'U' // ROUNDABOUT_RIGHT
                    14, 79 -> 'O' // U_TURN_LEFT
                    15, 80 -> 'P' // U_TURN_RIGHT
                    16, 81 -> 'Q' // FORK_LEFT
                    17, 82 -> 'R' // FORK_RIGHT
                    18, 89 -> 'Y' // FERRY
                    19, 84 -> 'T' // FERRY_TRAIN
                    20, 72 -> 'H' // DESTINATION_REACHED
                    21, 66 -> 'B' // WRONG_WAY
                    else -> 'G'
                }
                is String -> {
                    val s = maneuver.uppercase().trim()
                    if (s.length == 1 && s[0] in "BCDEFGHIJKLNOOPQRSTUVXYZ".toCharArray()) {
                        s[0]
                    } else when (s) {
                        "TURN_LEFT", "LEFT" -> 'I'
                        "TURN_RIGHT", "RIGHT" -> 'J'
                        "TURN_SLIGHT_LEFT", "SLIGHT_LEFT" -> 'C'
                        "TURN_SLIGHT_RIGHT", "SLIGHT_RIGHT" -> 'D'
                        "TURN_SHARP_LEFT", "SHARP_LEFT" -> 'E'
                        "TURN_SHARP_RIGHT", "SHARP_RIGHT" -> 'F'
                        "STRAIGHT", "CONTINUE" -> 'G'
                        "MERGE" -> 'V'
                        "KEEP_LEFT" -> 'Z'
                        "KEEP_RIGHT" -> 'X'
                        "RAMP_LEFT" -> 'K'
                        "RAMP_RIGHT" -> 'L'
                        "ROUNDABOUT", "ROUNDABOUT_RIGHT" -> 'U'
                        "ROUNDABOUT_LEFT" -> 'N'
                        "U_TURN", "U_TURN_RIGHT", "UTURN" -> 'P'
                        "U_TURN_LEFT" -> 'O'
                        "FORK_LEFT" -> 'Q'
                        "FORK_RIGHT" -> 'R'
                        "FERRY" -> 'Y'
                        "TRAIN", "FERRY_TRAIN" -> 'T'
                        "DESTINATION", "DESTINATION_REACHED", "ARRIVED" -> 'H'
                        "WRONG_WAY" -> 'B'
                        else -> 'G'
                    }
                }
                else -> 'G'
            }
        }
    }

    interface BleListener {
        fun onDeviceDiscovered(name: String, address: String, rssi: Int)
        fun onStatusUpdate(status: String)
        fun onConnected(name: String, address: String)
        fun onDisconnected(address: String)
        fun onTelemetryReceived(speed: Int, rpm: Int, gear: Int, fuelPct: Int, odoKm: Long)
        fun onError(message: String)
        fun onHeartbeatTick(batteryPct: Int, batteryBars: Int, heartbeatCount: Int) {}
    }

    private val listeners = CopyOnWriteArrayList<BleListener>()

    init {
        if (initialListener != null) {
            listeners.add(initialListener)
        }
    }

    fun addListener(l: BleListener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: BleListener) {
        listeners.remove(l)
    }

    private val bluetoothManager: BluetoothManager? = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var bleScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private var tbtChar: BluetoothGattCharacteristic? = null
    private var generalChar: BluetoothGattCharacteristic? = null
    private var missedCallChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var mediaChar: BluetoothGattCharacteristic? = null
    private var controlChar: BluetoothGattCharacteristic? = null

    // Heartbeat & status tracking
    private var heartbeatRunnable: Runnable? = null
    private var heartbeatCounter = 0
    private var cachedBatteryPct: Int = 85

    // Call state: 0 = NO_CALL, 1 = INCOMING_CALL, 2 = OUTGOING_CALL, 3 = ACTIVE_CALL, 4 = END_CALL
    private var activeCallState: Int = 0
    private var activeCallerName: String = ""
    private var callProgressSec: Int = 0

    // TBT repeating keep-alive tracking
    private var activeManeuverChar: Char? = null
    private var activeDistanceMeters: Int = 0
    private var activeStreetName: String = ""
    private var activeRoundaboutExit: Int = 0
    private var activeTotalDistanceMeters: Int = 0
    private var activeEtaMinutes: Int = 15
    private var tbtRepeatRunnable: Runnable? = null

    // Pairing / Bonding state tracking
    private var pairingReceiver: BroadcastReceiver? = null
    private var connectingDevice: BluetoothDevice? = null
    private var connectedDeviceName: String = ""
    private var connectedDeviceAddress: String = ""

    val isConnected: Boolean
        get() = bluetoothGatt != null && generalChar != null

    val connectedName: String
        get() = connectedDeviceName

    val connectedAddress: String
        get() = connectedDeviceAddress

    fun isConnectedOrConnecting(): Boolean = (bluetoothGatt != null || connectingDevice != null)

    fun isBonded(address: String): Boolean {
        return try {
            val dev = bluetoothAdapter?.getRemoteDevice(address)
            dev?.bondState == BluetoothDevice.BOND_BONDED
        } catch (e: Exception) {
            false
        }
    }

    fun getBondedClusterAddress(): String? {
        return try {
            val bonded = bluetoothAdapter?.bondedDevices ?: return null
            val savedMac = context.getSharedPreferences("n160_ble", Context.MODE_PRIVATE).getString("last_connected_mac", null)
            if (savedMac != null) {
                val match = bonded.firstOrNull { it.address.equals(savedMac, ignoreCase = true) }
                if (match != null) return match.address
            }
            val pulsar = bonded.firstOrNull { dev ->
                val name = dev.name ?: ""
                name.contains("pulsar", ignoreCase = true) || 
                name.contains("n160", ignoreCase = true) || 
                name.contains("bajaj", ignoreCase = true) || 
                name.contains("otc", ignoreCase = true)
            }
            pulsar?.address
        } catch (e: Exception) {
            null
        }
    }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    fun updateBatteryLevel(pct: Int) {
        cachedBatteryPct = pct.coerceIn(0, 100)
    }

    fun startScan() {
        if (!isBluetoothEnabled()) {
            notifyError("Bluetooth is disabled. Please turn on Bluetooth.")
            return
        }

        bleScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bleScanner == null) {
            notifyError("BLE Scanner not available on this device.")
            return
        }

        if (isScanning) return

        val scanFilter = ScanFilter.Builder().build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        isScanning = true
        Log.i(TAG, "Starting BLE Scan for Pulsar N160 clusters...")
        notifyStatus("Scanning for nearby Pulsar N160 clusters...")

        try {
            bleScanner?.startScan(listOf(scanFilter), settings, scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting scan", e)
            isScanning = false
            notifyError("Unable to start BLE scan: ${e.message}")
            return
        }

        mainHandler.postDelayed({
            stopScan()
        }, 15000)
    }

    fun stopScan() {
        if (isScanning) {
            isScanning = false
            try {
                bleScanner?.stopScan(scanCallback)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping scan", e)
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let {
                val dev = it.device
                val name = dev.name ?: "Unknown Bike"
                val addr = dev.address
                val rssi = it.rssi

                val isBajajCluster = name.contains("BAJAJ", ignoreCase = true) ||
                        name.contains("PULSAR", ignoreCase = true) ||
                        name.contains("N160", ignoreCase = true) ||
                        name.contains("OTC", ignoreCase = true) ||
                        name.contains("CLUSTER", ignoreCase = true) ||
                        (it.scanRecord?.serviceUuids?.any { u -> u.uuid == SERVICE_UUID } == true)

                if (isBajajCluster || name.isNotEmpty()) {
                    mainHandler.post {
                        for (l in listeners) l.onDeviceDiscovered(name, addr, rssi)
                    }

                    // Automatic connection to already paired or previously connected cluster
                    try {
                        val prefs = context.getSharedPreferences("n160_ble", Context.MODE_PRIVATE)
                        val savedMac = prefs.getString("last_connected_mac", null)
                        val isBonded = dev.bondState == BluetoothDevice.BOND_BONDED
                        if (bluetoothGatt == null && connectingDevice == null) {
                            val isTargetCluster = (savedMac != null && addr.equals(savedMac, ignoreCase = true)) ||
                                    (isBonded && (isBajajCluster || name.contains("pulsar", ignoreCase = true)))
                            if (isTargetCluster) {
                                Log.i(TAG, "Auto-connecting to previously paired cluster: $name ($addr)")
                                mainHandler.post {
                                    notifyStatus("Auto-connecting to ${if (name.isNotEmpty()) name else "Pulsar N160"}...")
                                    connect(addr)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Auto-connect check error", e)
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            mainHandler.post {
                notifyError("BLE Scan failed with code: $errorCode")
            }
        }
    }

    /**
     * Connect to the Pulsar N160 Instrument Cluster.
     */
    fun connect(address: String) {
        stopScan()
        val device = bluetoothAdapter?.getRemoteDevice(address)
        if (device == null) {
            notifyError("Device with address $address not found")
            return
        }

        connectingDevice = device
        unregisterPairingReceiver()

        val bondState = device.bondState
        val devName = device.name ?: address
        Log.i(TAG, "Initiating connection to $devName ($address). BondState=$bondState")

        if (bondState == BluetoothDevice.BOND_BONDED) {
            notifyStatus("Paired with $devName. Establishing GATT connection...")
            connectGattDirect(device)
        } else {
            notifyStatus("Pairing with $devName... Please tap 'Pair' if prompted on phone.")

            val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            pairingReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (intent?.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                        val bondDev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                        val prevState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)

                        if (bondDev?.address == address) {
                            Log.i(TAG, "Bond state changed for ${bondDev.address}: state=$state (prev=$prevState)")

                            if (state == BluetoothDevice.BOND_BONDED) {
                                Log.i(TAG, "Pulsar cluster successfully bonded! Proceeding to connect GATT...")
                                unregisterPairingReceiver()
                                notifyStatus("Pairing successful! Connecting to bike console...")
                                connectGattDirect(bondDev)
                            } else if (state == BluetoothDevice.BOND_NONE && prevState == BluetoothDevice.BOND_BONDING) {
                                Log.w(TAG, "Bonding failed or cancelled by user.")
                                unregisterPairingReceiver()
                                notifyError("Bluetooth pairing cancelled or timed out. Please pair your Pulsar N160 in Android Settings.")
                                notifyDisconnected(address)
                            }
                        }
                    }
                }
            }
            context.registerReceiver(pairingReceiver, filter)

            val bondInitiated = device.createBond()
            Log.i(TAG, "device.createBond() initiated=$bondInitiated")
            if (!bondInitiated) {
                connectGattDirect(device)
            }
        }
    }

    private fun unregisterPairingReceiver() {
        pairingReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                // Ignore
            }
            pairingReceiver = null
        }
    }

    private fun connectGattDirect(device: BluetoothDevice) {
        disconnectGattOnly()

        Log.i(TAG, "Connecting GATT to ${device.name ?: device.address}...")
        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    fun disconnect() {
        unregisterPairingReceiver()
        stopTbtRepeating()
        val addr = connectingDevice?.address ?: connectedDeviceAddress
        cleanupConnection(addr, null)
    }

    private fun disconnectGattOnly() {
        stopHeartbeat()
        stopTbtRepeating()
        tbtChar = null
        generalChar = null
        missedCallChar = null
        notifyChar = null
        mediaChar = null
        controlChar = null
        connectedDeviceName = ""
        connectedDeviceAddress = ""
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing GATT", e)
        }
        bluetoothGatt = null
    }

    private fun cleanupConnection(address: String, errorMessage: String?) {
        disconnectGattOnly()
        mainHandler.post {
            if (errorMessage != null) {
                notifyError(errorMessage)
            }
            notifyDisconnected(address)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            val devName = gatt?.device?.name ?: "Pulsar N160 Cluster"
            val devAddr = gatt?.device?.address ?: ""

            Log.i(TAG, "onConnectionStateChange: status=$status, newState=$newState for $devName ($devAddr)")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "GATT connection failed with status=$status. Disconnecting.")
                cleanupConnection(devAddr, "GATT connection error ($status)")
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED && gatt != null) {
                Log.i(TAG, "GATT link established. Requesting high priority and discovering services...")
                connectedDeviceName = devName
                connectedDeviceAddress = devAddr
                notifyStatus("Discovering cluster services...")

                // Request high priority for low latency handshake
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)

                mainHandler.postDelayed({
                    gatt.discoverServices()
                }, 150)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "GATT disconnected from $devName ($devAddr)")
                cleanupConnection(devAddr, null)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            Log.i(TAG, "MTU negotiated: $mtu (status=$status)")
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) {
                Log.e(TAG, "Service discovery failed with status $status")
                cleanupConnection(gatt?.device?.address ?: "", "Cluster service discovery failed ($status)")
                return
            }

            Log.i(TAG, "Services discovered successfully! Traversing characteristics...")
            tbtChar = null
            generalChar = null
            missedCallChar = null
            notifyChar = null
            mediaChar = null
            controlChar = null

            for (service in gatt.services) {
                val sUuid = service.uuid.toString().lowercase()
                Log.d(TAG, "Discovered Service: $sUuid")
                for (char in service.characteristics) {
                    val cUuid = char.uuid.toString().lowercase()
                    Log.d(TAG, "  Discovered Characteristic: $cUuid (props=${char.properties})")

                    if (cUuid.startsWith("0110") || cUuid.contains("0110676e")) {
                        tbtChar = char
                        Log.i(TAG, ">>> Resolved Primary TBT Characteristic (0110 TBT_INFO_CHAR): $cUuid")
                    } else if (cUuid.startsWith("0210") || cUuid.contains("0210676e")) {
                        generalChar = char
                        Log.i(TAG, ">>> Resolved General Characteristic (0210 GENERAL_CHAR): $cUuid")
                    } else if (cUuid.startsWith("0310") || cUuid.contains("0310676e")) {
                        missedCallChar = char
                        Log.i(TAG, ">>> Resolved Missed Call Characteristic (0310 MISSED_CALL_CHAR): $cUuid")
                    } else if (cUuid.startsWith("0410") || cUuid.contains("0410676e")) {
                        notifyChar = char
                        Log.i(TAG, ">>> Resolved Notification Characteristic (0410 ALERTS_INFO_CHAR): $cUuid")
                    } else if (cUuid.startsWith("0610") || cUuid.contains("0610676e")) {
                        mediaChar = char
                        Log.i(TAG, ">>> Resolved Media Characteristic (0610 MEDIA_INFO_CHAR): $cUuid")
                    } else if (cUuid.startsWith("0a10") || cUuid.contains("0a10676e")) {
                        controlChar = char
                        enableNotifications(gatt, char)
                        Log.i(TAG, ">>> Resolved Handlebar Control Characteristic (0a10 CONTROL_CHAR): $cUuid")
                    }
                }
            }

            // CRITICAL VERIFICATION:
            if (tbtChar == null && generalChar == null) {
                Log.e(TAG, "Connected device does not contain Pulsar N160 GATT characteristics (0110 / 0210).")
                cleanupConnection(
                    gatt.device?.address ?: "",
                    "Device connected, but it is not a recognized Pulsar N160 cluster. Ensure bike ignition is ON."
                )
                return
            }

            // Request MTU 512 as in official Bajaj app
            gatt.requestMtu(512)

            val devName = gatt.device?.name ?: "Pulsar N160 Cluster"
            val devAddr = gatt.device?.address ?: ""
            connectedDeviceName = devName
            connectedDeviceAddress = devAddr

            // Send initial Phone Status (0xC0 Byte0 + Battery Bars) to activate cluster icons
            mainHandler.postDelayed({
                sendPhoneStatus()
                startHeartbeat()
            }, 300)

                        mainHandler.post {
                try {
                    context.getSharedPreferences("n160_ble", Context.MODE_PRIVATE)
                        .edit()
                        .putString("last_connected_mac", devAddr)
                        .putString("last_connected_name", devName)
                        .apply()
                    Log.i(TAG, "Saved last_connected_mac: $devAddr ($devName)")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not save last_connected_mac", e)
                }
                for (l in listeners) l.onConnected(devName, devAddr)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            Log.d(TAG, "onCharacteristicWrite (${characteristic?.uuid}): status=$status")
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == CHAR_CONTROLS) {
                Log.d(TAG, "Received Handlebar control event: size=${value.size}")
            }
        }
    }

    /**
     * Heartbeat Timer:
     * Transmits the 55-byte status frame every 1,000 ms (1 second), exactly as implemented
     * in the official Bajaj app (BleFeatures$2). The cluster display hardware requires
     * this continuous 1s heartbeat to keep the Bluetooth link icon and battery level active.
     */
    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatRunnable = object : Runnable {
            override fun run() {
                sendPhoneStatus()
                mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
        mainHandler.postDelayed(heartbeatRunnable!!, HEARTBEAT_INTERVAL_MS)
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let {
            mainHandler.removeCallbacks(it)
            heartbeatRunnable = null
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(char, true)
        val descriptor = char.getDescriptor(CLIENT_CONFIG_DESCRIPTOR)
        if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun writeCharacteristicSafely(char: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: return false

        val writeType = if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        char.writeType = writeType
        char.value = data
        val success = gatt.writeCharacteristic(char)
        return success
    }

    /**
     * Send Phone Status (Battery & Signal) to cluster (55-byte frame)
     *
     * Reverse-engineered Dalvik layout from CallFrame.phoneStatusNew():
     * - Byte 0: getStatusByteZero() -> 0xC0 | ((isHeadset ? 1 : 0) << 4) | (currentVolume & 0x0F)
     *   NOTE: 0xC0 (bits 7 and 6 = 1) is mandatory. Without it, the cluster ignores the status packet!
     * - Byte 1: getStatusByteOne() -> ((batteryBars & 0x07) << 3) | (callState & 0x07)
     *   Battery Bars:
     *     >80% -> 4 bars (Full battery)
     *     >60% -> 3 bars
     *     >40% -> 2 bars
     *     >20% -> 1 bar
     *     <=20% -> 0 bars (Low battery warning)
     * - Byte 2: signalStrength (0..4 bars)
     * - Byte 3: (callState == ACTIVE_CALL ? 1 : 0)
     * - Byte 4: (callState == ACTIVE_CALL ? 1 : 0)
     * - Bytes 5..19: Call progress seconds (if in call) / 0
     * - Byte 20: min(callerName.length, 30)
     * - Bytes 21..: callerName ASCII/UTF-8
     * - Byte 53: rolling heartbeat counter (0..255)
     *
     * Transmitted EXCLUSIVELY to GENERAL_CHAR (0210) with WRITE_TYPE_DEFAULT.
     */
    fun sendPhoneStatus(batteryPct: Int? = null, signalLevel: Int = 4) {
        val gc = generalChar ?: return

        val actualPct = batteryPct ?: run {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: cachedBatteryPct
        }
        cachedBatteryPct = actualPct

        val batteryBars = when {
            actualPct > 80 -> 4 // 4 bars (Full)
            actualPct > 60 -> 3 // 3 bars
            actualPct > 40 -> 2 // 2 bars
            actualPct > 20 -> 1 // 1 bar
            else -> 0           // 0 bars (Low)
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
        val curVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 10
        val volumeNibble = if (maxVol > 0) ((curVol * 15) / maxVol) and 0x0F else 10
        val isHeadset = audioManager?.isWiredHeadsetOn == true || audioManager?.isBluetoothA2dpOn == true

        val packet = ByteArray(55)

        // Byte 0: 0xC0 | ((isHeadset ? 1 : 0) << 4) | (volumeNibble & 0x0F)
        val headsetBit = if (isHeadset) 0x10 else 0x00
        packet[0] = (0xC0 or headsetBit or (volumeNibble and 0x0F)).toByte()

        // Byte 1: ((batteryBars & 0x07) << 3) | (callState & 0x07)
        packet[1] = (((batteryBars and 0x07) shl 3) or (activeCallState and 0x07)).toByte()

        // Byte 2: Signal strength bars (0..4)
        packet[2] = (signalLevel.coerceIn(0, 4)).toByte()

        // Byte 3 & 4: Active call indicator
        val isActiveCall = if (activeCallState == 3) 1 else 0
        packet[3] = isActiveCall.toByte()
        packet[4] = isActiveCall.toByte()

        // Bytes 5..6: Call progress duration if call is active or incoming
        if (activeCallState == 3 || activeCallState == 1) {
            callProgressSec++
            packet[5] = (callProgressSec and 0xFF).toByte()
            packet[6] = ((callProgressSec shr 8) and 0xFF).toByte()
        } else {
            callProgressSec = 0
        }

        // Caller Name at Byte 20 (len) and 21.. (bytes)
        if (activeCallerName.isNotEmpty()) {
            val sanitized = activeCallerName.replace(Regex("[\\p{So}\\p{Cn}\\-+^]"), "").trim()
            val nameBytes = sanitized.toByteArray(Charsets.UTF_8)
            val copyLen = minOf(nameBytes.size, 30)
            packet[20] = copyLen.toByte()
            System.arraycopy(nameBytes, 0, packet, 21, copyLen)
        }

        // Byte 53: Rolling heartbeat counter
        heartbeatCounter = (heartbeatCounter + 1) and 0xFF
        packet[53] = heartbeatCounter.toByte()

        // Send exclusively to GENERAL_CHAR (0210)
        writeCharacteristicSafely(gc, packet)

        mainHandler.post {
            for (l in listeners) l.onHeartbeatTick(actualPct, batteryBars, heartbeatCounter)
        }
    }

    /**
     * Simulate / Handle Incoming Phone Call on Bike Cluster LCD:
     * Displays caller ID on cluster and rings until answered or ended.
     */
    fun simulateIncomingCall(callerName: String) {
        activeCallState = 1 // INCOMING_CALL
        activeCallerName = callerName
        callProgressSec = 0
        sendPhoneStatus()
        Log.i(TAG, "Simulating Incoming Call from '$callerName' on Cluster LCD")
    }

    /**
     * Simulate Active Call
     */
    fun simulateActiveCall(callerName: String) {
        activeCallState = 3 // ACTIVE_CALL
        activeCallerName = callerName
        sendPhoneStatus()
        Log.i(TAG, "Simulating Active Call with '$callerName' on Cluster LCD")
    }

    /**
     * End Current Call:
     * Sends END_CALL frame (state=4), then reverts to NO_CALL (state=0).
     */
    fun endCall() {
        activeCallState = 4 // END_CALL
        sendPhoneStatus()
        Log.i(TAG, "Ended Call on Cluster LCD")

        mainHandler.postDelayed({
            activeCallState = 0 // NO_CALL
            activeCallerName = ""
            callProgressSec = 0
            sendPhoneStatus()
        }, 1200)
    }

    /**
     * Send Official Notification Alert (SMS / WhatsApp) to Cluster (40-byte frame):
     * Reverse-engineered Dalvik layout from CallFrame.alertsFrame():
     * - Byte 0: type (1 for SMS, 2 for WhatsApp)
     * - Byte 1: min(textBytes.length, 32)
     * - Bytes 2..: textBytes UTF-8
     * Transmitted to ALERTS_INFO_CHAR (0410).
     */
    fun sendNotificationAlert(type: Int, senderOrText: String) {
        val char = notifyChar ?: return
        val packet = ByteArray(40)
        val cleanBytes = senderOrText.take(32).toByteArray(Charsets.UTF_8)

        packet[0] = (type and 0xFF).toByte()
        packet[1] = minOf(cleanBytes.size, 32).toByte()
        System.arraycopy(cleanBytes, 0, packet, 2, minOf(cleanBytes.size, 32))

        writeCharacteristicSafely(char, packet)
        Log.i(TAG, "Sent 40-byte Notification Alert to cluster ($type, '$senderOrText')")
    }

    /**
     * Send Official Turn-by-Turn Navigation Instruction to the Pulsar N160 Cluster.
     * Supports all 22 official PrimaryTurns maneuvers ('I': Left, 'J': Right, 'C': Slight Left,
     * 'D': Slight Right, 'E': Sharp Left, 'F': Sharp Right, 'G': Straight, 'V': Merge,
     * 'Z': Keep Left, 'X': Keep Right, 'K': Ramp Left, 'L': Ramp Right, 'N': Roundabout Left,
     * 'U': Roundabout Right, 'O': U-Turn Left, 'P': U-Turn Right, 'Q': Fork Left, 'R': Fork Right,
     * 'Y': Ferry, 'T': Train, 'H': Destination, 'B': Wrong Way) and Roundabout Exits 1..8.
     *
     * Starts periodic refresh so the cluster doesn't time out to "NAV OFF" during a ride.
     */
    /**
     * Send Turn-by-Turn Instruction to the Pulsar N160 Cluster LCD
     *
     * Top Line Display: "TURN [distanceMeters] m" (or km)
     * Middle Graphic: 22 official maneuvers (e.g. 'I' = Left Arrow, 'J' = Right Arrow,
     *                 'P' = U-Turn Right, 'O' = U-Turn Left, 'U'/'N' = 8-spoke Roundabout with Exits 1..8)
     * Bottom Line Display: "ETD [totalDistanceMeters / 1000] Km" (Estimated Total Distance)
     *
     * Starts periodic refresh so the cluster doesn't time out to "NAV OFF" during a ride.
     */
    fun sendTurnByTurn(
        maneuver: Any,
        distanceMeters: Int,
        streetName: String,
        roundaboutExit: Int = 0,
        totalDistanceMeters: Int = 0,
        etaMinutes: Int = 15
    ) {
        val mChar = resolveManeuverChar(maneuver)
        activeManeuverChar = mChar
        activeDistanceMeters = distanceMeters
        activeStreetName = streetName
        activeRoundaboutExit = roundaboutExit
        activeTotalDistanceMeters = if (totalDistanceMeters > 0) totalDistanceMeters else distanceMeters
        activeEtaMinutes = if (etaMinutes > 0) etaMinutes else 15

        sendTurnByTurnRaw(mChar, distanceMeters, streetName, roundaboutExit, activeTotalDistanceMeters, activeEtaMinutes)
        startTbtRepeating()
    }

    fun sendTurnByTurn(maneuverId: Int, distanceMeters: Int, streetName: String) {
        val exit = if (maneuverId == 10 || maneuverId == 12 || maneuverId == 13) 1 else 0
        sendTurnByTurn(maneuverId as Any, distanceMeters, streetName, exit)
    }

    private fun startTbtRepeating() {
        stopTbtRepeating()
        tbtRepeatRunnable = object : Runnable {
            override fun run() {
                val mChar = activeManeuverChar
                if (mChar != null && isConnected) {
                    sendTurnByTurnRaw(mChar, activeDistanceMeters, activeStreetName, activeRoundaboutExit, activeTotalDistanceMeters, activeEtaMinutes)
                    mainHandler.postDelayed(this, TBT_REPEAT_INTERVAL_MS)
                }
            }
        }
        mainHandler.postDelayed(tbtRepeatRunnable!!, TBT_REPEAT_INTERVAL_MS)
    }

    private fun stopTbtRepeating() {
        tbtRepeatRunnable?.let {
            mainHandler.removeCallbacks(it)
            tbtRepeatRunnable = null
        }
    }

    private fun sendTurnByTurnRaw(
        maneuverChar: Char,
        distanceMeters: Int,
        streetName: String,
        roundaboutExit: Int = 0,
        totalDistanceMeters: Int = 0,
        etaMinutes: Int = 15
    ) {
        val char = tbtChar ?: run {
            Log.w(TAG, "Cannot send TBT: Primary TBT Characteristic 0110 not found")
            return
        }

        val cal = Calendar.getInstance()
        val isPm = cal.get(Calendar.AM_PM) == Calendar.PM
        val unitBit = 0 // 0 = meters

        val cleanStreet = streetName.take(31).toByteArray(Charsets.US_ASCII)
        val exitNibble = (roundaboutExit.coerceIn(0, 8) shl 4) and 0xF0

        val etaHrs = (etaMinutes / 60).coerceIn(0, 15)
        val etaMins = (etaMinutes % 60).coerceIn(0, 59)
        val totalDist = if (totalDistanceMeters > 0) totalDistanceMeters else distanceMeters

        // =====================================================================
        // 1. Official 48-Byte NavigationHelper Frame
        //    Byte 12 contains (unitBit | 0x04) -> 0x04 is GPS ACTIVE!
        // =====================================================================
        val packet48 = ByteArray(48)
        packet48[0] = ((if (isPm) 0x80 else 0x00) or (unitBit shl 4) or 0x01).toByte()
        packet48[1] = maneuverChar.code.toByte()

        // Distance to maneuver (Uint32 LE) -> Cluster renders "TURN [distance] m" on top row!
        packet48[2] = (distanceMeters and 0xFF).toByte()
        packet48[3] = ((distanceMeters shr 8) and 0xFF).toByte()
        packet48[4] = ((distanceMeters shr 16) and 0xFF).toByte()
        packet48[5] = ((distanceMeters shr 24) and 0xFF).toByte()

        // ETA minutes
        packet48[6] = (etaMins and 0xFF).toByte()

        // Roundabout exit (high nibble) | ETA hours (low nibble)
        packet48[7] = (exitNibble or (etaHrs and 0x0F)).toByte()

        // Total remaining distance (Uint32 LE) -> Cluster renders "ETD [totalDist/1000] Km" on bottom row!
        packet48[8] = (totalDist and 0xFF).toByte()
        packet48[9] = ((totalDist shr 8) and 0xFF).toByte()
        packet48[10] = ((totalDist shr 16) and 0xFF).toByte()
        packet48[11] = ((totalDist shr 24) and 0xFF).toByte()

        // Byte 12: (unitBit | 0x04) -> bit 2 (0x04) is GPS ACTIVE!
        packet48[12] = (unitBit or 0x04).toByte()
        packet48[13] = 0x00.toByte()

        // Byte 14: Street length, Bytes 15..: Street ASCII
        packet48[14] = cleanStreet.size.toByte()
        System.arraycopy(cleanStreet, 0, packet48, 15, cleanStreet.size)

        // Byte 47: Checksum (sum of bytes 0..46 & 0xFF)
        var sum48 = 0
        for (i in 0 until 47) {
            sum48 += (packet48[i].toInt() and 0xFF)
        }
        packet48[47] = (sum48 and 0xFF).toByte()

        // Send 48-byte packet
        writeCharacteristicSafely(char, packet48)

        // =====================================================================
        // 2. Official 49-Byte CallFrame Frame
        //    Byte 8 contains (DistanceUnits.KM | (GpsStatus.ACTIVE << 2)) = 0x04 (GPS ACTIVE flag)!
        // =====================================================================
        mainHandler.postDelayed({
            val packet49 = ByteArray(49)
            packet49[0] = ((if (isPm) 0x80 else 0x00) or (unitBit shl 4) or 0x01).toByte()
            packet49[1] = maneuverChar.code.toByte()

            // Distance to maneuver (Uint16 Big-Endian)
            packet49[2] = ((distanceMeters shr 8) and 0xFF).toByte()
            packet49[3] = (distanceMeters and 0xFF).toByte()

            packet49[4] = 0x00.toByte()
            packet49[5] = (exitNibble or (etaHrs and 0x0F)).toByte()

            // ETA: etaMinutes (Uint16 Big-Endian)
            packet49[6] = ((etaMinutes shr 8) and 0xFF).toByte()
            packet49[7] = (etaMinutes and 0xFF).toByte()

            // Byte 8: (DistanceUnits.KM | (GpsStatus.ACTIVE << 2)) -> 0x04 = GPS ACTIVE!
            packet49[8] = 0x04.toByte()
            packet49[9] = 0x00.toByte()

            // Byte 10: Street length, Bytes 11..: Street ASCII
            packet49[10] = cleanStreet.size.toByte()
            System.arraycopy(cleanStreet, 0, packet49, 11, cleanStreet.size)

            writeCharacteristicSafely(char, packet49)
        }, 80)
    }

    /**
     * Clear / Cancel Turn-by-Turn from the Cluster display (returns to normal speedometer / NAV OFF)
     */
    fun clearTurnByTurn() {
        stopTbtRepeating()
        activeManeuverChar = null
        activeDistanceMeters = 0
        activeStreetName = ""
        activeRoundaboutExit = 0
        val char = tbtChar ?: return

        // 48-byte clear frame (packet[0] = 0x00 NAV OFF)
        val packet48 = ByteArray(48)
        writeCharacteristicSafely(char, packet48)

        // 49-byte clear frame
        mainHandler.postDelayed({
            val packet49 = ByteArray(49)
            writeCharacteristicSafely(char, packet49)
        }, 80)

        Log.i(TAG, "Sent Nav OFF clear frames on 0110 to return cluster to normal speedometer")
    }

    /**
     * Sync phone clock to motorcycle digital clock
     */
    fun sendTimeSync() {
        val char = generalChar ?: controlChar ?: return
        val cal = Calendar.getInstance()
        val packet = byteArrayOf(
            0x01, // Time Sync Opcode
            (cal.get(Calendar.YEAR) - 2000).toByte(),
            (cal.get(Calendar.MONTH) + 1).toByte(),
            cal.get(Calendar.DAY_OF_MONTH).toByte(),
            cal.get(Calendar.HOUR_OF_DAY).toByte(),
            cal.get(Calendar.MINUTE).toByte(),
            cal.get(Calendar.SECOND).toByte()
        )
        writeCharacteristicSafely(char, packet)
        Log.d(TAG, "Sent Clock Time Sync to cluster")
    }

    fun cleanup() {
        stopScan()
        stopHeartbeat()
        stopTbtRepeating()
        unregisterPairingReceiver()
        disconnectGattOnly()
        listeners.clear()
    }

    private fun notifyStatus(status: String) {
        mainHandler.post {
            for (l in listeners) l.onStatusUpdate(status)
        }
    }

    private fun notifyError(message: String) {
        mainHandler.post {
            for (l in listeners) l.onError(message)
        }
    }

    private fun notifyDisconnected(address: String) {
        mainHandler.post {
            for (l in listeners) l.onDisconnected(address)
        }
    }
}
