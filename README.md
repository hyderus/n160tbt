# Pulsar N160 USD Bluetooth Cluster & Turn-by-Turn GPS Navigation

A dedicated, modern Android app for the **Bajaj Pulsar N160 USD (Dual Channel ABS)** motorcycle that seamlessly interfaces with the digital instrument cluster via Bluetooth Low Energy (BLE) and delivers real-time Turn-by-Turn (TBT) navigation, telemetry logging, caller alerts, and a secure document vault.

![Cherry Blossom Badge](icon-192.png)

## 🚀 Key Features

- **Pulsar N160 Cluster Link (BLE):**
  - Connects to the motorcycle's digital instrument cluster via BLE GATT service.
  - Auto-reconnects to paired/bonded Pulsar clusters automatically on app launch and resume.
  - Sends incoming call and SMS alert notifications directly to the cluster LCD.

- **Turn-by-Turn (TBT) Google Maps Sync:**
  - Automated integration with Google Maps Turn-by-Turn notifications.
  - 1-Tap Google Maps Launch with **automatic search bar focus** (opens Google Maps directly into destination search with active keyboard and recent history).
  - Translates maneuver icons (straight, turn right/left, U-turn, roundabouts, highway exit) and remaining distance directly into cluster display packets.

- **Motorcycle Document Vault:**
  - Offline-safe encrypted local storage for National ID (NID), Driving Licence, Tax Token, and Smart Card (beta).
  - Supports image and PDF document capture with automatic corner detection and enhancement.

- **Automatic GPS Ride Tracker:**
  - Automatically records ride logs when the bike connects and disconnects over Bluetooth.
  - Captures date, route distance, duration, top speed, and average speed without manual intervention.

- **Console Utilities & Alerts (CT Menu):**
  - Clean card-based hardware testing suite for incoming call alerts, missed calls, SMS notifications, and navigation maneuvers.

- **Android Compatibility:**
  - Compatible with Android 7.0 (Nougat / API 24) through **Android 17 (API 36)**.
  - Fully supports modern foreground service types (`connectedDevice`), predictive back gestures, and 16 KB page-size memory architectures.

---

## 📦 Direct Download

- **Ready-to-Install APK:** [`n160.apk`](n160.apk) (Compiled with SDK 36, Version 1.1)

---

## 🛠 Project Structure

```
├── n160.apk                 # Compiled Android Application Package
├── android-n160/            # Android Studio Kotlin project
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── AndroidManifest.xml
│   │   │   ├── assets/       # Embedded responsive dashboard HTML/JS
│   │   │   ├── kotlin/       # Native BLE, A11y & Service implementations
│   │   │   │   └── com/example/n160/
│   │   │   │       ├── MainActivity.kt
│   │   │   │       ├── BajajBleManager.kt
│   │   │   │       ├── BajajBleService.kt
│   │   │   │       ├── GoogleMapsNotificationListener.kt
│   │   │   │       └── MapSearchAccessibilityService.kt
│   │   │   └── res/          # Mipmap densities, drawables & configs
│   │   └── build.gradle.kts
│   └── gradlew.bat
├── index.html               # Responsive dashboard web view
└── README.md
```

---

## 🔧 Building from Source

```bash
cd android-n160
./gradlew assembleDebug
```
The output APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📄 License
MIT License. Developed for Bajaj Pulsar N160 USD riders.
