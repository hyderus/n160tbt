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
    private var pendingNavClearRunnable: Runnable? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "GoogleMapsNotificationListener connected successfully!")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // Extract title, text, subText, bigText, summaryText, ticker
        val title = (extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG))?.toString() ?: ""
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString() ?: ""
        val subText = (extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_INFO_TEXT))?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
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

        val combined = "$title | $text | $subText | $bigText | $ticker".trim()
        if (combined.isEmpty()) return

        // Check if this notification is actually an active navigation cue
        if (!isNavigationNotification(combined, notification)) return

        // If a new navigation cue arrives, cancel any pending clear/NAV OFF debounce!
        pendingNavClearRunnable?.let {
            mainHandler.removeCallbacks(it)
            pendingNavClearRunnable = null
        }

        parseAndDispatch(title, text, subText, ticker, combined)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName == GOOGLE_MAPS_PACKAGE) {
            // Cancel any prior clear runnable
            pendingNavClearRunnable?.let { mainHandler.removeCallbacks(it) }

            // Check if there is still an active navigation notification from Google Maps
            try {
                val active = activeNotifications
                val hasActiveGmapsNav = active?.any { activeSbn ->
                    activeSbn.packageName == GOOGLE_MAPS_PACKAGE &&
                    activeSbn.notification != null &&
                    isNavigationNotification(extractAllNotificationText(activeSbn.notification), activeSbn.notification)
                } ?: false

                if (hasActiveGmapsNav) {
                    Log.i(TAG, "Google Maps notification removed, but active navigation notification still exists. Keeping cluster TBT active.")
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking active notifications: ${e.message}")
            }

            // Debounce for 2.5 seconds: Google Maps frequently removes and re-posts notifications during route recalculation or transitions
            pendingNavClearRunnable = Runnable {
                try {
                    val active = activeNotifications
                    val stillNavigating = active?.any { activeSbn ->
                        activeSbn.packageName == GOOGLE_MAPS_PACKAGE &&
                        activeSbn.notification != null &&
                        isNavigationNotification(extractAllNotificationText(activeSbn.notification), activeSbn.notification)
                    } ?: false

                    if (stillNavigating) {
                        Log.i(TAG, "Google Maps navigation resumed before debounce expired.")
                        return@Runnable
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error checking active notifications in debounce: ${e.message}")
                }

                Log.i(TAG, "Google Maps navigation notification truly removed -> Ending cluster TBT")
                isNavigating = false
                lastCueText = "Navigation ended"

                BajajBleService.instance?.bleManager?.clearTurnByTurn()

                mainHandler.post {
                    activeListener?.onGmapsNavEnded()
                }
            }
            mainHandler.postDelayed(pendingNavClearRunnable!!, 2500L)
        }
    }

    private fun extractAllNotificationText(n: Notification): String {
        val extras = n.extras ?: return n.tickerText?.toString() ?: ""
        val title = (extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG))?.toString() ?: ""
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString() ?: ""
        val subText = (extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val ticker = n.tickerText?.toString() ?: ""
        return "$title | $text | $subText | $bigText | $ticker".trim()
    }

    private fun isNavigationNotification(combined: String, n: Notification): Boolean {
        // 1. Category check
        val cat = n.category
        if (cat == Notification.CATEGORY_NAVIGATION || cat == "transport") {
            return true
        }

        // 2. Channel check (Android O+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channelId = n.channelId?.lowercase() ?: ""
            if (channelId.contains("navigation") || channelId.contains("direction")) {
                return true
            }
        }

        // 3. Action check: Google Maps navigation always has an "Exit" or "Stop" action
        n.actions?.forEach { action ->
            val at = action.title?.toString()?.lowercase() ?: ""
            if (at.contains("exit") || at.contains("stop") || at.contains("pause") ||
                at.contains("re-center") || at.contains("mute") || at.contains("বের হন") || at.contains("থামান")) {
                return true
            }
        }

        // 4. Keyword check in English and Bengali
        val lower = combined.lowercase()
        val hasNavKeywords = lower.contains("turn") || lower.contains("exit") ||
                lower.contains("roundabout") || lower.contains("rotary") || lower.contains("circle") ||
                lower.contains("u-turn") || lower.contains("uturn") || lower.contains("onto") ||
                lower.contains("toward") || lower.contains("towards") || lower.contains("continue") ||
                lower.contains("keep") || lower.contains("straight") || lower.contains("destination") ||
                lower.contains("arrived") || lower.contains("reached") || lower.contains("head") ||
                lower.contains("drive") || lower.contains("take") || lower.contains("merge") ||
                lower.contains("ramp") || lower.contains("fork") || lower.contains("slight") ||
                lower.contains("sharp") || lower.contains("bear") || lower.contains("stay") ||
                lower.contains("route") || lower.contains("in ") ||
                // Bengali keywords
                lower.contains("ডান") || lower.contains("বাম") || lower.contains("সোজা") ||
                lower.contains("ঘুরুন") || lower.contains("মোড়") || lower.contains("চলুন") ||
                lower.contains("গন্তব্য") || lower.contains("পৌঁছে") || lower.contains("ইউ-টার্ন") ||
                lower.contains("গোলচক্কর")

        // 5. Distance & Time patterns (English & Bengali)
        val hasDistancePattern = Pattern.compile(
            "(\\d+|[০-৯]+)\\s*(km|k\\.m\\.|m|meter|metre|ft|feet|mi|mile|কিমি|কি\\.মি\\.|মিটার|মি\\.)\\b",
            Pattern.CASE_INSENSITIVE
        ).matcher(combined).find()

        val hasTimePattern = Pattern.compile(
            "(\\d+|[০-৯]+)\\s*(min|hr|h|m|মিনিট|ঘণ্টা)\\b",
            Pattern.CASE_INSENSITIVE
        ).matcher(combined).find()

        val isOngoing = (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR or Notification.FLAG_FOREGROUND_SERVICE)) != 0

        return (hasNavKeywords || hasDistancePattern || hasTimePattern) && isOngoing
    }

    private fun normalizeBengaliDigits(input: String): String {
        val bengaliDigits = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')
        var result = input
        for (i in 0..9) {
            result = result.replace(bengaliDigits[i], ('0' + i))
        }
        return result
    }

    private fun parseAndDispatch(title: String, text: String, subText: String, ticker: String, rawCombined: String) {
        val normalizedCombined = normalizeBengaliDigits(rawCombined)
        val combined = normalizedCombined

        // 1. Parse Distance to next maneuver in meters (from title or text first)
        var distMeters = 100
        val distSource = if (title.isNotEmpty() || text.isNotEmpty()) "$title | $text" else combined
        val distMatcher = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(km|k\\.m\\.|m|meter|metre|ft|feet|mi|mile|কিমি|কি\\.মি\\.|মিটার|মি\\.)\\b",
            Pattern.CASE_INSENSITIVE
        ).matcher(normalizeBengaliDigits(distSource))

        if (distMatcher.find()) {
            val numStr = distMatcher.group(1)?.replace(',', '.') ?: "100"
            val num = numStr.toDoubleOrNull() ?: 100.0
            val unit = distMatcher.group(2)?.lowercase() ?: "m"
            distMeters = when {
                unit.startsWith("k") || unit.contains("কিমি") -> (num * 1000).toInt()
                unit in listOf("m", "meter", "metre") || unit.contains("মিটার") || unit.contains("মি") -> num.toInt()
                unit.startsWith("ft") || unit.startsWith("feet") -> (num * 0.3048).toInt()
                unit.startsWith("mi") -> (num * 1609.34).toInt()
                else -> num.toInt()
            }
        }

        // 2. Parse Total Distance (ETD) & ETA minutes from subText (e.g. "14 km • 25 min • 10:45 AM")
        var totalDistMeters = distMeters
        var etaMinutes = 15

        if (subText.isNotEmpty()) {
            val normSubText = normalizeBengaliDigits(subText)
            val totalMatcher = Pattern.compile(
                "(\\d+(?:[.,]\\d+)?)\\s*(km|k\\.m\\.|mi|mile|m|meter|কিমি|মিটার)\\b",
                Pattern.CASE_INSENSITIVE
            ).matcher(normSubText)
            if (totalMatcher.find()) {
                val numStr = totalMatcher.group(1)?.replace(',', '.') ?: ""
                val num = numStr.toDoubleOrNull()
                if (num != null) {
                    val unit = totalMatcher.group(2)?.lowercase() ?: "km"
                    totalDistMeters = when {
                        unit.startsWith("k") || unit.contains("কিমি") -> (num * 1000).toInt()
                        unit.startsWith("mi") -> (num * 1609.34).toInt()
                        else -> num.toInt()
                    }
                }
            }

            val etaMatcher = Pattern.compile("(?:(\\d+)\\s*(?:hr|hour|h|ঘণ্টা)\\s*)?(\\d+)\\s*(?:min|m|মিনিট)\\b", Pattern.CASE_INSENSITIVE).matcher(normSubText)
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
        if (!exitMatcher.find()) {
            exitMatcher = Pattern.compile("(\\d+)(?:\\s*নং|তম)?\\s*(?:এক্সিট|প্রস্থান)", Pattern.CASE_INSENSITIVE).matcher(combined)
        }
        if (exitMatcher.find()) {
            roundaboutExit = exitMatcher.group(1)?.toIntOrNull() ?: 0
        }

        // 4. Parse Maneuver Character
        val cLower = combined.lowercase()
        val maneuverChar: Char = when {
            cLower.contains("u-turn") || cLower.contains("uturn") || cLower.contains("u turn") || cLower.contains("ইউ-টার্ন") -> {
                if (cLower.contains("left") || cLower.contains("বাম")) 'O' else 'P' // O: U-Turn Left, P: U-Turn Right
            }
            cLower.contains("roundabout") || cLower.contains("rotary") || cLower.contains("traffic circle") || cLower.contains("গোলচক্কর") -> {
                if (roundaboutExit == 0) roundaboutExit = 1
                if (cLower.contains("left") || cLower.contains("বাম")) 'N' else 'U' // N: Roundabout Left, U: Roundabout Right
            }
            cLower.contains("sharp left") || cLower.contains("hard left") || cLower.contains("তীব্র বাম") || cLower.contains("শার্প বাম") -> 'E'
            cLower.contains("sharp right") || cLower.contains("hard right") || cLower.contains("তীব্র ডান") || cLower.contains("শার্প ডান") -> 'F'
            cLower.contains("slight left") || cLower.contains("bear left") || cLower.contains("veer left") || cLower.contains("হালকা বাম") -> 'C'
            cLower.contains("slight right") || cLower.contains("bear right") || cLower.contains("veer right") || cLower.contains("হালকা ডান") -> 'D'
            cLower.contains("turn left") || cLower.contains("take the left") || cLower.contains("বামে মোড়") || cLower.contains("বাম দিকে") -> 'I'
            cLower.contains("turn right") || cLower.contains("take the right") || cLower.contains("ডানে মোড়") || cLower.contains("ডান দিকে") -> 'J'
            cLower.contains("keep left") || cLower.contains("stay left") || cLower.contains("বামে থাকুন") -> 'Z'
            cLower.contains("keep right") || cLower.contains("stay right") || cLower.contains("ডানে থাকুন") -> 'X'
            cLower.contains("fork left") -> 'Q'
            cLower.contains("fork right") -> 'R'
            cLower.contains("ramp left") -> 'K'
            cLower.contains("ramp right") || cLower.contains("take the ramp") -> 'L'
            cLower.contains("merge") -> 'V'
            cLower.contains("arrived") || cLower.contains("destination") || cLower.contains("reached") || cLower.contains("গন্তব্য") || cLower.contains("পৌঁছে") -> 'H'
            cLower.contains("straight") || cLower.contains("continue") || cLower.contains("head") || cLower.contains("সোজা") || cLower.contains("চলুন") -> 'G'
            else -> {
                // Fallback directional checks
                if (cLower.contains("left") || cLower.contains("বাম")) 'I' else if (cLower.contains("right") || cLower.contains("ডান")) 'J' else 'G'
            }
        }

        // 5. Parse Road / Street Name
        var street = ""
        val streetMatcher1 = Pattern.compile("(?:onto|towards?|বরাবর)\\s+([^•|\\n]+)", Pattern.CASE_INSENSITIVE).matcher(combined)
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
        return raw.replace(Regex("\\d+(?:[.,]\\d+)?\\s*(km|k\\.m\\.|m|meter|metre|ft|feet|mi|mile|কিমি|মিটার|মি\\.)\\b", RegexOption.IGNORE_CASE), "")
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
            "min", "remaining", "eta", "মোড়", "ঘুরুন", "চলুন", "সোজা", "গন্তব্য", "পৌঁছে"
        ).any { sl.contains(it) }
    }
}
