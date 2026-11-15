package com.example.n160

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class MapSearchAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MapSearchA11y"

        @Volatile
        var shouldFocusSearch: Boolean = false

        @Volatile
        var searchTriggerTime: Long = 0L

        var isConnected: Boolean = false
            private set

        fun triggerSearchFocus() {
            shouldFocusSearch = true
            searchTriggerTime = System.currentTimeMillis()
            Log.d(TAG, "Search focus triggered at $searchTriggerTime")
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        isConnected = true
        Log.i(TAG, "MapSearchAccessibilityService connected.")
    }

    override fun onDestroy() {
        super.onDestroy()
        isConnected = false
        Log.i(TAG, "MapSearchAccessibilityService destroyed.")
    }

    override fun onInterrupt() {
        Log.w(TAG, "MapSearchAccessibilityService interrupted.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!shouldFocusSearch) return
        val now = System.currentTimeMillis()
        if (now - searchTriggerTime > 12000L) {
            shouldFocusSearch = false
            return
        }

        val pkgName = event?.packageName?.toString() ?: ""
        if (pkgName != "com.google.android.apps.maps") {
            return
        }

        handler.postDelayed({
            if (shouldFocusSearch) {
                tryClickSearchBox()
            }
        }, 150)
    }

    fun tryClickSearchBox() {
        if (!shouldFocusSearch) return
        val root = rootInActiveWindow ?: return

        // Check if the search input is already focused and active
        val activeSearchNodes = root.findAccessibilityNodeInfosByViewId("com.google.android.apps.maps:id/search_omnibox_edit_text")
        if (activeSearchNodes.isNotEmpty()) {
            for (node in activeSearchNodes) {
                if (node.isFocused) {
                    Log.d(TAG, "Search omnibox edit text is already focused!")
                    shouldFocusSearch = false
                    return
                }
            }
        }

        var clicked = false

        // 1. Try finding by search omnibox text box id
        val omniboxNodes = root.findAccessibilityNodeInfosByViewId("com.google.android.apps.maps:id/search_omnibox_text_box")
        for (node in omniboxNodes) {
            if (performClickOnNodeOrParents(node)) {
                Log.d(TAG, "Clicked search_omnibox_text_box via AccessibilityNodeInfo")
                clicked = true
                break
            }
        }

        // 2. Try finding by text "Search here"
        if (!clicked) {
            val textNodes = root.findAccessibilityNodeInfosByText("Search here")
            for (node in textNodes) {
                if (performClickOnNodeOrParents(node)) {
                    Log.d(TAG, "Clicked node with text 'Search here' via AccessibilityNodeInfo")
                    clicked = true
                    break
                }
            }
        }

        // 3. Try finding by "Choose destination"
        if (!clicked) {
            val destNodes = root.findAccessibilityNodeInfosByText("Choose destination")
            for (node in destNodes) {
                if (performClickOnNodeOrParents(node)) {
                    Log.d(TAG, "Clicked node with text 'Choose destination' via AccessibilityNodeInfo")
                    clicked = true
                    break
                }
            }
        }

        // 4. Fallback: Dispatch gesture tap directly on the search bar
        if (!clicked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val rect = Rect()
            var targetX = 0f
            var targetY = 0f

            if (omniboxNodes.isNotEmpty()) {
                omniboxNodes[0].getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    targetX = rect.centerX().toFloat()
                    targetY = rect.centerY().toFloat()
                }
            }

            if (targetX == 0f || targetY == 0f) {
                val dm = resources.displayMetrics
                targetX = dm.widthPixels * 0.40f
                targetY = dm.heightPixels * 0.089f // around Y=250 on 2800h screen
            }

            val clickPath = Path().apply { moveTo(targetX, targetY) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(clickPath, 0, 50))
                .build()

            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Search tap gesture completed at ($targetX, $targetY)")
                }
            }, null)
            clicked = true
        }

        if (clicked) {
            shouldFocusSearch = false
        } else {
            // Retry in 350ms
            handler.postDelayed({
                if (shouldFocusSearch) {
                    tryClickSearchBox()
                }
            }, 350)
        }
    }

    private fun performClickOnNodeOrParents(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        while (current != null) {
            if (current.isClickable) {
                val res = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (res) return true
            }
            current = current.parent
        }
        return false
    }
}
