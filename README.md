# SaraService — APK Nativa para Control Total de Android

Servicio Android que expone un **socket TCP en puerto 7775** para que blist (Termux) pueda controlar el dispositivo nativamente: visión de pantalla, toque, voz, GPS, batería, notificaciones, SMS, llamadas, portapapeles, WiFi, sensores, linterna, vibración y más.

---

## 🚀 Compilación Rápida (GitHub Actions)

1. **Sube este proyecto a GitHub** (repo público o privado)
2. **Actions → Build SaraService APK → Run workflow**
3. **Descarga el APK** desde Artifacts (debug o release)
4. **Instala en tu Android**: `adb install app-debug.apk`

---

## 🛠 Compilación Local

```bash
# Requisitos: JDK 17+, Android SDK (API 34)
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools

cd SaraService
chmod +x gradlew
./gradlew assembleDebug
# APK en: app/build/outputs/apk/debug/app-debug.apk
```

---

## 📱 Instalación y Configuración en Android

### 1. Instala la APK
```bash
adb install app-debug.apk
# O transfiere el .apk al móvil e instálalo manualmente
```

### 2. Abre la app y concede permisos
1. Abre **SaraService** en el launcher
2. Pulsa **"🔐 Solicitar Todos los Permisos"** → permite todo
3. Pulsa **"♿ Abrir Ajustes de Accesibilidad"** → busca **"Sara Accessibility"** → **ACTÍVALO**
4. Pulsa **"🪟 Permiso Dibujar sobre otras apps"** → permite para SaraService
4. Pulsa **"▶ Iniciar SaraService (Puerto 7775)"**

### 3. Verifica desde Termux
```bash
cd /data/data/com.termux/files/home
python3 -c "
from sara_native_bridge import SaraNativeBridge
b = SaraNativeBridge()
print('Batería:', b.get_battery())
print('GPS:', b.get_gps())
print('Pantalla:', b.get_screen())
print('App actual:', b.current_app())
"
```

---

## 🔌 Protocolo Socket (Puerto 7775)

**Conexión**: TCP `127.0.0.1:7775`
**Formato**: JSON línea por línea (newline-delimited)

### Request:
```json
{"action": "get_battery", "params": {}}
{"action": "click_text", "params": {"text": "Aceptar"}}
{"action": "tap", "params": {"x": 500, "y": 1000}}
{"action": "speak", "params": {"text": "Hola desde Sara"}}
```

### Response:
```json
{"level": 85, "status": "DISCHARGING", "health": 2, "technology": "Li-ion", "plugged": false, "present": true}
{"clicked": true, "text": "Aceptar", "found": true}
{"tapped": true, "x": 500, "y": 1000}
{"status": "speaking", "text": "Hola desde Sara"}
```

---

## 🎯 Actions Disponibles

| Categoría | Actions |
|-----------|---------|
| **Visión/Pantalla** | `get_screen`, `click_text`, `find_element`, `tap`, `current_app` |
| **Sistema** | `get_battery`, `get_gps`, `device_info` |
| **Voz/Audio** | `speak`, `listen` |
| **Hardware** | `torch`, `vibrate` |
| **Notificaciones** | `notification`, `cancel_notification` |
| **Comunicación** | `send_sms`, `read_sms`, `call_log`, `make_call` |
| **Portapapeles/Web** | `get_clipboard`, `set_clipboard`, `open_url`, `share_text` |
| **Red/WiFi** | `scan_wifi`, `get_wifi_info` |
| **Sensores** | `get_sensors` |

---

## ⚙️ Arquitectura

```
blist (Termux)          SaraService (Android APK)
┌─────────────────┐     ┌─────────────────────────────┐
│ sara_native_bridge.py  │◄──►│ SaraSocketService (puerto 7775) │
│ 14 tools expuestas     │     │  - JSON request/response    │
└─────────────────┘     │  - Thread pool por cliente  │
                        │  - Foreground service       │
                        ├─────────────────────────────┤
                        │ SaraAccessibilityService    │
                        │  - get_screen (XML dump)    │
                        │  - click_text / find_element│
                        │  - tap (gesture injection)  │
                        └─────────────────────────────┘
```

---

## 🔐 Permisos Críticos

| Permiso | Para qué | Obligatorio |
|---------|----------|-------------|
| `BIND_ACCESSIBILITY_SERVICE` | Visión pantalla, click, tap | **SÍ** (control total) |
| `SYSTEM_ALERT_WINDOW` | Overlay, tap injection | **SÍ** (tap) |
| `ACCESS_FINE_LOCATION` | GPS preciso | Sí |
| `CAMERA` + `FLASHLIGHT` | Linterna (torch) | Sí |
| `READ_SMS` / `SEND_SMS` | SMS | Opcional |
| `READ_CALL_LOG` / `CALL_PHONE` | Llamadas | Opcional |
| `RECORD_AUDIO` | Listen (futuro) | Opcional |
| `POST_NOTIFICATIONS` | Notificaciones Android 13+ | Sí |
| `BLUETOOTH_CONNECT/SCAN` | Bluetooth | Opcional |

---

## 🐛 Troubleshooting

| Problema | Solución |
|----------|----------|
| `Connection refused` | Servicio no iniciado → pulsa "Iniciar SaraService" en la app |
| `get_screen` → error Accessibility | Activa "Sara Accessibility" en Ajustes > Accesibilidad |
| `tap` → error | Concede permiso "Dibujar sobre otras apps" |
| `send_sms` → error | Concede permiso SMS en la app o Ajustes |
| Puerto 7775 ocupado | Solo una instancia del servicio; reinicia la app |
| `listen` → no implementado | Usa `termux:api` o implementa `SpeechRecognizer` |

---

## 📦 Estructura del Proyecto

```
SaraService/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/saraservice/
│       │   ├── SaraApplication.kt
│       │   ├── SaraSocketService.kt          # Socket server puerto 7775
│       │   ├── SaraSocketService_part2.kt    # Resto de tools
│       │   ├── SaraAccessibilityService.kt   # Visión/tap/click
│       │   ├── MainActivity.kt               # UI configuración
│       │   └── BootReceiver.kt               # Auto-inicio en boot
│       ├── res/
│       │   ├── layout/activity_main.xml
│       │   ├── values/strings.xml
│       │   └── xml/accessibility_service_config.xml
│       └── ...
├── build.gradle
├── settings.gradle
├── gradle/wrapper/gradle-wrapper.properties
├── gradlew / gradlew.bat
├── .github/workflows/build.yml               # CI/CD GitHub Actions
└── README.md
```

---

## 🔄 Integración con blist

El bridge `sara_native_bridge.py` ya está listo en `/data/data/com.termux/files/home/`.

```python
from sara_native_bridge import SARA_NATIVE_TOOLS, SaraNativeBridge

# Tools disponibles directamente en blist:
# sara_get_screen, sara_click_text, sara_find_element, sara_tap,
# sara_current_app, sara_get_battery, sara_get_gps, sara_speak,
# sara_listen, sara_torch, sara_vibrate, sara_notification,
# sara_cancel_notification, sara_open_url, sara_share_text

bridge = SaraNativeBridge()
print(bridge.get_battery())
print(bridge.get_screen())  # Requiere Accessibility ON
```

---

## 📄 Licencia

MIT — Úsalo, modifícalo, intégralo en blist.

---

**¿Problemas?** Abre un Issue en el repo o revisa los logs: `adb logcat | grep SaraSocketService`
