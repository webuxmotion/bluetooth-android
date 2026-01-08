#include <Arduino.h>
#include <NimBLEDevice.h>

// BLE UUIDs - must match the Android app
#define SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHARACTERISTIC_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"

NimBLEServer* pServer = NULL;
NimBLECharacteristic* pCharacteristic = NULL;
bool deviceConnected = false;
bool clientSubscribed = false;

// Simulated GPS coordinates (starting point - San Francisco)
float currentLat = 37.7749;
float currentLng = -122.4194;
float currentAlt = 100.5;

// Movement parameters for simulating GPS tracking
float latSpeed = 0.0001;  // Speed of latitude change
float lngSpeed = 0.0001;  // Speed of longitude change
float altSpeed = 0.5;     // Speed of altitude change

class MyServerCallbacks: public NimBLEServerCallbacks {
    void onConnect(NimBLEServer* pServer) {
      deviceConnected = true;
      Serial.println("Device connected");
    };

    void onDisconnect(NimBLEServer* pServer) {
      deviceConnected = false;
      clientSubscribed = false;
      Serial.println("Device disconnected");
      // Restart advertising so the device can be discovered again
      NimBLEDevice::startAdvertising();
      Serial.println("Advertising restarted");
    }
};

class MyCharacteristicCallbacks: public NimBLECharacteristicCallbacks {
    void onWrite(NimBLECharacteristic* pCharacteristic) {
      std::string value = pCharacteristic->getValue();
      if (value.length() > 0) {
        Serial.print("Received from Android: ");
        Serial.println(value.c_str());
      }
    }

    void onSubscribe(NimBLECharacteristic* pCharacteristic, ble_gap_conn_desc* desc, uint16_t subValue) {
      String str = "Client ID: ";
      str += desc->conn_handle;
      str += " Address: ";
      str += std::string(NimBLEAddress(desc->peer_ota_addr)).c_str();
      if (subValue == 0) {
        str += " Unsubscribed from notifications/indications";
        clientSubscribed = false;
      } else if (subValue == 1) {
        str += " Subscribed to notifications";
        clientSubscribed = true;
      } else if (subValue == 2) {
        str += " Subscribed to indications";
        clientSubscribed = true;
      } else if (subValue == 3) {
        str += " Subscribed to notifications and indications";
        clientSubscribed = true;
      }
      Serial.println(str);
    }
};

void setup() {
  Serial.begin(115200);
  delay(2000);
  Serial.println("ESP32-C3 GPS Simulator Starting...");

  // Create the BLE Device
  NimBLEDevice::init("ESP32-GPS");

  // Create the BLE Server
  pServer = NimBLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());

  // Create the BLE Service
  NimBLEService *pService = pServer->createService(SERVICE_UUID);

  // Create a BLE Characteristic with notify capability
  // Using only NOTIFY, not INDICATE
  pCharacteristic = pService->createCharacteristic(
                      CHARACTERISTIC_UUID,
                      NIMBLE_PROPERTY::READ   |
                      NIMBLE_PROPERTY::WRITE  |
                      NIMBLE_PROPERTY::NOTIFY
                    );

  // Set characteristic callbacks
  pCharacteristic->setCallbacks(new MyCharacteristicCallbacks());

  // Start the service
  pService->start();

  // Start advertising
  NimBLEAdvertising *pAdvertising = NimBLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->setScanResponse(false);
  pAdvertising->setMinPreferred(0x0);
  NimBLEDevice::startAdvertising();

  Serial.println("BLE GPS Simulator is ready!");
  Serial.println("Waiting for a client connection...");
}

void updateSimulatedGPS() {
  // Simulate GPS movement in a small pattern
  // This creates a more realistic "moving" GPS signal

  currentLat += latSpeed;
  currentLng += lngSpeed;
  currentAlt += altSpeed;

  // Reverse direction when reaching boundaries (creates a back-and-forth pattern)
  if (currentLat > 37.7849 || currentLat < 37.7649) {
    latSpeed = -latSpeed;
  }

  if (currentLng > -122.4094 || currentLng < -122.4294) {
    lngSpeed = -lngSpeed;
  }

  if (currentAlt > 150.0 || currentAlt < 50.0) {
    altSpeed = -altSpeed;
  }
}

void loop() {
  if (deviceConnected && clientSubscribed) {
    // Update simulated GPS coordinates
    updateSimulatedGPS();

    // Format using char array instead of String to ensure proper formatting
    char gpsBuffer[64];
    snprintf(gpsBuffer, sizeof(gpsBuffer), "%.6f,%.6f,%.2f", currentLat, currentLng, currentAlt);

    // Send via BLE
    pCharacteristic->setValue((uint8_t*)gpsBuffer, strlen(gpsBuffer));
    pCharacteristic->notify();

    // Log to Serial Monitor
    Serial.print("Sent GPS (");
    Serial.print(strlen(gpsBuffer));
    Serial.print(" bytes): ");
    Serial.println(gpsBuffer);

    // Send update every 1 second
    delay(1000);
  } else {
    // When not connected or not subscribed, just wait
    if (deviceConnected && !clientSubscribed) {
      Serial.println("Device connected but not subscribed to notifications");
    }
    delay(500);
  }
}
