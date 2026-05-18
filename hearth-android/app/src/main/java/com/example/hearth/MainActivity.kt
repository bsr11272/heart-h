package com.example.hearth

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.hearth.improv.ImprovBleClient
import com.example.hearth.notifications.FallNotifier
import com.example.hearth.state.RoomLabels
import com.example.hearth.ui.FallBanner
import com.example.hearth.ui.nav.HearthBottomNav
import com.example.hearth.ui.nav.HearthTab
import com.example.hearth.ui.screens.AskScreen
import com.example.hearth.ui.screens.HealthScreen
import com.example.hearth.ui.screens.HomeScreen
import com.example.hearth.ui.screens.LiveScreen
import com.example.hearth.ui.screens.MemoryScreen
import com.example.hearth.ui.screens.PendantScreen
import com.example.hearth.ui.screens.SetupScreen
import com.example.hearth.ui.theme.HearthTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        RoomLabels.init(this)
        FallNotifier.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                9001,
            )
        }
        setContent {
            HearthTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HearthApp()
                }
            }
        }
    }
}

@Composable
fun HearthApp(vm: HearthViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    var selectedTab by remember { mutableStateOf(HearthTab.HOME) }

    val blePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) vm.startScan()
    }

    Column(Modifier.fillMaxSize()) {
        FallBanner(
            state = ui.sensorState,
            dismissed = ui.fallDismissed,
            onDismiss = vm::dismissFall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            onCheckIn = {
                val prompt = "I just heard an unusual sound near Margaret. " +
                    "Vision and pendant did not confirm a fall. Compose a " +
                    "warm, brief check-in message I can voice to her over " +
                    "the smart speaker — one or two short sentences."
                vm.setChatInput(prompt)
                selectedTab = HearthTab.ASK
                vm.sendChat()
            },
        )
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                HearthBottomNav(
                    selected = selectedTab,
                    onSelect = { selectedTab = it },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                when (selectedTab) {
                    HearthTab.HOME -> HomeScreen(
                        state = ui.sensorState,
                        onQuickAsk = { prompt ->
                            vm.setChatInput(prompt)
                            selectedTab = HearthTab.ASK
                        },
                        onRoomLabelChange = { id, label -> RoomLabels.set(id, label) },
                    )
                    HearthTab.LIVE -> LiveScreen(state = ui.sensorState, brokerHost = ui.host)
                    HearthTab.PENDANT -> PendantScreen(state = ui.sensorState)
                    HearthTab.HEALTH -> HealthScreen(state = ui.sensorState)
                    HearthTab.ASK -> AskScreen(
                        chatHistory = ui.chatHistory,
                        chatInput = ui.chatInput,
                        onChatInputChange = vm::setChatInput,
                        onSend = vm::sendChat,
                        onClearChat = vm::clearChat,
                        inferenceInFlight = ui.inferenceInFlight,
                        engine = ui.engine,
                        onLoadModel = vm::onLoadModelClicked,
                    )
                    HearthTab.MEMORY -> MemoryScreen(
                        state = ui.sensorState,
                        memoryClient = vm.memoryClient,
                    )
                    HearthTab.SETUP -> SetupScreen(
                        host = ui.host,
                        port = ui.port,
                        connectionState = ui.connection,
                        onHostChange = vm::setHost,
                        onPortChange = vm::setPort,
                        onConnectClicked = vm::onConnectClicked,
                        onPublishTest = vm::publishTest,
                        setupOpen = ui.setupOpen,
                        scanning = ui.scanning,
                        discovered = ui.discovered,
                        wifiSsid = ui.wifiSsid,
                        wifiPassword = ui.wifiPassword,
                        provisioningDevice = ui.provisioningDevice,
                        provisionStatus = ui.provisionStatus,
                        onOpenClicked = vm::openSetup,
                        onCloseClicked = vm::closeSetup,
                        onScanClicked = {
                            blePermLauncher.launch(ImprovBleClient.REQUIRED_PERMISSIONS)
                        },
                        onStopScanClicked = vm::stopScan,
                        onSsidChange = vm::setWifiSsid,
                        onPasswordChange = vm::setWifiPassword,
                        onProvision = vm::provision,
                        messages = ui.messages,
                    )
                }
            }
        }
    }
}
