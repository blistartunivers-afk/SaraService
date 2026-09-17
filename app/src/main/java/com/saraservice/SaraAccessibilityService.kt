package com.saraservice

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.gson.Gson
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SaraAccessibilityService : AccessibilityService() {
    private val TAG = "SaraAccessibilityService"
    private val gson = Gson()
    private val pendingActions = ConcurrentHashMap<String, (String) -> Unit>()
    
    // Binder para comunicación con SaraSocketService
    private val binder = AccessibilityBinder()
    
    inner class AccessibilityBinder : Binder() {
        fun getService(): SaraAccessibilityService = this@SaraAccessibilityService
    }
    
    override fun onBind(intent: Intent): IBinder {
        return binder
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Accessibility Service created")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No necesitamos procesar eventos, solo servir como backend para actions
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility Service connected")
        
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }
        setServiceInfo(info)
    }

    // ========== MÉTODOS PÚBLICOS PARA EL SOCKET SERVICE ==========

    fun getScreenXml(callback: (String) -> Unit) {
        val root = rootInActiveWindow ?: return callback(gson.toJson(mapOf("error" to "No hay ventana activa")))
        val xml = nodeToXml(root, 0)
        root.recycle()
        callback(gson.toJson(mapOf("xml" to xml, "timestamp" to System.currentTimeMillis())))
    }

    fun clickText(text: String, callback: (String) -> Unit) {
        val root = rootInActiveWindow ?: return callback(gson.toJson(mapOf("error" to "No hay ventana activa")))
        val nodes = findNodesByText(root, text)
        if (nodes.isEmpty()) {
            root.recycle()
            return callback(gson.toJson(mapOf("error" to "Texto no encontrado: $text", "found" to false)))
        }
        val node = nodes[0]
        val result = performClick(node)
        node.recycle()
        root.recycle()
        callback(gson.toJson(mapOf("clicked" to result, "text" to text, "found" to true)))
    }

    fun findElement(text: String, callback: (String) -> Unit) {
        val root = rootInActiveWindow ?: return callback(gson.toJson(mapOf("error" to "No hay ventana activa")))
        val nodes = findNodesByText(root, text)
        val results = nodes.map { node ->
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            mapOf(
                "text" to node.text.toString(),
                "class" to node.className.toString(),
                "bounds" to mapOf(
                    "left" to bounds.left,
                    "top" to bounds.top,
                    "right" to bounds.right,
                    "bottom" to bounds.bottom,
                    "center_x" to (bounds.left + bounds.right) / 2,
                    "center_y" to (bounds.top + bounds.bottom) / 2
                ),
                "clickable" to node.isClickable,
                "enabled" to node.isEnabled
            )
        }
        nodes.forEach { it.recycle() }
        root.recycle()
        callback(gson.toJson(mapOf("elements" to results, "count" to results.size)))
    }

    fun tap(x: Int, y: Int, callback: (String) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val gesture = android.accessibilityservice.GestureDescription.Builder()
            val path = android.graphics.Path()
            path.moveTo(x.toFloat(), y.toFloat())
            path.lineTo(x.toFloat(), y.toFloat())
            gesture.addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50))
            val result = dispatchGesture(gesture.build(), object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription) {
                    callback(gson.toJson(mapOf("tapped" to true, "x" to x, "y" to y)))
                }
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription) {
                    callback(gson.toJson(mapOf("tapped" to false, "error" to "Gesture cancelled", "x" to x, "y" to y)))
                }
            }, null)
            if (!result) {
                callback(gson.toJson(mapOf("tapped" to false, "error" to "No se pudo despachar gesture", "x" to x, "y" to y)))
            }
        } else {
            callback(gson.toJson(mapOf("error" to "Requiere Android 7.0+", "tapped" to false)))
        }
    }

    // ========== HELPERS ==========

    private fun nodeToXml(node: AccessibilityNodeInfo, depth: Int): String {
        val sb = StringBuilder()
        val indent = "  ".repeat(depth)
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        
        sb.append("$indent<node ")
        sb.append("class=\"${node.className}\" ")
        sb.append("text=\"${escapeXml(node.text.toString())}\" ")
        sb.append("content_desc=\"${escapeXml(node.contentDescription.toString())}\" ")
        sb.append("bounds=\"[$bounds.left,$bounds.top][$bounds.right,$bounds.bottom]\" ")
        sb.append("clickable=\"${node.isClickable}\" ")
        sb.append("enabled=\"${node.isEnabled}\" ")
        sb.append("focusable=\"${node.isFocusable}\" ")
        sb.append("scrollable=\"${node.isScrollable}\" ")
        sb.append("long_clickable=\"${node.isLongClickable}\" ")
        sb.append("password=\"${node.isPassword}\" ")
        sb.append("visible=\"${node.isVisibleToUser}\" ")
        
        val childCount = node.childCount
        if (childCount > 0) {
            sb.append(">\n")
            for (i in 0 until childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    sb.append(nodeToXml(child, depth + 1))
                    child.recycle()
                }
            }
            sb.append("$indent</node>\n")
        } else {
            sb.append("/>\n")
        }
        return sb.toString()
    }

    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun findNodesByText(node: AccessibilityNodeInfo, text: String): MutableList<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        val searchText = text.lowercase()
        
        fun search(n: AccessibilityNodeInfo) {
            val nodeText = n.text?.toString()?.lowercase() ?: ""
            val descText = n.contentDescription?.toString()?.lowercase() ?: ""
            if (nodeText.contains(searchText) || descText.contains(searchText)) {
                results.add(n)
            } else {
                for (i in 0 until n.childCount) {
                    val child = n.getChild(i)
                    if (child != null) {
                        search(child)
                        child.recycle()
                    }
                }
            }
        }
        search(node)
        return results
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            @Suppress("DEPRECATION")
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    companion object {
        private var instance: SaraAccessibilityService? = null
        fun getInstance(): SaraAccessibilityService? = instance
        
        init {
            instance = this
        }
    }
}