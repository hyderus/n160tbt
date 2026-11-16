import UIKit
import WebKit
import CoreBluetooth
import CoreLocation
import VisionKit
import Photos

class ViewController: UIViewController, WKScriptMessageHandler, WKNavigationDelegate,
                      CBCentralManagerDelegate, CBPeripheralDelegate,
                      CLLocationManagerDelegate, VNDocumentCameraViewControllerDelegate,
                      UIImagePickerControllerDelegate, UINavigationControllerDelegate {

    private var webView: WKWebView!
    private var centralManager: CBCentralManager!
    private var connectedPeripheral: CBPeripheral?
    private var writeCharacteristic: CBCharacteristic?
    private var notifyCharacteristic: CBCharacteristic?
    private var discoveredPeripherals: [String: CBPeripheral] = [:]
    
    private let locationManager = CLLocationManager()
    private var isDarkMode: Bool = false
    private var activeDocScanKey: String?
    private var activeDocScanSide: String?

    // Cluster BLE UUIDs matching Bajaj Pulsar N160 / OTC Engineering spec
    private let serviceUuid = CBUUID(string: "00001000-0000-1000-8000-00805F9B34FB")
    private let shortServiceUuid = CBUUID(string: "1000")
    private let writeCharUuid = CBUUID(string: "00001001-0000-1000-8000-00805F9B34FB")
    private let notifyCharUuid = CBUUID(string: "00001002-0000-1000-8000-00805F9B34FB")

    override var preferredStatusBarStyle: UIStatusBarStyle {
        return isDarkMode ? .lightContent : .darkContent
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        setupWebView()
        setupBluetooth()
        setupLocation()
        loadLocalApp()
    }

    // MARK: - WKWebView Setup & Bridge Injection
    private func setupWebView() {
        let contentController = WKUserContentController()
        contentController.add(self, name: "iosBle")

        // Polyfill bridge: maps AndroidBle calls to iOS WebKit MessageHandler seamlessly
        let bridgeScriptSource = """
        window.isIOSDevice = true;
        window.AndroidBle = {
            startScan: function() {
                window.webkit.messageHandlers.iosBle.postMessage({ action: 'startScan' });
            },
            stopScan: function() {
                window.webkit.messageHandlers.iosBle.postMessage({ action: 'stopScan' });
            },
            connectCluster: function(uuid) {
                window.webkit.messageHandlers.iosBle.postMessage({ action: 'connectCluster', address: uuid });
            },
            disconnectCluster: function() {
                window.webkit.messageHandlers.iosBle.postMessage({ action: 'disconnectCluster' });
            },
            sendHexPayload: function(hexData) {
                window.webkit.messageHandlers.iosBle.postMessage({ action: 'sendHexPayload', data: hexData });
            },
            saveImageToGallery: function(base64Data, filename) {
                window.webkit.messageHandlers.iosBle.postMessage({ action: 'saveImageToGallery', data: base64Data, filename: filename });
            },
            launchCameraScan: function(docKey, side) {
                window.webkit.messageHandlers.iosBle.postMessage({ action: 'launchCameraScan', docKey: docKey, side: side });
            },
            getAppVersionName: function() {
                return '1.3.3';
            },
            getAppVersionCode: function() {
                return 7;
            },
            setSystemBarTheme: function(isDark) {
                window.webkit.messageHandlers.iosBle.postMessage({ action: 'setSystemBarTheme', isDark: isDark });
            },
            checkForUpdate: function(isManual) {
                window.webkit.messageHandlers.iosBle.postMessage({ action: 'checkForUpdate', isManual: isManual });
            },
            openGoogleMapsApp: function() {
                window.webkit.messageHandlers.iosBle.postMessage({ action: 'openGoogleMapsApp' });
            },
            getBondedClusterAddress: function() {
                return localStorage.getItem('n160_paired_cluster_mac') || '';
            }
        };
        """
        let userScript = WKUserScript(source: bridgeScriptSource, injectionTime: .atDocumentStart, forMainFrameOnly: false)
        contentController.addUserScript(userScript)

        let config = WKWebViewConfiguration()
        config.userContentController = contentController
        config.allowsInlineMediaPlayback = true
        config.preferences.setValue(true, forKey: "allowFileAccessFromFileURLs")

        webView = WKWebView(frame: view.bounds, configuration: config)
        webView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        webView.navigationDelegate = self
        webView.scrollView.bounces = false
        view.addSubview(webView)
    }

    private func loadLocalApp() {
        if let bundlePath = Bundle.main.path(forResource: "www", ofType: nil),
           let indexUrl = URL(string: "file://\(bundlePath)/index.html") {
            let accessUrl = URL(fileURLWithPath: bundlePath)
            webView.loadFileURL(indexUrl, allowingReadAccessTo: accessUrl)
        } else if let indexUrl = Bundle.main.url(forResource: "index", withExtension: "html", subdirectory: "www") {
            webView.loadFileURL(indexUrl, allowingReadAccessTo: indexUrl.deletingLastPathComponent())
        }
    }

    // MARK: - Script Message Handler (Calls from JS)
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let dict = message.body as? [String: Any],
              let action = dict["action"] as? String else { return }

        switch action {
        case "startScan":
            startScan()
        case "stopScan":
            stopScan()
        case "connectCluster":
            if let uuid = dict["address"] as? String {
                connect(uuid: uuid)
            }
        case "disconnectCluster":
            disconnect()
        case "sendHexPayload":
            if let hex = dict["data"] as? String {
                sendHex(hex)
            }
        case "saveImageToGallery":
            if let b64 = dict["data"] as? String, let name = dict["filename"] as? String {
                saveImageToGallery(base64: b64, filename: name)
            }
        case "launchCameraScan":
            let docKey = dict["docKey"] as? String ?? "doc"
            let side = dict["side"] as? String ?? "front"
            launchCameraScan(docKey: docKey, side: side)
        case "setSystemBarTheme":
            if let dark = dict["isDark"] as? Bool {
                isDarkMode = dark
                setNeedsStatusBarAppearanceUpdate()
            }
        case "openGoogleMapsApp":
            openGoogleMaps()
        case "checkForUpdate":
            let isManual = dict["isManual"] as? Bool ?? false
            checkUpdate(isManual: isManual)
        default:
            break
        }
    }

    // MARK: - CoreBluetooth Implementation
    private func setupBluetooth() {
        centralManager = CBCentralManager(delegate: self, queue: nil)
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn {
            print("BLE: Powered ON")
        } else {
            notifyJsClusterState("DISCONNECTED", address: "")
        }
    }

    private func startScan() {
        guard centralManager.state == .poweredOn else { return }
        discoveredPeripherals.removeAll()
        centralManager.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
    }

    private func stopScan() {
        centralManager.stopScan()
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let name = peripheral.name ?? (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? "Unknown Device"
        let uuid = peripheral.identifier.uuidString
        discoveredPeripherals[uuid] = peripheral

        let isN160Candidate = name.uppercased().contains("PULSAR") ||
                              name.uppercased().contains("N160") ||
                              name.uppercased().contains("BAJAJ") ||
                              name.uppercased().contains("OTC")

        if isN160Candidate || name != "Unknown Device" {
            let js = "if (window.onBleDeviceFound) { window.onBleDeviceFound('\(name)', '\(uuid)', \(RSSI.intValue)); }"
            webView.evaluateJavaScript(js, completionHandler: nil)
        }
    }

    private func connect(uuid: String) {
        guard let peripheral = discoveredPeripherals[uuid] else { return }
        connectedPeripheral = peripheral
        connectedPeripheral?.delegate = self
        notifyJsClusterState("CONNECTING", address: uuid)
        centralManager.connect(peripheral, options: nil)
    }

    private func disconnect() {
        if let p = connectedPeripheral {
            centralManager.cancelPeripheralConnection(p)
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        notifyJsClusterState("CONNECTED", address: peripheral.identifier.uuidString)
        peripheral.discoverServices([serviceUuid, shortServiceUuid])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        notifyJsClusterState("DISCONNECTED", address: peripheral.identifier.uuidString)
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        connectedPeripheral = nil
        writeCharacteristic = nil
        notifyCharacteristic = nil
        notifyJsClusterState("DISCONNECTED", address: peripheral.identifier.uuidString)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services else { return }
        for service in services {
            peripheral.discoverCharacteristics([writeCharUuid, notifyCharUuid], for: service)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let chars = service.characteristics else { return }
        for char in chars {
            if char.properties.contains(.write) || char.properties.contains(.writeWithoutResponse) {
                writeCharacteristic = char
            }
            if char.properties.contains(.notify) {
                notifyCharacteristic = char
                peripheral.setNotifyValue(true, for: char)
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let data = characteristic.value else { return }
        let hex = data.map { String(format: "%02hhX", $0) }.joined()
        let js = "if (window.onClusterDataReceived) { window.onClusterDataReceived('\(hex)'); }"
        webView.evaluateJavaScript(js, completionHandler: nil)
    }

    private func sendHex(_ hex: String) {
        guard let peripheral = connectedPeripheral, let char = writeCharacteristic else { return }
        let data = Data(hexString: hex)
        let chunkSize = 20
        var offset = 0
        while offset < data.count {
            let chunk = data.subdata(in: offset..<min(offset + chunkSize, data.count))
            let type: CBCharacteristicWriteType = char.properties.contains(.writeWithoutResponse) ? .withoutResponse : .withResponse
            peripheral.writeValue(chunk, for: char, type: type)
            offset += chunkSize
        }
    }

    private func notifyJsClusterState(_ state: String, address: String) {
        let js = "if (window.onClusterBleStateChanged) { window.onClusterBleStateChanged('\(state)', '\(address)'); }"
        DispatchQueue.main.async {
            self.webView.evaluateJavaScript(js, completionHandler: nil)
        }
    }

    // MARK: - CoreLocation Implementation
    private func setupLocation() {
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        locationManager.allowsBackgroundLocationUpdates = true
        locationManager.pausesLocationUpdatesAutomatically = false
        locationManager.requestAlwaysAuthorization()
        locationManager.startUpdatingLocation()
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }
        let speedKmH = max(0, loc.speed * 3.6)
        let js = "if (window.onGpsLocationUpdate) { window.onGpsLocationUpdate(\(loc.coordinate.latitude), \(loc.coordinate.longitude), \(speedKmH), \(loc.horizontalAccuracy)); }"
        webView.evaluateJavaScript(js, completionHandler: nil)
    }

    // MARK: - Camera & Vision Document Scanning
    private func launchCameraScan(docKey: String, side: String) {
        activeDocScanKey = docKey
        activeDocScanSide = side
        if #available(iOS 13.0, *), VNDocumentCameraViewController.isSupported {
            let scanner = VNDocumentCameraViewController()
            scanner.delegate = self
            present(scanner, animated: true)
        } else {
            let picker = UIImagePickerController()
            picker.delegate = self
            picker.sourceType = .camera
            present(picker, animated: true)
        }
    }

    @available(iOS 13.0, *)
    func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFinishWith scan: VNDocumentCameraScan) {
        controller.dismiss(animated: true) {
            guard scan.pageCount > 0 else { return }
            let image = scan.imageOfPage(at: 0)
            self.deliverScannedImage(image)
        }
    }

    @available(iOS 13.0, *)
    func documentCameraViewControllerDidCancel(_ controller: VNDocumentCameraViewController) {
        controller.dismiss(animated: true)
    }

    func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey : Any]) {
        picker.dismiss(animated: true) {
            if let image = info[.originalImage] as? UIImage {
                self.deliverScannedImage(image)
            }
        }
    }

    private func deliverScannedImage(_ image: UIImage) {
        guard let key = activeDocScanKey, let side = activeDocScanSide,
              let data = image.jpegData(compressionQuality: 0.85) else { return }
        let base64 = data.base64EncodedString()
        let js = "if (window.onNativeCameraScanResult) { window.onNativeCameraScanResult('\(key)', '\(side)', 'data:image/jpeg;base64,\(base64)'); }"
        DispatchQueue.main.async {
            self.webView.evaluateJavaScript(js, completionHandler: nil)
        }
    }

    // MARK: - Save to Photos Gallery
    private func saveImageToGallery(base64: String, filename: String) {
        var cleanB64 = base64
        if let comma = cleanB64.firstIndex(of: ",") {
            cleanB64 = String(cleanB64[cleanB64.index(after: comma)...])
        }
        guard let data = Data(base64Encoded: cleanB64), let image = UIImage(data: data) else { return }
        PHPhotoLibrary.requestAuthorization { status in
            guard status == .authorized || status == .limited else { return }
            PHPhotoLibrary.shared().performChanges({
                PHAssetChangeRequest.creationRequestForAsset(from: image)
            }) { success, _ in
                DispatchQueue.main.async {
                    let alert = UIAlertController(title: success ? "Saved" : "Error",
                                                  message: success ? "\(filename) saved to Photos." : "Failed to save to Photos.",
                                                  preferredStyle: .alert)
                    alert.addAction(UIAlertAction(title: "OK", style: .default))
                    self.present(alert, animated: true)
                }
            }
        }
    }

    // MARK: - Navigation App Launcher
    private func openGoogleMaps() {
        if let gmapsUrl = URL(string: "comgooglemaps://"), UIApplication.shared.canOpenURL(gmapsUrl) {
            UIApplication.shared.open(gmapsUrl)
        } else if let appleMaps = URL(string: "http://maps.apple.com/") {
            UIApplication.shared.open(appleMaps)
        }
    }

    // MARK: - In-App Update Checker
    private func checkUpdate(isManual: BooleanLiteralType) {
        guard let url = URL(string: "https://raw.githubusercontent.com/hyderus/n160tbt/main/version.json?t=\(Date().timeIntervalSince1970)") else { return }
        URLSession.shared.dataTask(with: url) { data, _, _ in
            guard let data = data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let serverCode = json["versionCode"] as? Int,
                  let serverName = json["versionName"] as? String else {
                return
            }
            let currentCode = 7
            let currentName = "1.3.3"
            DispatchQueue.main.async {
                if serverCode > currentCode {
                    let notes = (json["releaseNotes"] as? String ?? "").replacingOccurrences(of: "'", with: "\\'").replacingOccurrences(of: "\n", with: " ")
                    let apkUrl = json["apkUrl"] as? String ?? ""
                    let js = "if (window.onUpdateCheckResult) { window.onUpdateCheckResult('AVAILABLE', JSON.stringify({versionName: '\(serverName)', versionCode: \(serverCode), releaseNotes: '\(notes)', apkUrl: '\(apkUrl)'})); }"
                    self.webView.evaluateJavaScript(js, completionHandler: nil)
                } else if isManual {
                    let js = "if (window.onUpdateCheckResult) { window.onUpdateCheckResult('LATEST', JSON.stringify({versionName: '\(currentName)', versionCode: \(currentCode)})); }"
                    self.webView.evaluateJavaScript(js, completionHandler: nil)
                }
            }
        }.resume()
    }
}

// MARK: - Hex Data Conversion Helper
private extension Data {
    init(hexString: String) {
        self.init()
        var hex = hexString
        if hex.count % 2 != 0 { hex = "0" + hex }
        var index = hex.startIndex
        while index < hex.endIndex {
            let nextIndex = hex.index(index, offsetBy: 2)
            if let byte = UInt8(hex[index..<nextIndex], radix: 16) {
                append(byte)
            }
            index = nextIndex
        }
    }
}
