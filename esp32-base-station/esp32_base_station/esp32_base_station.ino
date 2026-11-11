#include <WiFi.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>

// 1. YOUR WI-FI CREDENTIALS
const char* ssid = "YOUR_WIFI_SSID";
const char* password = "YOUR_WIFI_PASSWORD";

// 2. YOUR FIREBASE DATABASE URL
const char* firebaseUrl = "https://YOUR_PROJECT_ID-default-rtdb.firebaseio.com/sos_status.json";

// 3. TELEGRAM CREDENTIALS
const String telegramBotToken = "YOUR_TELEGRAM_BOT_TOKEN";
const String telegramChatId = "YOUR_TELEGRAM_CHAT_ID";

// Pin Definitions
const int alertPin = 2;       // Built-in LED
const int touchPin = 4;       // GPIO 4 (Touch Pin T0)

// Touch Settings
int touchThreshold = 500; 
unsigned long lastTouchTime = 0;
const unsigned long touchDebounce = 3000; 

// Timer Settings
unsigned long lastCheckTime = 0;
const int checkInterval = 3000; // Check Firebase every 3 seconds

// Telegram Repeating Alert Settings
unsigned long lastTelegramTime = 0;
const int telegramInterval = 10000; // Send Telegram every 10 seconds (10000 ms)

// Store current SOS location
String currentLat = "0";
String currentLng = "0";
bool isSOSActive = false;

void setup() {
  Serial.begin(115200);
  pinMode(alertPin, OUTPUT);
  digitalWrite(alertPin, LOW); 

  Serial.println();
  Serial.print("Connecting to Wi-Fi: ");
  Serial.println(ssid);

  WiFi.begin(ssid, password);

  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }

  Serial.println("");
  Serial.println("Wi-Fi connected successfully!");
  Serial.println("System Ready. Touch GPIO 4 to reset SOS.");
}

void loop() {
  // 1. Check for Touch Input (Reset)
  int touchValue = touchRead(touchPin);
  if (touchValue < touchThreshold && (millis() - lastTouchTime > touchDebounce)) {
    Serial.println(" TOUCH DETECTED! Resetting SOS...");
    resetSOSviaFirebase();
    digitalWrite(alertPin, LOW); 
    isSOSActive = false; // Stop Telegram alerts
    lastTouchTime = millis();    
  }

  // 2. Check Firebase for SOS status
  if (millis() - lastCheckTime > checkInterval) {
    lastCheckTime = millis();
    checkFirebaseForSOS();
  }

  // 3. Send Repeating Telegram Alert if SOS is active
  if (isSOSActive && (millis() - lastTelegramTime > telegramInterval)) {
    sendTelegramAlert(currentLat, currentLng);
    lastTelegramTime = millis();
  }
}

// Function to send HTTP PUT request to Firebase to reset the status
void resetSOSviaFirebase() {
  if (WiFi.status() == WL_CONNECTED) {
    HTTPClient http;
    http.begin(firebaseUrl);
    http.addHeader("Content-Type", "application/json");
    int httpCode = http.PUT("{\"status\":\"NORMAL\"}");

    if (httpCode > 0) {
      Serial.println("✅ Successfully sent 'NORMAL' to Firebase!");
    } else {
      Serial.print("❌ Failed to reset SOS: ");
      Serial.println(http.errorToString(httpCode));
    }
    http.end();
  }
}

// Function to send Telegram Message from ESP32
void sendTelegramAlert(String lat, String lng) {
  if (WiFi.status() == WL_CONNECTED) {
    HTTPClient http;
    String url = "https://api.telegram.org/bot" + telegramBotToken + "/sendMessage";
    http.begin(url);
    http.addHeader("Content-Type", "application/json");

    String mapsLink = "https://www.google.com/maps/search/?api=1&query=" + lat + "," + lng;
    String message = "🚨 CONTINUOUS SOS ALERT! 🚨\nHelp is needed!\n📍 Location: " + mapsLink;
    
    // Create JSON payload
    String payload = "{\"chat_id\":\"" + telegramChatId + "\",\"text\":\"" + message + "\"}";
    
    int httpCode = http.POST(payload);
    
    if (httpCode > 0) {
      Serial.println("📱 Telegram alert sent!");
    } else {
      Serial.print("❌ Telegram failed: ");
      Serial.println(http.errorToString(httpCode));
    }
    http.end();
  }
}

void checkFirebaseForSOS() {
  if (WiFi.status() == WL_CONNECTED) {
    HTTPClient http;
    http.begin(firebaseUrl);
    int httpCode = http.GET();

    if (httpCode > 0) {
      String payload = http.getString();
      JsonDocument doc;
      DeserializationError error = deserializeJson(doc, payload);

      if (!error) {
        const char* status = doc["status"];
        if (status != NULL && String(status) == "SOS_DETECTED") {
          // Extract location
          double latitude = doc["latitude"];
          double longitude = doc["longitude"];
          currentLat = String(latitude, 6);
          currentLng = String(longitude, 6);
          
          if (!isSOSActive) {
            Serial.println("!!! 🚨 NEW SOS RECEIVED 🚨 !!!");
            isSOSActive = true;
            lastTelegramTime = 0; // Force immediate first Telegram message
          }
          digitalWrite(alertPin, HIGH); 
        } else {
          isSOSActive = false; // Status is NORMAL
        }
      }
    }
    http.end(); 
  }
}
