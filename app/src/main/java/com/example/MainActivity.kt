package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.example.data.DeviceEntity
import com.example.data.LogEntity
import com.example.ui.BluetoothOrchestratorViewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.util.SoundSynthesizer
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private val viewModel: BluetoothOrchestratorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("main_scaffold")
                ) { innerPadding ->
                    BluetoothOrchestratorHost(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Ensure service binds when app returns to foreground
        viewModel.startAndBindService()
    }
}

@Composable
fun BluetoothOrchestratorHost(
    viewModel: BluetoothOrchestratorViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    // Permission state handling
    val requiredPermissions = remember {
        val list = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.VIBRATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
            list.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            list.add("android.permission.ANSWER_PHONE_CELLS")
        }
        list.toTypedArray()
    }

    var permissionGranted by remember {
        mutableStateOf(
            requiredPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionGranted = results.values.all { it }
        // Ensure service binds when permissions change
        viewModel.startAndBindService()
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted) {
            launcher.launch(requiredPermissions)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                )
            )
    ) {
        DashboardHeader(permissionGranted)

        if (!permissionGranted) {
            PermissionRequiredCard(
                permissions = requiredPermissions,
                onRequestPermissions = { launcher.launch(requiredPermissions) }
            )
        }

        // Main content is always scrollable for comfortable single-view ergonomics
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                StatusMonitorCard(viewModel)
            }

            item {
                TestingSimulatorPanel(viewModel)
            }

            item {
                PairedDevicesManagerPanel(viewModel)
            }

            item {
                BatteryOptimizationTipsBanner()
            }

            item {
                ConnectionLogsPanel(viewModel)
            }
        }
    }
}

@Composable
fun DashboardHeader(permissionsGranted: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Circle Logo matching design HTML
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(color = Color(0xFF6750A4), shape = CircleShape)
                    .shadow(1.dp, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                CustomBluetoothLogo(
                    color = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column {
                Text(
                    text = "LinkAnswer",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1C1B1F),
                    letterSpacing = (-0.5).sp
                )
                Text(
                    text = "Smart Bluetooth Linker",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF79747E)
                )
            }
        }

        // Active state badge styled as an elegant button/pill
        Surface(
            color = if (permissionsGranted) Color(0xFFEADDFF) else Color(0xFFF9DEDC),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, if (permissionsGranted) Color(0xFFD0BCFF) else Color(0xFFF9DEDC))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            color = if (permissionsGranted) Color(0xFF1F6B42) else Color(0xFFB3261E),
                            shape = CircleShape
                        )
                )
                Text(
                    text = if (permissionsGranted) "Service Ready" else "Attention",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (permissionsGranted) Color(0xFF21005D) else Color(0xFFB3261E)
                )
            }
        }
    }
}

@Composable
fun PermissionRequiredCard(
    permissions: Array<String>,
    onRequestPermissions: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Alert icon",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = "System Permissions Required",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Text(
                text = "LinkAnswer needs Phone and Bluetooth pairing permissions to monitor accessories, accept incoming hands-free calls, and play custom audio patterns in background state.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
            )
            Button(
                onClick = onRequestPermissions,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Grant Approvals")
            }
        }
    }
}

@Composable
fun StatusMonitorCard(viewModel: BluetoothOrchestratorViewModel) {
    val isConnected by viewModel.isTargetConnected.collectAsState()
    val isAutoAnswerActive by viewModel.isAutoAnswerActive.collectAsState()
    val connectedName by viewModel.connectedDeviceName.collectAsState()
    val batteryLevel by viewModel.connectedDeviceBattery.collectAsState()
    val currentTarget by viewModel.targetDevice.collectAsState()

    val cardBg = if (isConnected) Color(0xFFEADDFF) else Color(0xFFF7F2FA)
    val cardBorder = if (isConnected) Color(0xFFD0BCFF) else Color(0xFFE7E0EC)
    val labelColor = Color(0xFF4F378B)
    val titleColor = Color(0xFF21005D)
    val activeGreen = Color(0xFF1F6B42)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(1.dp, cardBorder),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Upper row: Device info and battery percent
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                // Info block
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isConnected) "Active Link" else "Selected Target",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        color = labelColor.copy(alpha = 0.8f),
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                    Text(
                        text = if (isConnected) (connectedName ?: "Target Device") else (currentTarget?.name ?: "No Target Selected"),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = titleColor
                    )
                    
                    // Connection status badge with solid active green dot
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    color = if (isConnected) activeGreen else Color(0xFF79747E),
                                    shape = CircleShape
                                )
                        )
                        Text(
                            text = if (isConnected) "Connected (BLE Secured)" else "Disconnected (Idle)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isConnected) activeGreen else Color(0xFF79747E)
                        )
                    }
                }

                // Battery / Status circle indicator
                Column(horizontalAlignment = Alignment.End) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.testTag("battery_level_indicator")
                    ) {
                        CustomBatteryIcon(
                            batteryLevel = batteryLevel,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (batteryLevel >= 0) "$batteryLevel%" else "0%",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = titleColor
                        )
                    }
                    Text(
                        text = if (batteryLevel >= 0 && batteryLevel <= 20) "LOW POWER MODE" else "OPTIMIZED MODE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = labelColor.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            // Dual Grid Sub-cards from the Design Sheet HTML!
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Left Pill - Auto Answer Engine
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isAutoAnswerActive) Color(0xFFF3EDF7) else Color.White
                    ),
                    border = BorderStroke(1.dp, Color(0xFFE7E0EC)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Auto Answer",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF49454F)
                        )
                        
                        // Fake switch indicator matching Tailwind w-12 h-6
                        Box(
                            modifier = Modifier
                                .size(width = 32.dp, height = 18.dp)
                                .background(
                                    color = if (isAutoAnswerActive) Color(0xFF6750A4) else Color(0xFFE7E0EC),
                                    shape = RoundedCornerShape(9.dp)
                                )
                                .padding(2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .align(if (isAutoAnswerActive) Alignment.CenterEnd else Alignment.CenterStart)
                                    .background(color = Color.White, shape = CircleShape)
                            )
                        }

                        Text(
                            text = if (isAutoAnswerActive) "ENABLED" else "DISABLED",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isAutoAnswerActive) Color(0xFF6750A4) else Color(0xFF79747E),
                            modifier = Modifier.testTag("auto_answer_status")
                        )
                    }
                }

                // Right Pill - Haptics status (Static deactivated matching HTML UI template)
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFE7E0EC)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Haptics",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF49454F)
                        )

                        // Fake disabled switch indicator
                        Box(
                            modifier = Modifier
                                .size(width = 32.dp, height = 18.dp)
                                .background(
                                    color = Color(0xFFE7E0EC),
                                    shape = RoundedCornerShape(9.dp)
                                )
                                .padding(2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .align(Alignment.CenterStart)
                                    .background(color = Color(0xFF79747E), shape = CircleShape)
                            )
                        }

                        Text(
                            text = "DISABLED",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF79747E)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun TestingSimulatorPanel(viewModel: BluetoothOrchestratorViewModel) {
    val currentTarget by viewModel.targetDevice.collectAsState()
    val isConnected by viewModel.isTargetConnected.collectAsState()

    var testCallerNumber by remember { mutableStateOf("+1 555-408-2026") }
    var testBatteryLevel by remember { mutableFloatStateOf(85f) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = "Simulator console icon",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Instructor Testing Controls (Simulation)",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = "Simulate events directly to test the background service state, haptic feedback, and auto-answering sequence on standard Android emulators.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Connection simulator toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Accessory Link Simulator",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isConnected) "Simulating connected state" else "Simulating disconnected state",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Button(
                    onClick = {
                        currentTarget?.let {
                            viewModel.toggleConnectionSimulation(it.macAddress)
                        } ?: viewModel.previewSound(SoundSynthesizer.SoundProfile.ALERT)
                    },
                    enabled = currentTarget != null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isConnected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.testTag("simulate_connection_toggle")
                ) {
                    Text(if (isConnected) "Disconnect" else "Connect Link")
                }
            }

            AnimatedVisibility(
                visible = isConnected,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Battery simulator slider
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                            .padding(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Simulate Battery Drain",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${testBatteryLevel.toInt()}%",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (testBatteryLevel <= 20) Color.Red else MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = testBatteryLevel,
                            onValueChange = { testBatteryLevel = it },
                            valueRange = 0f..100f,
                            onValueChangeFinished = {
                                currentTarget?.let {
                                    viewModel.simulateBatteryDrop(it.macAddress, testBatteryLevel.toInt())
                                }
                            },
                            modifier = Modifier.testTag("simulate_battery_slider")
                        )
                        Text(
                            text = "Set <= 20% to trigger low battery synthesized alarm, notification, and device logs.",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Simulated call input card
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Incoming Sound Call Hook",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextField(
                                value = testCallerNumber,
                                onValueChange = { testCallerNumber = it },
                                placeholder = { Text("Caller string") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("simulated_caller_input"),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                                )
                            )
                            Button(
                                onClick = { viewModel.simulateIncomingCall(testCallerNumber) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B824B)),
                                modifier = Modifier.testTag("simulate_incoming_call_button")
                            ) {
                                Text("Inbound Call")
                            }
                        }
                        Text(
                            text = "Triggers auto-answer logic immediately if link is connected.",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (currentTarget == null) {
                Text(
                    text = "* Configure at least one paired device as Active Target below to run simulated tests.",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun PairedDevicesManagerPanel(viewModel: BluetoothOrchestratorViewModel) {
    val devices by viewModel.allDevices.collectAsState()
    val currentTarget by viewModel.targetDevice.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Devices settings icon",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Accessory Devices Pool",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                TextButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.testTag("open_add_device_dialog_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add accessory icon",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Register Device", fontSize = 12.sp)
                }
            }

            Text(
                text = "LinkAnswer manages multiple devices. Set ONE specific device as the Designated Target. When connected, auto-answer enables, and custom sound profiles govern status alerts.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (devices.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No devices registered. Click Register to set up.",
                            color = MaterialTheme.colorScheme.outline,
                            fontSize = 12.sp
                        )
                    }
                } else {
                    devices.forEach { device ->
                        DeviceItemRow(
                            device = device,
                            isTarget = currentTarget?.macAddress == device.macAddress,
                            onSetTarget = { viewModel.setAsTargetDevice(device.macAddress) },
                            onDelete = { viewModel.removeDevice(device) },
                            onSoundChange = { sound -> viewModel.updateDeviceSound(device.macAddress, sound) },
                            onPreviewSound = { sound -> viewModel.previewSound(sound) }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddDeviceDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, mac, requireEncryption, sound ->
                viewModel.addPairedDevice(name, mac, requireEncryption, sound)
                showAddDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceItemRow(
    device: DeviceEntity,
    isTarget: Boolean,
    onSetTarget: () -> Unit,
    onDelete: () -> Unit,
    onSoundChange: (SoundSynthesizer.SoundProfile) -> Unit,
    onPreviewSound: (SoundSynthesizer.SoundProfile) -> Unit
) {
    var expandedSoundMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSetTarget() }
            .shadow(
                if (isTarget) 1.dp else 0.dp,
                RoundedCornerShape(12.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isTarget) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
            }
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                color = if (isTarget) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        CustomBluetoothLogo(
                            color = if (isTarget) Color.White else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = device.name,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (device.securePair) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Secure pairing icon",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                        Text(
                            text = "MAC: ${device.macAddress}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isTarget) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Text(
                                text = "TARGET",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("delete_device_${device.macAddress}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete device pool reference",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))

            // Embedded Customizable sound row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left sound picker selector button
                Box {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface,
                        onClick = { expandedSoundMenu = true },
                        modifier = Modifier
                            .testTag("sound_picker_${device.macAddress}")
                            .shadow(1.dp, RoundedCornerShape(8.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Notifications,
                                contentDescription = "Alarm profile chime",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(14.dp)
                            )
                            val currentSound = device.customSoundUri?.let { uri ->
                                try {
                                    SoundSynthesizer.SoundProfile.valueOf(uri).displayName
                                } catch (e: Exception) {
                                    SoundSynthesizer.SoundProfile.CHIME.displayName
                                }
                            } ?: SoundSynthesizer.SoundProfile.CHIME.displayName
                            
                            Text(
                                text = "Alert Tone: $currentSound",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = expandedSoundMenu,
                        onDismissRequest = { expandedSoundMenu = false }
                    ) {
                        SoundSynthesizer.SoundProfile.values().forEach { profile ->
                            DropdownMenuItem(
                                text = { Text(profile.displayName, fontSize = 12.sp) },
                                onClick = {
                                    onSoundChange(profile)
                                    expandedSoundMenu = false
                                }
                            )
                        }
                    }
                }

                // Right preview trigger button
                TextButton(
                    onClick = {
                        val currentProfile = try {
                            device.customSoundUri?.let { SoundSynthesizer.SoundProfile.valueOf(it) } ?: SoundSynthesizer.SoundProfile.CHIME
                        } catch (e: Exception) {
                            SoundSynthesizer.SoundProfile.CHIME
                        }
                        onPreviewSound(currentProfile)
                    },
                    modifier = Modifier
                        .height(30.dp)
                        .testTag("preview_sound_${device.macAddress}")
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Test Sound Chime play",
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text("Listen", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun BatteryOptimizationTipsBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
        ),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = Brush.horizontalGradient(
                listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)
                )
            )
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Battery security verification shield icon",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Ultra-Low Battery Optimization Enabled",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = "⚡ Efficient Process Design: LinkAnswer actively sleeps background receivers using ACL connection callbacks, bypassing scheduled battery draining intervals. programmatically synthesized sound generation (0mb storage footprint).\n⭐ BLE Support: Features Low Energy GATT polling and dynamic payload encryption protocols.",
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ConnectionLogsPanel(viewModel: BluetoothOrchestratorViewModel) {
    val logs by viewModel.recentLogs.collectAsState()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Logs index history panel launcher icon",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Accessory Connection History Logs",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                IconButton(
                    onClick = { viewModel.clearLogHistory() },
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("clear_logs_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Erase and refresh logs database records",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
            ) {
                if (logs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Log Database empty. Generate connection event tests to register logs.",
                            color = MaterialTheme.colorScheme.outline,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(logs, key = { it.id }) { log ->
                            LogItemRow(log = log)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LogItemRow(log: LogEntity) {
    val dateString = remember(log.timestamp) {
        val sdf = SimpleDateFormat("HH:mm.ss", Locale.getDefault())
        sdf.format(Date(log.timestamp))
    }

    val themeColor = when (log.eventType) {
        "CONNECTED" -> Color(0xFF1F6B42)
        "DISCONNECTED" -> Color(0xFFB3261E)
        "LOW_BATTERY" -> Color(0xFFFF9100)
        "CALL_ANSWERED" -> Color(0xFF6750A4)
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F2FA)),
        border = BorderStroke(1.dp, Color(0xFFE7E0EC)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    // Left vertical accent stripe indicator
                    val stripeWidth = 4.dp.toPx()
                    drawRect(
                        color = themeColor,
                        size = androidx.compose.ui.geometry.Size(stripeWidth, size.height)
                    )
                }
                .padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = log.eventType ?: "EVENT",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = themeColor,
                        letterSpacing = 1.1.sp
                    )
                    Text(
                        text = dateString,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF79747E)
                    )
                }
                
                Text(
                    text = log.deviceName,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1C1B1F),
                    modifier = Modifier.padding(top = 2.dp)
                )

                Text(
                    text = log.details,
                    fontSize = 11.sp,
                    color = Color(0xFF49454F),
                    modifier = Modifier.padding(top = 2.dp),
                    lineHeight = 14.sp
                )
            }
        }
    }
}

@Composable
fun AddDeviceDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, mac: String, requireEncryption: Boolean, SoundSynthesizer.SoundProfile) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var macAddress by remember { mutableStateOf("") }
    var requireEncryption by remember { mutableStateOf(false) }
    var selectedSound by remember { mutableStateOf(SoundSynthesizer.SoundProfile.CHIME) }
    var soundMenuExpanded by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Register Accessory Device",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Device Name") },
                    placeholder = { Text("e.g. Sony WH-1000XM4") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dialog_device_name")
                )

                TextField(
                    value = macAddress,
                    onValueChange = { macAddress = it },
                    label = { Text("MAC Address (Optional)") },
                    placeholder = { Text("e.g. 00:11:22:33:44:55") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dialog_device_mac")
                )

                // BLE secure pairing protocol toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enforce BLE Data Encryption", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("Enforce secure BLE connection and credential encryption key rotation.", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = requireEncryption,
                        onCheckedChange = { requireEncryption = it },
                        modifier = Modifier.testTag("dialog_encryption_toggle")
                    )
                }

                // Default alert sound chime configuration dropdown
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Alert Sound Customizer", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Box {
                        Button(
                            onClick = { soundMenuExpanded = true },
                            modifier = Modifier.testTag("dialog_sound_button")
                        ) {
                            Text(selectedSound.displayName, fontSize = 11.sp)
                        }
                        DropdownMenu(
                            expanded = soundMenuExpanded,
                            onDismissRequest = { soundMenuExpanded = false }
                        ) {
                            SoundSynthesizer.SoundProfile.values().forEach { sound ->
                                DropdownMenuItem(
                                    text = { Text(sound.displayName, fontSize = 11.sp) },
                                    onClick = {
                                        selectedSound = sound
                                        soundMenuExpanded = false
                                        SoundSynthesizer.playSound(sound)
                                    }
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onConfirm(name, macAddress, requireEncryption, selectedSound)
                        },
                        modifier = Modifier.testTag("dialog_submit_confirm")
                    ) {
                        Text("Confirm Pair")
                    }
                }
            }
        }
    }
}

@Composable
fun CustomBluetoothLogo(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val path = Path().apply {
            moveTo(w * 0.35f, h * 0.3f)
            lineTo(w * 0.65f, h * 0.7f)
            lineTo(w * 0.5f, h * 0.9f)
            lineTo(w * 0.5f, h * 0.1f)
            lineTo(w * 0.65f, h * 0.3f)
            lineTo(w * 0.35f, h * 0.7f)
        }

        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = w * 0.11f,
                cap = StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round
            )
        )
    }
}

@Composable
fun CustomBatteryIcon(batteryLevel: Int, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Outer battery shell
        val outlineColor = Color(0xFFA1A3A6)
        val fillLevel = if (batteryLevel >= 0) batteryLevel else 100
        val fillColor = when {
            batteryLevel < 0 -> Color(0xFFA1A3A6)
            batteryLevel <= 20 -> Color(0xFFC62828)
            else -> Color(0xFF1B824B)
        }

        val strokeWidth = w * 0.08f
        val batteryWidth = w * 0.75f
        val batteryHeight = h * 0.45f
        val topOffset = (h - batteryHeight) / 2f

        // Draw outer shell
        drawRoundRect(
            color = outlineColor,
            topLeft = Offset(strokeWidth / 2f, topOffset),
            size = androidx.compose.ui.geometry.Size(batteryWidth, batteryHeight),
            style = Stroke(width = strokeWidth),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f)
        )

        // Draw interior fill
        val inset = strokeWidth * 1.5f
        val fillWidth = (batteryWidth - inset * 2) * (fillLevel / 100f)
        if (fillWidth > 0 && batteryLevel >= 0) {
            drawRoundRect(
                color = fillColor,
                topLeft = Offset(inset, topOffset + inset),
                size = androidx.compose.ui.geometry.Size(fillWidth, batteryHeight - inset * 2),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.04f)
            )
        }

        // Draw battery positive terminal cap
        drawRoundRect(
            color = outlineColor,
            topLeft = Offset(batteryWidth, topOffset + batteryHeight * 0.25f),
            size = androidx.compose.ui.geometry.Size(w * 0.12f, batteryHeight * 0.5f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.02f)
        )
    }
}
