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
    
    companion object {
        @Volatile private var instance: SaraAccessibilityService? = null
        fun getInstance(): SaraAccessibilityService? = instance
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "Accessibility Service created")
    }
    
    override fun onDestroy() {
        instance = null
        super.onDestroy()
        Log.d(TAG, "Accessibility Service destroyed")
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
        val success = performClick(node)
        root.recycle()
        callback(gson.toJson(mapOf("success" to success, "text" to text)))
    }

    fun findElement(text: String, callback: (String) -> Unit) {
        val root = rootInActiveWindow ?: return callback(gson.toJson(mapOf("error" to "No hay ventana activa")))
        val nodes = findNodesByText(root, text)
        root.recycle()
        if (nodes.isEmpty()) {
            return callback(gson.toJson(mapOf("error" to "Texto no encontrado: $text", "found" to false)))
        }
        val node = nodes[0]
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        callback(gson.toJson(mapOf(
            "found" to true,
            "text" to text,
            "bounds" to mapOf(
                "left" to bounds.left,
                "top" to bounds.top,
                "right" to bounds.right,
                "bottom" to bounds.bottom,
                "centerX" to bounds.exactCenterX(),
                "centerY" to bounds.exactCenterY()
            )
        )))
    }

    fun tapScreen(x: Int, y: Int, callback: (String) -> Unit) {
        val root = rootInActiveWindow ?: return callback(gson.toJson(mapOf("error" to "No hay ventana activa")))
        val node = findNodeAtLocation(root, x, y)
        if (node == null) {
            root.recycle()
            return callback(gson.toJson(mapOf("error" to "No hay elemento en ($x, $y)", "success" to false)))
        }
        val success = performClick(node)
        node.recycle()
        root.recycle()
        callback(gson.toJson(mapOf("success" to success, "x" to x, "y" to y)))
    }

    fun getClipboardText(callback: (String) -> Unit) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        callback(gson.toJson(mapOf("text" to text)))
    }

    fun setClipboardText(text: String, callback: (String) -> Unit) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("SaraService", text)
        clipboard.primaryClip = clip
        callback(gson.toJson(mapOf("success" to true)))
    }

    // ========== HELPER METHODS ==========

    private fun nodeToXml(node: AccessibilityNodeInfo, depth: Int): String {
        val indent = "  ".repeat(depth)
        val className = node.className ?: "Unknown"
        val nodeText = node.text ?: ""
        val contentDesc = node.contentDescription ?: ""
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        
        val attrs = mutableListOf<String>()
        attrs.add("class=\"$className\"")
        if (nodeText.isNotEmpty()) attrs.add("text=\"$nodeText\"")
        if (contentDesc.isNotEmpty()) attrs.add("content-desc=\"$contentDesc\"")
        attrs.add("bounds=\"[$bounds.left,$bounds.top][$bounds.right,$bounds.bottom]\"")
        attrs.add("clickable=\"${node.isClickable}\"")
        attrs.add("enabled=\"${node.isEnabled}\"")
        attrs.add("focusable=\"${node.isFocusable}\"")
        attrs.add("scrollable=\"${node.isScrollable}\"")
        attrs.add("long-clickable=\"${node.isLongClickable}\"")
        attrs.add("password=\"${node.isPassword}\"")
        attrs.add("selected=\"${node.isSelected}\"")
        attrs.add("visible-to-user=\"${node.isVisibleToUser}\"")
        
        val childrenXml = StringBuilder()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            childrenXml.append(nodeToXml(child, depth + 1))
            child.recycle()
        }
        
        val attrsStr = attrs.joinToString(' ')
        return if (childrenXml.isNotEmpty()) {
            "$indent<node $attrsStr>\n${childrenXml}$indent</node>\n"
        } else {
            "$indent<node $attrsStr />\n"
        }
    }

    private fun findNodesByText(node: AccessibilityNodeInfo, text: String): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        fun search(n: AccessibilityNodeInfo) {
            val nodeText = n.text?.toString() ?: ""
            val nodeDesc = n.contentDescription?.toString() ?: ""
            if (nodeText.contains(text, ignoreCase = true) || nodeDesc.contains(text, ignoreCase = true)) {
                results.add(n)
            }
            for (i in 0 until n.childCount) {
                val child = n.getChild(i) ?: continue
                search(child)
                child.recycle()
            }
        }
        search(node)
        return results
    }

    private fun findNodeAtLocation(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.contains(x, y)) {
            for (i in node.childCount - 1 downTo 0) {
                val child = node.getChild(i) ?: continue
                val result = findNodeAtLocation(child, x, y)
                if (result != null) {
                    child.recycle()
                    return result
                }
                child.recycle()
            }
            return node
        }
        return null
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            @Suppress("DEPRECATION")
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }
}