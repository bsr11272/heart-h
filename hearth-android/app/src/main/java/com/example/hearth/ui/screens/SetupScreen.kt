package com.example.hearth.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hearth.improv.ImprovDevice
import com.example.hearth.mqtt.ConnectionState
import com.example.hearth.mqtt.MqttMessage

@Composable
fun SetupScreen(
    host: String,
    port: String,
    connectionState: ConnectionState,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onConnectClicked: () -> Unit,
    onPublishTest: () -> Unit,
    setupOpen: Boolean,
    scanning: Boolean,
    discovered: List<ImprovDevice>,
    wifiSsid: String,
    wifiPassword: String,
    provisioningDevice: ImprovDevice?,
    provisionStatus: String,
    onOpenClicked: () -> Unit,
    onCloseClicked: () -> Unit,
    onScanClicked: () -> Unit,
    onStopScanClicked: () -> Unit,
    onSsidChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onProvision: (ImprovDevice) -> Unit,
    messages: List<MqttMessage>,
    modifier: Modifier = Modifier,
) {
    var showLog by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BrokerCard(
            host = host, port = port,
            onHostChange = onHostChange, onPortChange = onPortChange,
            connectionState = connectionState,
            onConnectClicked = onConnectClicked,
            onPublishTest = onPublishTest,
        )

        ImprovSetupCard(
            setupOpen = setupOpen, scanning = scanning, discovered = discovered,
            wifiSsid = wifiSsid, wifiPassword = wifiPassword,
            provisioningDevice = provisioningDevice, provisionStatus = provisionStatus,
            onOpenClicked = onOpenClicked, onCloseClicked = onCloseClicked,
            onScanClicked = onScanClicked, onStopScanClicked = onStopScanClicked,
            onSsidChange = onSsidChange, onPasswordChange = onPasswordChange,
            onProvision = onProvision,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Raw MQTT log (${messages.size})",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { showLog = !showLog }) {
                        Text(if (showLog) "hide" else "show")
                    }
                }
                if (showLog) {
                    RawMessageList(messages = messages,
                        modifier = Modifier.fillMaxWidth().height(280.dp).padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun BrokerCard(
    host: String,
    port: String,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    connectionState: ConnectionState,
    onConnectClicked: () -> Unit,
    onPublishTest: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Coral broker", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = host, onValueChange = onHostChange,
                    label = { Text("host") }, singleLine = true,
                    modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = port, onValueChange = onPortChange,
                    label = { Text("port") }, singleLine = true,
                    modifier = Modifier.width(110.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnectClicked, modifier = Modifier.weight(1f)) {
                    Text(connectionState.buttonLabel())
                }
                Button(
                    onClick = onPublishTest,
                    enabled = connectionState is ConnectionState.Connected,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("publish test")
                }
            }
            Text(connectionState.statusLine(), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ImprovSetupCard(
    setupOpen: Boolean,
    scanning: Boolean,
    discovered: List<ImprovDevice>,
    wifiSsid: String,
    wifiPassword: String,
    provisioningDevice: ImprovDevice?,
    provisionStatus: String,
    onOpenClicked: () -> Unit,
    onCloseClicked: () -> Unit,
    onScanClicked: () -> Unit,
    onStopScanClicked: () -> Unit,
    onSsidChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onProvision: (ImprovDevice) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Improv-WiFi BLE setup",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = if (setupOpen) onCloseClicked else onOpenClicked) {
                    Text(if (setupOpen) "close" else "open")
                }
            }
            if (!setupOpen) {
                Text(
                    "Provision new ESP32 nodes over BLE.",
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }

            OutlinedTextField(
                value = wifiSsid, onValueChange = onSsidChange,
                label = { Text("WiFi SSID (your hotspot)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = wifiPassword, onValueChange = onPasswordChange,
                label = { Text("WiFi password") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = if (scanning) onStopScanClicked else onScanClicked,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (scanning) "stop scan" else "scan")
            }

            if (discovered.isNotEmpty()) {
                Text("Discovered (${discovered.size}):",
                    style = MaterialTheme.typography.labelMedium)
                discovered.forEach { dev ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(dev.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${dev.address} · ${dev.rssi} dBm",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            Button(
                                onClick = { onProvision(dev) },
                                enabled = wifiSsid.isNotBlank() && provisioningDevice == null,
                            ) {
                                Text(if (provisioningDevice?.address == dev.address)
                                    "provisioning…" else "provision")
                            }
                        }
                    }
                }
            } else if (scanning) {
                Text("scanning…", style = MaterialTheme.typography.bodySmall)
            }
            if (provisionStatus.isNotBlank()) {
                Text(provisionStatus, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RawMessageList(messages: List<MqttMessage>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    LazyColumn(
        state = listState, modifier = modifier,
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(messages) { msg ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Column {
                    Text(
                        msg.topic, style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        msg.payload, style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

private fun ConnectionState.buttonLabel(): String = when (this) {
    is ConnectionState.Idle -> "connect"
    is ConnectionState.Connecting -> "connecting…"
    is ConnectionState.Connected -> "disconnect"
    is ConnectionState.Disconnected -> "reconnect"
}

private fun ConnectionState.statusLine(): String = when (this) {
    is ConnectionState.Idle -> "idle"
    is ConnectionState.Connecting -> "connecting to $host:$port…"
    is ConnectionState.Connected -> "connected to $host:$port"
    is ConnectionState.Disconnected -> "disconnected${reason?.let { " (${it})" } ?: ""}"
}
