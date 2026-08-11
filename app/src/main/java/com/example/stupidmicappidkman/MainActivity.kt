package com.example.stupidmicappidkman

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object StreamState {
    val amplitude = MutableStateFlow(0f)
    val isStreaming = MutableStateFlow(false)
    val logs = MutableStateFlow<List<String>>(emptyList())

    fun addLog(message: String) {
        val currentLogs = logs.value.toMutableList()
        currentLogs.add(0, message)
        if (currentLogs.size > 50) currentLogs.removeAt(50)
        logs.value = currentLogs
    }
}

data class AudioInputDevice(val id: Int, val name: String, val type: Int)

class MainViewModel : ViewModel() {
    var ipAddress by mutableStateOf("192.168.1.100")
    var port by mutableStateOf("7000")
    var phoneId by mutableStateOf(1)
    var selectedAudioSource by mutableStateOf(MediaRecorder.AudioSource.MIC)
    var availableDevices by mutableStateOf<List<AudioInputDevice>>(emptyList())
    var selectedDeviceId by mutableStateOf<Int?>(null)
    var showDiagnostics by mutableStateOf(false)
    
    val amplitude = StreamState.amplitude.asStateFlow()
    val isStreaming = StreamState.isStreaming.asStateFlow()
    val logs = StreamState.logs.asStateFlow()

    fun refreshDevices(context: android.content.Context) {
        val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        availableDevices = devices.map { device ->
            val name = "${device.productName} (${getDeviceTypeName(device.type)})"
            AudioInputDevice(device.id, name, device.type)
        }
    }

    private fun getDeviceTypeName(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Mic"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Device"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
            else -> "Other ($type)"
        }
    }

    fun toggleStreaming(context: android.content.Context) {
        val intent = Intent(context, AudioStreamService::class.java)
        if (isStreaming.value) {
            intent.action = AudioStreamService.ACTION_STOP
            context.startService(intent)
            StreamState.isStreaming.value = false
        } else {
            intent.action = AudioStreamService.ACTION_START
            intent.putExtra(AudioStreamService.EXTRA_IP, ipAddress)
            intent.putExtra(AudioStreamService.EXTRA_PORT, port.toIntOrNull() ?: 7000)
            intent.putExtra(AudioStreamService.EXTRA_PHONE_ID, phoneId.toByte())
            intent.putExtra(AudioStreamService.EXTRA_AUDIO_SOURCE, selectedAudioSource)
            selectedDeviceId?.let {
                intent.putExtra(AudioStreamService.EXTRA_DEVICE_ID, it)
            }
            ContextCompat.startForegroundService(context, intent)
            StreamState.isStreaming.value = true
        }
    }
}

class MainActivity : ComponentActivity() {
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            android.util.Log.d("MainActivity", "Permissions granted")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        checkPermissions()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(missing.toTypedArray())
        }
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isStreaming by viewModel.isStreaming.collectAsState()
    val amplitude by viewModel.amplitude.collectAsState()
    val logs by viewModel.logs.collectAsState()

    if (viewModel.showDiagnostics) {
        DiagnosticDialog(
            logs = logs,
            onDismiss = { viewModel.showDiagnostics = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("MeowMic", style = MaterialTheme.typography.headlineMedium)
            IconButton(onClick = { viewModel.showDiagnostics = true }) {
                Icon(Icons.Default.Info, contentDescription = "Diagnostics")
            }
        }
        
        OutlinedTextField(
            value = viewModel.ipAddress,
            onValueChange = { viewModel.ipAddress = it },
            label = { Text("Receiver IP Address") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isStreaming,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        )

        OutlinedTextField(
            value = viewModel.port,
            onValueChange = { viewModel.port = it },
            label = { Text("UDP Port") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isStreaming,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        LaunchedEffect(Unit) {
            viewModel.refreshDevices(context)
        }

        DeviceSelector(
            selectedDeviceId = viewModel.selectedDeviceId,
            devices = viewModel.availableDevices,
            onDeviceSelected = { viewModel.selectedDeviceId = it },
            enabled = !isStreaming
        )

        AudioSourceSelector(
            selectedSource = viewModel.selectedAudioSource,
            onSourceSelected = { viewModel.selectedAudioSource = it },
            enabled = !isStreaming
        )

        Text("Phone ID", style = MaterialTheme.typography.titleMedium)
        PhoneIdSelector(
            selectedId = viewModel.phoneId,
            onIdSelected = { viewModel.phoneId = it },
            enabled = !isStreaming
        )

        Spacer(modifier = Modifier.height(16.dp))

        ConnectionStatus(isStreaming)

        AudioLevelMeter(amplitude)

        Button(
            onClick = { viewModel.toggleStreaming(context) },
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isStreaming) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(if (isStreaming) "STOP STREAMING" else "START STREAMING")
        }
    }
}

@Composable
fun DiagnosticDialog(logs: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("UDP Diagnostics") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .background(Color.Black.copy(alpha = 0.2f))
                    .padding(8.dp)
            ) {
                items(logs) { log ->
                    Text(
                        text = log,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun DeviceSelector(
    selectedDeviceId: Int?,
    devices: List<AudioInputDevice>,
    onDeviceSelected: (Int?) -> Unit,
    enabled: Boolean
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedDevice = devices.find { it.id == selectedDeviceId }

    Column {
        Text("Input Device (Hardware)", style = MaterialTheme.typography.titleMedium)
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(selectedDevice?.name ?: "System Default")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth()
            ) {
                DropdownMenuItem(
                    text = { Text("System Default") },
                    onClick = {
                        onDeviceSelected(null)
                        expanded = false
                    }
                )
                devices.forEach { device ->
                    DropdownMenuItem(
                        text = { Text(device.name) },
                        onClick = {
                            onDeviceSelected(device.id)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun AudioSourceSelector(selectedSource: Int, onSourceSelected: (Int) -> Unit, enabled: Boolean) {
    val sources = listOf(
        "Mic" to MediaRecorder.AudioSource.MIC,
        "Camcorder" to MediaRecorder.AudioSource.CAMCORDER,
        "Voice Recognition" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
        "Voice Communication" to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        "Unprocessed" to MediaRecorder.AudioSource.UNPROCESSED
    )
    var expanded by remember { mutableStateOf(false) }

    Column {
        Text("Audio Source", style = MaterialTheme.typography.titleMedium)
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(sources.firstOrNull { it.second == selectedSource }?.first ?: "Select Source")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth()
            ) {
                sources.forEach { (name, source) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            onSourceSelected(source)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun PhoneIdSelector(selectedId: Int, onIdSelected: (Int) -> Unit, enabled: Boolean) {
    val options = listOf(1, 2, 3, 4, 5, 6, 7, 8)
    Row(
        Modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        options.forEach { id ->
            Column(
                Modifier
                    .selectable(
                        selected = (id == selectedId),
                        onClick = { if (enabled) onIdSelected(id) },
                        role = Role.RadioButton
                    )
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                RadioButton(
                    selected = (id == selectedId),
                    onClick = null, // handled by selectable
                    enabled = enabled
                )
                Text(
                    text = id.toString(),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
fun ConnectionStatus(isStreaming: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(
                    if (isStreaming) Color.Green else Color.Red,
                    shape = MaterialTheme.shapes.small
                )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(if (isStreaming) "Streaming" else "Disconnected")
    }
}

@Composable
fun AudioLevelMeter(amplitude: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Mic Activity", style = MaterialTheme.typography.labelMedium)
        LinearProgressIndicator(
            progress = amplitude,
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp),
            color = if (amplitude > 0.8f) Color.Red else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}
