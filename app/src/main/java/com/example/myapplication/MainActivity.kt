package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import java.util.UUID
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState

data class GpsPoint(
    val latitude: Double,
    val longitude: Double
)

class MainActivity : ComponentActivity() {
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var isScanning by mutableStateOf(false)
    private val discoveredDevices = mutableStateListOf<BluetoothDevice>()
    private val pairedDevices = mutableStateListOf<BluetoothDevice>()
    private var bluetoothState by mutableStateOf("Unknown")
    private var hasPermissions by mutableStateOf(false)
    private var debugMessage by mutableStateOf("")

    // BLE connection
    private var bluetoothGatt: BluetoothGatt? = null
    private var connectionState by mutableStateOf("Disconnected")
    private var gpsData by mutableStateOf("No data")
    private var connectedDeviceName by mutableStateOf("")

    // GPS points storage
    private val gpsPoints = mutableStateListOf<GpsPoint>()

    // Handler for updating UI from background thread
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "BluetoothTest"

        // UUIDs from ESP32 code
        private val SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
        private val CHARACTERISTIC_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        Log.d(TAG, "Permission result received:")
        permissions.forEach { (permission, granted) ->
            Log.d(TAG, "  $permission: ${if (granted) "GRANTED" else "DENIED"}")
        }
        hasPermissions = permissions.values.all { it }
        Log.d(TAG, "All permissions granted: $hasPermissions")
        if (hasPermissions) {
            loadPairedDevices()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Received broadcast: ${intent?.action}")
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    device?.let {
                        Log.d(TAG, "Device found: ${it.name ?: "Unknown"} - ${it.address}")
                        if (!discoveredDevices.contains(it)) {
                            discoveredDevices.add(it)
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    Log.d(TAG, "Discovery started")
                    isScanning = true
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.d(TAG, "Discovery finished")
                    isScanning = false
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    updateBluetoothState()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Debug: Check if API key is configured
        try {
            val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            val apiKey = appInfo.metaData?.getString("com.google.android.geo.API_KEY")
            Log.d(TAG, "Maps API Key configured: ${apiKey?.take(10)}...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read API key from manifest", e)
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        updateBluetoothState()
        checkPermissions()

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }

        // Register receiver with proper flags for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        Log.d(TAG, "Broadcast receiver registered")

        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    BluetoothTestScreen(
                        bluetoothState = bluetoothState,
                        isScanning = isScanning,
                        hasPermissions = hasPermissions,
                        pairedDevices = pairedDevices,
                        discoveredDevices = discoveredDevices,
                        debugMessage = debugMessage,
                        connectionState = connectionState,
                        connectedDeviceName = connectedDeviceName,
                        gpsData = gpsData,
                        gpsPoints = gpsPoints,
                        onRequestPermissions = { requestPermissions() },
                        onStartScan = { startDiscovery() },
                        onStopScan = { stopDiscovery() },
                        onConnectDevice = { device -> connectToDevice(device) },
                        onDisconnect = { disconnectFromDevice() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
        stopDiscovery()
        disconnectFromDevice()
    }

    private fun checkPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        Log.d(TAG, "Checking permissions for Android SDK: ${Build.VERSION.SDK_INT}")
        permissions.forEach { permission ->
            val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Permission $permission: ${if (granted) "GRANTED" else "DENIED"}")
        }

        hasPermissions = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        Log.d(TAG, "Overall hasPermissions: $hasPermissions")

        if (hasPermissions) {
            loadPairedDevices()
        }
    }

    private fun requestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        permissionLauncher.launch(permissions)
    }

    @SuppressLint("MissingPermission")
    private fun loadPairedDevices() {
        if (!hasPermissions) return

        pairedDevices.clear()
        bluetoothAdapter?.bondedDevices?.let { devices ->
            pairedDevices.addAll(devices)
        }
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        Log.d(TAG, "startDiscovery called")
        debugMessage = "Start scan clicked\n"

        if (!hasPermissions) {
            Log.d(TAG, "Permissions not granted")
            debugMessage += "ERROR: Permissions not granted"
            Toast.makeText(this, "Permissions not granted", Toast.LENGTH_SHORT).show()
            requestPermissions()
            return
        }
        debugMessage += "Permissions: OK\n"

        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth adapter is null")
            debugMessage += "ERROR: Bluetooth not available"
            Toast.makeText(this, "Bluetooth not available", Toast.LENGTH_SHORT).show()
            return
        }
        debugMessage += "Adapter: OK\n"

        if (!bluetoothAdapter!!.isEnabled) {
            Log.d(TAG, "Bluetooth is not enabled")
            debugMessage += "ERROR: Bluetooth not enabled"
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }
        debugMessage += "Bluetooth: Enabled\n"

        // Check if location services are enabled (required for Bluetooth scanning)
        if (!isLocationEnabled()) {
            Log.d(TAG, "Location services are disabled")
            debugMessage += "ERROR: Location services disabled\n"
            debugMessage += "Bluetooth scanning requires Location to be ON"
            Toast.makeText(
                this,
                "Please enable Location Services in device settings",
                Toast.LENGTH_LONG
            ).show()

            // Optionally open location settings
            try {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open location settings", e)
            }
            return
        }
        debugMessage += "Location: Enabled\n"

        // Cancel any ongoing discovery
        if (bluetoothAdapter!!.isDiscovering) {
            Log.d(TAG, "Canceling previous discovery")
            debugMessage += "Canceling previous scan\n"
            bluetoothAdapter!!.cancelDiscovery()
        }

        discoveredDevices.clear()

        val started = bluetoothAdapter!!.startDiscovery()
        Log.d(TAG, "startDiscovery result: $started")
        debugMessage += "startDiscovery() returned: $started"

        if (!started) {
            Toast.makeText(this, "Failed to start discovery", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Scanning started...", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopDiscovery() {
        if (!hasPermissions) return
        bluetoothAdapter?.cancelDiscovery()
    }

    private fun updateBluetoothState() {
        bluetoothState = when (bluetoothAdapter?.state) {
            BluetoothAdapter.STATE_OFF -> "OFF"
            BluetoothAdapter.STATE_ON -> "ON"
            BluetoothAdapter.STATE_TURNING_OFF -> "Turning OFF"
            BluetoothAdapter.STATE_TURNING_ON -> "Turning ON"
            else -> "Unknown"
        }
        Log.d(TAG, "Bluetooth state updated: $bluetoothState")
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasPermissions) {
            Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d(TAG, "Connecting to device: ${device.name} - ${device.address}")
        connectionState = "Connecting..."
        connectedDeviceName = device.name ?: "Unknown"

        // Close existing connection if any
        bluetoothGatt?.close()

        // Connect to the GATT server on the device
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    @SuppressLint("MissingPermission")
    private fun disconnectFromDevice() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        connectionState = "Disconnected"
        gpsData = "No data"
        connectedDeviceName = ""
        gpsPoints.clear()
        Log.d(TAG, "Disconnected from device")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to GATT server")
                    mainHandler.post {
                        connectionState = "Connected"
                    }
                    // Discover services after connection
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT server")
                    mainHandler.post {
                        connectionState = "Disconnected"
                        gpsData = "No data"
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered")

                // Find the GPS service and characteristic
                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID)
                    if (characteristic != null) {
                        Log.d(TAG, "GPS Characteristic found, enabling notifications")
                        Log.d(TAG, "Characteristic properties: ${characteristic.properties}")

                        // Enable notifications
                        val notificationSet = gatt.setCharacteristicNotification(characteristic, true)
                        Log.d(TAG, "setCharacteristicNotification result: $notificationSet")

                        // Write to descriptor to enable notifications
                        val descriptor = characteristic.getDescriptor(CCCD_UUID)
                        if (descriptor != null) {
                            Log.d(TAG, "CCCD descriptor found, writing enable value")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val writeResult = gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                Log.d(TAG, "writeDescriptor (new API) result: $writeResult")
                            } else {
                                @Suppress("DEPRECATION")
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                @Suppress("DEPRECATION")
                                val writeResult = gatt.writeDescriptor(descriptor)
                                Log.d(TAG, "writeDescriptor (deprecated) result: $writeResult")
                            }
                            mainHandler.post {
                                connectionState = "Waiting for data..."
                            }
                        } else {
                            Log.e(TAG, "CCCD descriptor not found!")
                            mainHandler.post {
                                connectionState = "Error: CCCD not found"
                            }
                        }
                    } else {
                        Log.e(TAG, "GPS Characteristic not found")
                        mainHandler.post {
                            connectionState = "Error: Characteristic not found"
                        }
                    }
                } else {
                    Log.e(TAG, "GPS Service not found")
                    mainHandler.post {
                        connectionState = "Error: Service not found"
                    }
                }
            } else {
                Log.e(TAG, "Service discovery failed with status: $status")
                mainHandler.post {
                    connectionState = "Error: Service discovery failed"
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Descriptor write successful - notifications enabled!")
                    mainHandler.post {
                        connectionState = "Receiving data"
                    }
                } else {
                    Log.e(TAG, "Descriptor write failed with status: $status")
                    mainHandler.post {
                        connectionState = "Error: Failed to enable notifications"
                    }
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            // Handle the notification (for Android < 13)
            if (characteristic.uuid == CHARACTERISTIC_UUID) {
                @Suppress("DEPRECATION")
                val value = characteristic.value
                if (value != null) {
                    val data = String(value, Charsets.UTF_8)
                    val hexDump = value.joinToString(" ") { byte -> "%02X".format(byte) }
                    Log.d(TAG, "Received GPS data (deprecated): $data")
                    Log.d(TAG, "Raw bytes (${value.size}): $hexDump")
                    Log.d(TAG, "Data length: ${data.length} chars")
                    mainHandler.post {
                        gpsData = data
                        parseAndStoreGpsData(data)
                    }
                } else {
                    Log.e(TAG, "Received null value")
                    mainHandler.post {
                        gpsData = "Error: null value"
                    }
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            // Handle the notification (for Android >= 13)
            if (characteristic.uuid == CHARACTERISTIC_UUID) {
                val data = String(value, Charsets.UTF_8)
                val hexDump = value.joinToString(" ") { byte -> "%02X".format(byte) }
                Log.d(TAG, "Received GPS data (new API): $data")
                Log.d(TAG, "Raw bytes (${value.size}): $hexDump")
                Log.d(TAG, "Data length: ${data.length} chars")
                mainHandler.post {
                    gpsData = data
                    parseAndStoreGpsData(data)
                }
            }
        }
    }

    private fun parseAndStoreGpsData(data: String) {
        try {
            val parts = data.trim().split(",")
            if (parts.size >= 2) {
                val latitude = parts[0].toDoubleOrNull()
                val longitude = parts[1].toDoubleOrNull()

                if (latitude != null && longitude != null) {
                    val point = GpsPoint(latitude, longitude)
                    gpsPoints.add(point)
                    Log.d(TAG, "Stored GPS point: $point (total: ${gpsPoints.size})")
                } else {
                    Log.w(TAG, "Failed to parse GPS values: lat=$latitude, lng=$longitude")
                }
            } else {
                Log.w(TAG, "GPS data has insufficient parts: ${parts.size}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing GPS data: $data", e)
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun BluetoothTestScreen(
    bluetoothState: String,
    isScanning: Boolean,
    hasPermissions: Boolean,
    pairedDevices: List<BluetoothDevice>,
    discoveredDevices: List<BluetoothDevice>,
    debugMessage: String,
    connectionState: String,
    connectedDeviceName: String,
    gpsData: String,
    gpsPoints: List<GpsPoint>,
    onRequestPermissions: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnectDevice: (BluetoothDevice) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Bluetooth Test App",
                style = MaterialTheme.typography.headlineMedium
            )
        }

        if (debugMessage.isNotEmpty()) {
            item {
                Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFF3CD)
                )
            ) {
                Text(
                    text = "Debug Info:\n$debugMessage",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Black
                )
            }
            }
        }

        item {
            Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when (bluetoothState) {
                    "ON" -> Color(0xFF4CAF50)
                    "OFF" -> Color(0xFFF44336)
                    else -> Color.Gray
                }
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Bluetooth State: $bluetoothState",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Text(
                    text = "Permissions: ${if (hasPermissions) "Granted" else "Not granted"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
            }
            }
        }

        if (!hasPermissions) {
            item {
                Button(
                    onClick = onRequestPermissions,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Grant Bluetooth Permissions")
                }
            }
        } else {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onStartScan,
                        enabled = !isScanning && bluetoothState == "ON",
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Start Scan")
                    }
                    Button(
                        onClick = onStopScan,
                        enabled = isScanning,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Stop Scan")
                    }
                }
            }

            if (isScanning) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scanning for devices...")
                    }
                }
            }
        }

        // Connection Status Card
        if (connectionState != "Disconnected") {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            connectionState.contains("Error") -> Color(0xFFF44336)
                            connectionState == "Connected" || connectionState == "Receiving data" -> Color(0xFF4CAF50)
                            else -> Color(0xFFFF9800)
                        }
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Device: $connectedDeviceName",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White
                                )
                                Text(
                                    text = "Status: $connectionState",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White
                                )
                                if (gpsData != "No data") {
                                    Spacer(modifier = Modifier.height(8.dp))

                                    // Show raw data first for debugging
                                    Text(
                                        text = "Raw Data: '$gpsData'",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.8f)
                                    )

                                    Text(
                                        text = "Length: ${gpsData.length} chars",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.8f)
                                    )

                                    Spacer(modifier = Modifier.height(4.dp))

                                    // Parse and format GPS data
                                    val parts = gpsData.split(",")
                                    Text(
                                        text = "Parts: ${parts.size}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.8f)
                                    )

                                    if (parts.size >= 2) {
                                        Text(
                                            text = "Lat: ${parts[0]}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Color.White
                                        )
                                        Text(
                                            text = "Lng: ${parts[1]}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Color.White
                                        )
                                    } else {
                                        Text(
                                            text = "Cannot parse: $gpsData",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                            Button(
                                onClick = onDisconnect,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White.copy(alpha = 0.2f)
                                )
                            ) {
                                Text("Disconnect", color = Color.White)
                            }
                        }
                    }
                }
            }
        }

        // GPS Points Debug Info
        if (connectionState != "Disconnected") {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFE3F2FD)
                    )
                ) {
                    Text(
                        text = "GPS Points Collected: ${gpsPoints.size}",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }

        // GPS Map View
        if (gpsPoints.isNotEmpty()) {
            item {
                GpsMapView(
                    gpsPoints = gpsPoints,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Paired Devices (${pairedDevices.size})",
                style = MaterialTheme.typography.titleMedium
            )
        }

        if (pairedDevices.isEmpty()) {
            item {
                Text(
                    text = "No paired devices",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
            }
        } else {
            items(pairedDevices) { device ->
                DeviceCard(
                    deviceName = device.name ?: "Unknown",
                    deviceAddress = device.address,
                    isPaired = true,
                    onConnect = { onConnectDevice(device) }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            Divider()
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Discovered Devices (${discoveredDevices.size})",
                style = MaterialTheme.typography.titleMedium
            )
        }

        if (discoveredDevices.isEmpty() && !isScanning) {
            item {
                Text(
                    text = "No devices discovered. Start scanning to find devices.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
            }
        } else {
            items(discoveredDevices) { device ->
                DeviceCard(
                    deviceName = device.name ?: "Unknown",
                    deviceAddress = device.address,
                    isPaired = false,
                    onConnect = { onConnectDevice(device) }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun GpsMapView(
    gpsPoints: List<GpsPoint>,
    modifier: Modifier = Modifier
) {
    Log.d("GpsMapView", "GpsMapView called with ${gpsPoints.size} points")

    if (gpsPoints.isEmpty()) {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No GPS data yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.Gray
                )
            }
        }
    } else {
        // Calculate the center position based on all points
        val centerLat = gpsPoints.map { it.latitude }.average()
        val centerLng = gpsPoints.map { it.longitude }.average()

        Log.d("GpsMapView", "Map center: lat=$centerLat, lng=$centerLng")
        Log.d("GpsMapView", "First point: ${gpsPoints.first()}")
        Log.d("GpsMapView", "Last point: ${gpsPoints.last()}")

        val cameraPositionState = rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(LatLng(centerLat, centerLng), 15f)
        }

        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column {
                Text(
                    text = "GPS Points on Map (${gpsPoints.size} points)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                ) {
                    GoogleMap(
                        modifier = Modifier.fillMaxSize(),
                        cameraPositionState = cameraPositionState
                    ) {
                        gpsPoints.forEachIndexed { index, point ->
                            Marker(
                                state = MarkerState(position = LatLng(point.latitude, point.longitude)),
                                title = "Point ${index + 1}",
                                snippet = "Lat: ${String.format("%.6f", point.latitude)}, Lng: ${String.format("%.6f", point.longitude)}"
                            )
                        }
                    }
                }

                // Debug info
                Text(
                    text = "Center: ${String.format("%.6f", centerLat)}, ${String.format("%.6f", centerLng)}",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
fun DeviceCard(
    deviceName: String,
    deviceAddress: String,
    isPaired: Boolean,
    onConnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isPaired) Color(0xFFE3F2FD) else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = deviceName,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = deviceAddress,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isPaired) {
                        Surface(
                            color = Color(0xFF2196F3),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = "Paired",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                    }
                    Button(
                        onClick = onConnect,
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text("Connect", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}