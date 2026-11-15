package com.example.n160

import android.app.Notification
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.regex.Pattern

class GoogleMapsNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "GMapsNotification"
        const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"

        @Volatile
        var isNavigating = false
            private set

        @Volatile
        var lastCueText = "No active Google Maps navigation"
            private set

        @Volatile
        var lastManeuverChar = 'G'
            private set

        @Volatile
        var lastDistanceMeters = 0
            private set

        @Volatile
        var lastStreet = ""
            private set

        @Volatile
        var lastRoundaboutExit = 0
            private set

        @Volatile
        var lastTotalDistanceMeters = 0
            private set

        @Volatile
        var lastEtaMinutes = 15
            private set

        interface GmapsListener {
            fun onGmapsNavUpdate(maneuver: Char, distanceMeters: Int, street: String, roundaboutExit: Int, fullText: String, totalDistanceMeters: Int = 0, etaMinutes: Int = 15)
            fun onGmapsNavEnded()
        }

        private var activeListener: GmapsListener? = null

        fun setListener(listener: GmapsListener?) {
            activeListener = listener
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "GoogleMapsNotificationListener connected successfully!")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // Extract title, text, subText, ticker
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
        val ticker = notification.tickerText?.toString() ?: ""

        // 1. Check for SMS or WhatsApp to light up cluster envelope icon!
        val isSms = pkg in listOf(
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.android.mms",
            "com.android.messaging"
        )
        val isWhatsApp = pkg == "com.whatsapp"

        if (isSms || isWhatsApp) {
            val sender = title.takeIf { it.isNotEmpty() } ?: (if (isSms) "SMS" else "WhatsApp")
            val alertType = if (isSms) 1 else 2
            Log.i(TAG, "Incoming ${if (isSms) "SMS" else "WhatsApp"} from '$sender' -> Lighting cluster envelope icon")
            val ble = BajajBleService.instance?.bleManager
            if (ble != null && ble.isConnected) {
                ble.sendNotificationAlert(alertType, sender)
            }
            return
        }

        if (pkg != GOOGLE_MAPS_PACKAGE) return

        val combined = "$title | $text | $subText | $ticker".trim()
        if (combined.isEmpty()) return

        // Check if this notification is actually an active navigation cue
        // (Google Maps also sends location sharing or timeline notifications which we ignore)
        if (!isNavigationNotification(combined, notification)) return

        parseAndDispatch(title, text, subText, ticker)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName == GOOGLE_MAPS_PACKAGE) {
            Log.i(TAG, "Google Maps navigation notification removed -> Ending cluster TBT")
            isNavigating = false
            lastCueText = "Navigation ended"

            BajajBleService.instance?.bleManager?.clearTurnByTurn()

            mainHandler.post {
                activeListener?.onGmapsNavEnded()
            }
        }
    }

    private fun isNavigationNotification(combined: String, n: Notification): Boolean {
        val lower = combined.lowercase()
        val hasNavKeywords = lower.contains("turn") || lower.contains("exit") ||
                lower.contains("roundabout") || lower.contains("u-turn") ||
                lower.contains("uturn") || lower.contains("onto") ||
                lower.contains("toward") || lower.contains("continue") ||
                lower.contains("keep") || lower.contains("straight") ||
                lower.contains("destination") || lower.contains("arrived") ||
                lower.contains("min") || lower.contains("km") || lower.contains(" m")

        return hasNavKeywords && (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
    }

    private fun parseAndDispatch(title: String, text: String, subText: String, ticker: String) {
        val combined = "$title | $text | $subText | $ticker".trim()

        // 1. Parse Distance to next maneuver in meters (from title or text first)
        var distMeters = 100
        val distSource = if (title.isNotEmpty() || text.isNotEmpty()) "$title | $text" else combined
        val distMatcher = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(km|k\\.m\\.|m|meter|metre|ft|feet|mi|mile)\\b",
            Pattern.CASE_INSENSITIVE
        ).matcher(distSource)

        if (distMatcher.find()) {
            val numStr = distMatcher.group(1)?.replace(',', '.') ?: "100"
            val num = numStr.toDoubleOrNull() ?: 100.0
            val unit = distMatcher.group(2)?.lowercase() ?: "m"
            distMeters = when {
                unit.startsWith("k") -> (num * 1000).toInt()
                unit in listOf("m", "meter", "metre") -> num.toInt()
                unit.startsWith("ft") || unit.startsWith("feet") -> (num * 0.3048).toInt()
                unit.startsWith("mi") -> (num * 1609.34).toInt()
                else -> num.toInt()
            }
        }

        // 2. Parse Total Distance (ETD) & ETA minutes from subText (e.g. "14 km • 25 min • 10:45 AM")
        var totalDistMeters = distMeters
        var etaMinutes = 15

        if (subText.isNotEmpty()) {
            val totalMatcher = Pattern.compile(
                "(\\d+(?:[.,]\\d+)?)\\s*(km|k\\.m\\.|mi|mile|m|meter)\\b",
                Pattern.CASE_INSENSITIVE
            ).matcher(subText)
            if (totalMatcher.find()) {
                val numStr = totalMatcher.group(1)?.replace(',', '.') ?: ""
                val num = numStr.toDoubleOrNull()
                if (num != null) {
                    val unit = totalMatcher.group(2)?.lowercase() ?: "km"
                    totalDistMeters = when {
                        unit.startsWith("k") -> (num * 1000).toInt()
                        unit.startsWith("mi") -> (num * 1609.34).toInt()
                        else -> num.toInt()
                    }
                }
            }

            val etaMatcher = Pattern.compile("(?:(\\d+)\\s*(?:hr|hour|h)\\s*)?(\\d+)\\s*(?:min|m)\\b", Pattern.CASE_INSENSITIVE).matcher(subText)
            if (etaMatcher.find()) {
                val hrs = etaMatcher.group(1)?.toIntOrNull() ?: 0
                val mins = etaMatcher.group(2)?.toIntOrNull() ?: 15
                etaMinutes = hrs * 60 + mins
            }
        }

        // 3. Parse Roundabout Exit (1..8)
        var roundaboutExit = 0
        var exitMatcher = Pattern.compile("(\\d+)(?:st|nd|rd|th)?\\s+exit", Pattern.CASE_INSENSITIVE).matcher(combined)
        if (!exitMatcher.find()) {
            exitMatcher = Pattern.compile("exit\\s+(\\d+)", Pattern.CASE_INSENSITIVE).matcher(combined)
        }
        if (exitMatcher.find()) {
            roundaboutExit = exitMatcher.group(1)?.toIntOrNull() ?: 0
        }

        // 3. Parse Maneuver Character
        val cLower = combined.lowercase()
        val maneuverChar: Char = when {
            cLower.contains("u-turn") || cLower.contains("uturn") || cLower.contains("u turn") -> {
                if (cLower.contains("left")) 'O' else 'P' // O: U-Turn Left, P: U-Turn Right
            }
            cLower.contains("roundabout") || cLower.contains("rotary") || cLower.contains("traffic circle") -> {
                if (roundaboutExit == 0) roundaboutExit = 1
                if (cLower.contains("left")) 'N' else 'U' // N: Roundabout Left, U: Roundabout Right
            }
            cLower.contains("sharp left") || cLower.contains("hard left") -> 'E'
            cLower.contains("sharp right") || cLower.contains("hard right") -> 'F'
            cLower.contains("slight left") || cLower.contains("bear left") || cLower.contains("veer left") -> 'C'
            cLower.contains("slight right") || cLower.contains("bear right") || cLower.contains("veer right") -> 'D'
            cLower.contains("turn left") || cLower.contains("take the left") -> 'I'
            cLower.contains("turn right") || cLower.contains("take the right") -> 'J'
            cLower.contains("keep left") || cLower.contains("stay left") -> 'Z'
            cLower.contains("keep right") || cLower.contains("stay right") -> 'X'
            cLower.contains("fork left") -> 'Q'
            cLower.contains("fork right") -> 'R'
            cLower.contains("ramp left") -> 'K'
            cLower.contains("ramp right") || cLower.contains("take the ramp") -> 'L'
            cLower.contains("merge") -> 'V'
            cLower.contains("arrived") || cLower.contains("destination") || cLower.contains("reached") -> 'H'
            cLower.contains("straight") || cLower.contains("continue") || cLower.contains("head") -> 'G'
            else -> {
                // Fallback directional checks
                if (cLower.contains("left")) 'I' else if (cLower.contains("right")) 'J' else 'G'
            }
        }

        // 4. Parse Road / Street Name
        var street = ""
        val streetMatcher1 = Pattern.compile("(?:onto|towards?)\\s+([^•|\\n]+)", Pattern.CASE_INSENSITIVE).matcher(combined)
        if (streetMatcher1.find()) {
            val cand = cleanStreetCandidate(streetMatcher1.group(1) ?: "")
            if (!isManeuverPhrase(cand)) street = cand
        }

        if (street.isEmpty()) {
            val streetMatcher2 = Pattern.compile("\\bon\\s+([^•|\\n]+)", Pattern.CASE_INSENSITIVE).matcher(combined)
            if (streetMatcher2.find()) {
                val cand = cleanStreetCandidate(streetMatcher2.group(1) ?: "")
                if (!isManeuverPhrase(cand)) street = cand
            }
        }

        if (street.isEmpty()) {
            for (part in listOf(text, title, subText)) {
                for (sub in part.split("•")) {
                    val cand = cleanStreetCandidate(sub)
                    if (cand.length >= 2 && !isManeuverPhrase(cand)) {
                        street = cand
                        break
                    }
                }
                if (street.isNotEmpty()) break
            }
        }

        if (street.isEmpty()) {
            street = if (maneuverChar == 'H') "Destination" else "Route"
        }

        // Clean to 30 ASCII chars
        street = street.take(30).trim()

        isNavigating = true
        lastManeuverChar = maneuverChar
        lastDistanceMeters = distMeters
        lastTotalDistanceMeters = totalDistMeters
        lastEtaMinutes = etaMinutes
        lastStreet = street
        lastRoundaboutExit = roundaboutExit
        lastCueText = "${distMeters}m $maneuverChar onto $street (ETD: ${totalDistMeters / 1000}km)"

        Log.i(TAG, "Google Maps Sync => Maneuver: '$maneuverChar', Dist: ${distMeters}m, ETD: ${totalDistMeters}m, ETA: ${etaMinutes}m, Street: '$street', Exit: $roundaboutExit")

        // Transmit live cue to physical motorcycle cluster via BajajBleManager
        val ble = BajajBleService.instance?.bleManager
        if (ble != null && ble.isConnected) {
            ble.sendTurnByTurn(maneuverChar, distMeters, street, roundaboutExit, totalDistMeters, etaMinutes)
        }

        mainHandler.post {
            activeListener?.onGmapsNavUpdate(maneuverChar, distMeters, street, roundaboutExit, combined, totalDistMeters, etaMinutes)
        }
    }

    private fun cleanStreetCandidate(raw: String): String {
        return raw.replace(Regex("\\d+(?:[.,]\\d+)?\\s*(km|k\\.m\\.|m|meter|metre|ft|feet|mi|mile)\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^[•|\\-–—\\s]+"), "")
            .replace(Regex("[•|\\-–—\\s]+$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isManeuverPhrase(s: String): Boolean {
        val sl = s.lowercase()
        return listOf(
            "turn", "fork", "ramp", "exit", "roundabout", "merge", "u-turn", "uturn",
            "the right", "the left", "destination", "arrived", "continue", "straight",
            "min", "remaining", "eta"
        ).any { sl.contains(it) }
    }
}
