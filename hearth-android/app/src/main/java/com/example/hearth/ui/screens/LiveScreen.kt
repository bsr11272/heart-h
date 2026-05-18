package com.example.hearth.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.hearth.state.SensorState
import com.example.hearth.ui.CameraLiveFeedPanel
import com.example.hearth.ui.RadarMapPanel

@Composable
fun LiveScreen(
    state: SensorState,
    brokerHost: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CameraLiveFeedPanel(state = state, brokerHost = brokerHost)
        HorizontalDivider()
        RadarMapPanel(state = state)
    }
}
