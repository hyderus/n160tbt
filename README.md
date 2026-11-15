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

## 📦 Direct Downloads & Releases

- **Android (Direct Install):** [`n160.apk`](n160.apk) (Compiled with SDK 36, Version 1.3.0)
- **iPhone / iOS (Sideloadable):** [`n160.ipa`](https://github.com/hyderus/n160tbt/releases) (Built via GitHub Actions cloud runner)

### 📲 Installing on iPhone with Sideloadly (Free & Easy):
1. Download **[Sideloadly](https://sideloadly.io/)** (Free for Windows/macOS).
2. Connect your iPhone to your PC via USB cable.
3. Download `n160.ipa` from the [Releases](https://github.com/hyderus/n160tbt/releases) page and drag it into Sideloadly.
4. Enter your free Apple ID and click **Start**. The app installs directly onto your iPhone home screen!

---

## 🛠 Project Structure

```
├── n160.apk                 # Compiled Android Application Package
├── android-n160/            # Android Studio Kotlin project
├── ios-n160/                # Swift iOS native project (CoreBluetooth, WKWebView, VisionKit)
│   ├── N160/
│   │   ├── ViewController.swift   # BLE GATT 0x1000, CoreLocation & WebKit Bridge
│   │   ├── Info.plist             # iOS permissions & background BLE modes
│   │   └── www/                   # Embedded responsive dashboard HTML/JS
│   └── project.yml          # XcodeGen automated project configuration
├── .github/workflows/       # Automated cloud compilation for iOS (.ipa)
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
