# System Architecture & State Synchronization

## State Machine
The system maintains a distributed state across Wear OS, Firebase RTDB, and the ESP32 Base Station:

- **NORMAL**: Standby state. ESP32 alert LED is OFF. Watch GPS is idle.
- **SOS_DETECTED**: Distress active. Watch writes GPS coordinates to Firebase every 10s. ESP32 alert LED is ON. Telegram alerts repeat every 10s.
- **RESET**: Capacitive touch on ESP32 (GPIO 4) pushes `status = "NORMAL"` to Firebase. Smartwatch halts GPS polling upon detection.

## REST vs WebSocket Synchronization
- **Watch -> Firebase**: Native Firebase Android SDK uses long-lived WebSockets for low-latency synchronization and reactive event listeners.
- **ESP32 -> Firebase**: Uses lightweight HTTP REST calls (`HTTPClient.GET` polling every 3s and `HTTPClient.PUT` for state clearing).
