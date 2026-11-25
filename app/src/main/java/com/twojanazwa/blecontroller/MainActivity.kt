package com.twojanazwa.blecontroller

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ControllerApp()
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun ControllerApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val bluetoothManager = remember { context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    val bluetoothAdapter = remember { bluetoothManager.adapter }

    var hasPermissions by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }
    val scannedDevices = remember { mutableStateListOf<BluetoothDevice>() }
    var connectedGatt by remember { mutableStateOf<BluetoothGatt?>(null) }
    var txCharacteristic by remember { mutableStateOf<BluetoothGattCharacteristic?>(null) }
    var connectionStatus by remember { mutableStateOf("Rozłączono") }
    var batteryLevel by remember { mutableStateOf("Nieznany") }

    var sliderPercent by remember { mutableFloatStateOf(0f) }
    var inputPercent by remember { mutableStateOf("50") }
    var inputSeconds by remember { mutableStateOf("30") }
    var remainingSeconds by remember { mutableIntStateOf(0) }
    var timerJob by remember { mutableStateOf<Job?>(null) }

    val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        hasPermissions = perms.values.all { it }
    }

    LaunchedEffect(Unit) {
        hasPermissions = permissionsToRequest.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasPermissions) {
            permissionLauncher.launch(permissionsToRequest)
        }
    }

    fun sendCommand(cmd: String) {
        val gatt = connectedGatt ?: return
        val char = txCharacteristic ?: return
        char.value = cmd.toByteArray(Charsets.UTF_8)
        gatt.writeCharacteristic(char)
    }

    fun setPowerPercent(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        val deviceLevel = (clamped * 0.2f).roundToInt()
        sendCommand("Vibrate:$deviceLevel;")
    }

    val scanCallback = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = device.name ?: return
                if (name.startsWith("LVS-") && scannedDevices.none { it.address == device.address }) {
                    scannedDevices.add(device)
                }
            }
        }
    }

    val gattCallback = remember {
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectionStatus = "Połączono z ${gatt.device.name}"
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connectionStatus = "Rozłączono"
                    connectedGatt = null
                    txCharacteristic = null
                    timerJob?.cancel()
                    remainingSeconds = 0
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                for (service in gatt.services) {
                    for (char in service.characteristics) {
                        val props = char.properties
                        if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                            (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                        ) {
                            txCharacteristic = char
                        }
                        if ((props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                            gatt.setCharacteristicNotification(char, true)
                            val descriptor = char.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                            descriptor?.let {
                                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(it)
                            }
                        }
                    }
                }
                scope.launch {
                    delay(500)
                    sendCommand("Battery;")
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val response = characteristic.value.toString(Charsets.UTF_8).trim()
                if (response.endsWith(";")) {
                    val value = response.removeSuffix(";")
                    if (value.toIntOrNull() != null) {
                        batteryLevel = "$value%"
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Wheel Controll", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Status: $connectionStatus", fontWeight = FontWeight.SemiBold)
                if (connectedGatt != null) {
                    Text("Bateria: $batteryLevel", color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            setPowerPercent(0)
                            connectedGatt?.disconnect()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Rozłącz urządzenie")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (connectedGatt == null) {
            Button(
                onClick = {
                    if (!hasPermissions) {
                        permissionLauncher.launch(permissionsToRequest)
                        return@Button
                    }
                    if (!isScanning) {
                        scannedDevices.clear()
                        bluetoothAdapter?.bluetoothLeScanner?.startScan(scanCallback)
                        isScanning = true
                    } else {
                        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
                        isScanning = false
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isScanning) "Zatrzymaj skanowanie..." else "Szukaj urządzeń w pobliżu")
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn {
                items(scannedDevices) { device ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
                                isScanning = false
                                connectionStatus = "Łączenie..."
                                connectedGatt = device.connectGatt(context, false, gattCallback)
                            }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(device.name ?: "Nieznane urządzenie", fontWeight = FontWeight.Bold)
                            Text(device.address, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } else {
            Text("Sterowanie płynne (Suwak)", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = sliderPercent,
                onValueChange = {
                    timerJob?.cancel()
                    remainingSeconds = 0
                    sliderPercent = it
                    setPowerPercent(it.roundToInt())
                },
                valueRange = 0f..100f
            )
            Text("Aktualna moc: ${sliderPercent.roundToInt()}%")

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text("Tryb precyzyjny (Moc % + Czas)", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = inputPercent,
                    onValueChange = { inputPercent = it.filter { char -> char.isDigit() } },
                    label = { Text("Moc (%)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = inputSeconds,
                    onValueChange = { inputSeconds = it.filter { char -> char.isDigit() } },
                    label = { Text("Czas (sekundy)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val targetPercent = (inputPercent.toIntOrNull() ?: 0).coerceIn(0, 100)
                        val duration = (inputSeconds.toIntOrNull() ?: 0).coerceAtLeast(1)

                        timerJob?.cancel()
                        sliderPercent = targetPercent.toFloat()
                        setPowerPercent(targetPercent)

                        timerJob = scope.launch {
                            for (sec in duration downTo 1) {
                                remainingSeconds = sec
                                delay(1000L)
                            }
                            remainingSeconds = 0
                            sliderPercent = 0f
                            setPowerPercent(0)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (remainingSeconds > 0) "Pozostało: ${remainingSeconds}s" else "Uruchom cykl")
                }

                OutlinedButton(
                    onClick = {
                        timerJob?.cancel()
                        remainingSeconds = 0
                        sliderPercent = 0f
                        setPowerPercent(0)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("STOP (0%)")
                }
            }
        }
    }
}
