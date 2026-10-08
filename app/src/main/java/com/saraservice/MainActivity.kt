package com.saraservice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityServiceInfo
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private val TAG = "SaraMainActivity"
    private val PERMISSION_REQUEST_CODE = 100
    
    private val requiredPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        Manifest.permission.CAMERA,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.SYSTEM_ALERT_WINDOW,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.NEARBY_WIFI_DEVICES
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        val statusText = findViewById<TextView>(R.id.statusText)
        val startServiceBtn = findViewById<Button>(R.id.startServiceBtn)
        val accessibilityBtn = findViewById<Button>(R.id.accessibilityBtn)
        val overlayBtn = findViewById<Button>(R.id.overlayBtn)
        val permissionsBtn = findViewById<Button>(R.id.permissionsBtn)
        
        updateUI()
        
        startServiceBtn.setOnClickListener {
            startSaraService()
        }
        
        accessibilityBtn.setOnClickListener {
            openAccessibilitySettings()
        }
        
        overlayBtn.setOnClickListener {
            openOverlaySettings()
        }
        
        permissionsBtn.setOnClickListener {
            requestAllPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun updateUI() {
        val statusText = findViewById<TextView>(R.id.statusText)
        val isServiceRunning = isServiceRunning()
        val hasAccessibility = hasAccessibilityPermission()
        val hasOverlay = hasOverlayPermission()
        val hasPermissions = hasAllPermissions()
        
        val status = StringBuilder()
        status.append("SaraService v1.0.0\n\n")
        status.append("🔌 Servicio: ").append(if (isServiceRunning) "ACTIVO ✅" else "DETENIDO ❌").append("\n")
        status.append("♿ Accessibility: ").append(if (hasAccessibility) "ACTIVO ✅" else "INACTIVO ❌").append("\n")
        status.append("🪟 Overlay: ").append(if (hasOverlay) "CONCEDIDO ✅" else "DENEGADO ❌").append("\n")
        status.append("🔐 Permisos: ").append(if (hasPermissions) "COMPLETOS ✅" else "PARCIALES ⚠️").append("\n\n")
        status.append("Puerto: 7775 (TCP)\n")
        status.append("Protocolo: JSON línea por línea\n")
        status.append("\nAcciones disponibles:\n")
        status.append("- get_screen, click_text, find_element, tap\n")
        status.append("- current_app, get_battery, get_gps\n")
        status.append("- speak, listen, torch, vibrate\n")
        status.append("- notification, cancel_notification\n")
        status.append("- send_sms, read_sms, call_log, make_call\n")
        status.append("- get_clipboard, set_clipboard\n")
        status.append("- open_url, share_text\n")
        status.append("- scan_wifi, get_wifi_info\n")
        status.append("- get_sensors, device_info")
        
        statusText.text = status.toString()
    }

    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(android.app.ActivityManager::class.java)
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (service.service.className == "com.saraservice.SaraSocketService") {
                return true
            }
        }
        return false
    }

    private fun hasAccessibilityPermission(): Boolean {
        val accessibilityManager = getSystemService(AccessibilityManager::class.java)
        val services = accessibilityManager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        return services.any { it.id.contains("com.saraservice") }
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun startSaraService() {
        val intent = Intent(this, SaraSocketService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Iniciando SaraService...", Toast.LENGTH_SHORT).show()
        updateUI()
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
        }
    }

    private fun requestAllPermissions() {
        val toRequest = requiredPermissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            Toast.makeText(this, "Todos los permisos ya concedidos", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val granted = grantResults.count { it == PackageManager.PERMISSION_GRANTED }
            Toast.makeText(this, "$granted de ${permissions.size} permisos concedidos", Toast.LENGTH_LONG).show()
            updateUI()
        }
    }
}
