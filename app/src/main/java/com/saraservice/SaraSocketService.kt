package com.saraservice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * SaraSocketService - Servicio principal que expone funcionalidades nativas Android via socket TCP
 * Puerto por defecto: 7775
 */
class SaraSocketService : Service() {
    private val TAG = "SaraSocketService"
    private val gson = Gson()
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val clientExecutor = Executors.newCachedThreadPool()
    private val clients = mutableListOf<Socket>()
    private val clientsLock = Any()

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): SaraSocketService = this@SaraSocketService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, createNotification())
        startSocketServer()
        Log.d(TAG, "SaraSocketService created and socket server started on port 7775")
    }

    override fun onDestroy() {
        isRunning = false
        stopSocketServer()
        acceptExecutor.shutdownNow()
        clientExecutor.shutdownNow()
        try {
            acceptExecutor.awaitTermination(5, TimeUnit.SECONDS)
            clientExecutor.awaitTermination(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Executor shutdown interrupted", e)
        }
        stopForeground(true)
        super.onDestroy()
        Log.d(TAG, "SaraSocketService destroyed")
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "sara_service_channel",
                "SaraSocketService",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Servicio de socket para control remoto"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "sara_service_channel")
            .setContentTitle("SaraSocketService")
            .setContentText("Escuchando en puerto 7775")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startSocketServer() {
        isRunning = true
        acceptExecutor.execute {
            try {
                serverSocket = ServerSocket(7775)
                serverSocket?.reuseAddress = true
                serverSocket?.soTimeout = 1000
                while (isRunning) {
                    try {
                        val client = serverSocket?.accept()
                        client?.let {
                            synchronized(clientsLock) {
                                clients.add(it)
                            }
                            handleClient(it)
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // Timeout normal; continuar loop
                    } catch (e: Exception) {
                        if (isRunning) Log.e(TAG, "Error accepting connection", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting socket server", e)
            }
        }
    }

    private fun stopSocketServer() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing server socket", e)
        }
        synchronized(clientsLock) {
            clients.forEach { runCatching { it.close() } }
            clients.clear()
        }
    }

    private fun handleClient(socket: Socket) {
        clientExecutor.execute {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream()), true)

                var line: String?
                while (isRunning && !socket.isClosed) {
                    line = reader.readLine()
                    if (line == null) break
                    val response = processCommand(line.trim())
                    writer.println(response)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Client handling error", e)
            } finally {
                synchronized(clientsLock) {
                    clients.remove(socket)
                }
                runCatching { socket.close() }
            }
        }
    }

    private fun processCommand(command: String): String {
        return try {
            val parts = command.split(" ", limit = 2)
            val cmd = parts[0].lowercase()
            val args = if (parts.size > 1) parts[1] else ""

            when (cmd) {
                "ping" -> gson.toJson(mapOf("status" to "pong", "timestamp" to System.currentTimeMillis()))
                "status" -> getStatus()
                "sms_send" -> sendSmsCommand(args)
                "sms_list" -> listSmsCommand(args)
                "camera_list" -> listCamerasCommand()
                "camera_capture" -> capturePhotoCommand(args)
                "location_get" -> getLocationCommand()
                "vibrate" -> vibrateCommand(args)
                "telephony_info" -> getTelephonyInfoCommand()
                "base64_encode" -> base64EncodeCommand(args)
                "base64_decode" -> base64DecodeCommand(args)
                "json_parse" -> jsonParseCommand(args)
                "json_stringify" -> jsonStringifyCommand(args)
                else -> gson.toJson(
                    mapOf(
                        "error" to "Comando desconocido: $cmd",
                        "available" to listOf(
                            "ping", "status", "sms_send", "sms_list", "camera_list",
                            "camera_capture", "location_get", "vibrate", "telephony_info",
                            "base64_encode", "base64_decode", "json_parse", "json_stringify"
                        )
                    )
                )
            }
        } catch (e: Exception) {
            gson.toJson(mapOf("error" to "Error procesando comando: ${e.message}"))
        }
    }

    private fun getStatus(): String {
        return gson.toJson(
            mapOf(
                "service" to "SaraSocketService",
                "running" to isRunning,
                "port" to 7775,
                "connected_clients" to clients.size,
                "timestamp" to System.currentTimeMillis()
            )
        )
    }

    private fun sendSmsCommand(args: String): String {
        val parts = args.split(" ", limit = 2)
        if (parts.size < 2) return gson.toJson(mapOf("error" to "Uso: sms_send <numero> <mensaje>"))
        return sendSms(parts[0], parts[1])
    }

    private fun sendSms(number: String, message: String): String {
        if (number.isEmpty() || message.isEmpty()) {
            return gson.toJson(mapOf("error" to "Número y mensaje requeridos"))
        }
        if (!hasPermission(Manifest.permission.SEND_SMS) || !hasPermission(Manifest.permission.READ_SMS)) {
            return gson.toJson(mapOf("error" to "Faltan permisos SMS (SEND_SMS, READ_SMS)"))
        }
        return try {
            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage(number, null, message, null, null)
            gson.toJson(mapOf("success" to true, "number" to number))
        } catch (e: Exception) {
            gson.toJson(mapOf("error" to "Error enviando SMS: ${e.message}"))
        }
    }

    private fun listSmsCommand(args: String): String {
        return gson.toJson(mapOf("error" to "No implementado - requiere ContentResolver"))
    }

    private fun listCamerasCommand(): String {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            return gson.toJson(mapOf("error" to "Falta permiso CAMERA"))
        }
        return try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraIds = cameraManager.cameraIdList
            val cameras = cameraIds.map { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                mapOf(
                    "id" to id,
                    "facing" to when (facing) {
                        0 -> "front"
                        1 -> "back"
                        2 -> "external"
                        else -> "unknown"
                    }
                )
            }
            gson.toJson(mapOf("cameras" to cameras))
        } catch (e: Exception) {
            gson.toJson(mapOf("error" to "Error listando cámaras: ${e.message}"))
        }
    }

    private fun capturePhotoCommand(args: String): String {
        return gson.toJson(mapOf("error" to "No implementado - requiere Camera2 API completa"))
    }

    private fun getLocationCommand(): String {
        val hasFine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val hasCoarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!hasFine && !hasCoarse) {
            return gson.toJson(mapOf("error" to "Permiso de ubicación no concedido"))
        }
        return try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val provider = when {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }
            if (provider == null) {
                return gson.toJson(mapOf("error" to "No hay proveedores de ubicación habilitados"))
            }
            val location = locationManager.getLastKnownLocation(provider)
            if (location != null) {
                gson.toJson(
                    mapOf(
                        "latitude" to location.latitude,
                        "longitude" to location.longitude,
                        "accuracy" to location.accuracy,
                        "provider" to provider,
                        "timestamp" to location.time
                    )
                )
            } else {
                gson.toJson(mapOf("error" to "Ubicación no disponible"))
            }
        } catch (e: SecurityException) {
            gson.toJson(mapOf("error" to "Permiso de ubicación no concedido"))
        } catch (e: Exception) {
            gson.toJson(mapOf("error" to "Error obteniendo ubicación: ${e.message}"))
        }
    }

    private fun vibrateCommand(args: String): String {
        if (!hasPermission(Manifest.permission.VIBRATE)) {
            return gson.toJson(mapOf("error" to "Falta permiso VIBRATE"))
        }
        val duration = args.toLongOrNull() ?: 500
        return try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
            gson.toJson(mapOf("success" to true, "duration_ms" to duration))
        } catch (e: Exception) {
            gson.toJson(mapOf("error" to "Error vibrando: ${e.message}"))
        }
    }

    private fun getTelephonyInfoCommand(): String {
        if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            return gson.toJson(mapOf("error" to "Falta permiso READ_PHONE_STATE"))
        }
        return try {
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            gson.toJson(
                mapOf(
                    "device_id" to telephonyManager.deviceId,
                    "sim_serial" to telephonyManager.simSerialNumber,
                    "network_operator" to telephonyManager.networkOperatorName,
                    "network_type" to telephonyManager.networkType,
                    "phone_type" to telephonyManager.phoneType,
                    "data_state" to telephonyManager.dataState,
                    "call_state" to telephonyManager.callState
                )
            )
        } catch (e: Exception) {
            gson.toJson(mapOf("error" to "Error obteniendo info telefonía: ${e.message}"))
        }
    }

    private fun base64EncodeCommand(args: String): String {
        return try {
            val encoded = Base64.encodeToString(args.toByteArray(), Base64.NO_WRAP)
            gson.toJson(mapOf("encoded" to encoded))
        } catch (e: Exception) {
            gson.toJson(mapOf("error" to "Error codificando Base64: ${e.message}"))
        }
    }

    private fun base64DecodeCommand(args: String): String {
        return try {
            val decoded = String(Base64.decode(args, Base64.NO_WRAP))
            gson.toJson(mapOf("decoded" to decoded))
        } catch (e: Exception) {
            gson.toJson(mapOf("error" to "Error decodificando Base64: ${e.message}"))
        }
    }

    private fun jsonParseCommand(args: String): String {
        return try {
            val json = JsonParser.parseString(args).asJsonObject
            gson.toJson(mapOf("parsed" to json))
        } catch (e: Exception) {
            gson.toJson(mapOf("error" to "JSON inválido: ${e.message}"))
        }
    }

    private fun jsonStringifyCommand(args: String): String {
        return try {
            val json = JsonParser.parseString(args).asJsonObject
            gson.toJson(mapOf("stringified" to gson.toJson(json)))
        } catch (e: Exception) {
            gson.toJson(mapOf("error" to "Error stringify JSON: ${e.message}"))
        }
    }
}

