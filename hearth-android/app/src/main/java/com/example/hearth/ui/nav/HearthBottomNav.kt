package com.example.hearth.ui.nav

import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

enum class HearthTab(val label: String, val emoji: String) {
    HOME("Home", "🏠"),
    LIVE("Live", "📹"),
    PENDANT("Pendant", "🛟"),
    HEALTH("Health", "🩺"),
    ASK("Ask", "💬"),
    MEMORY("Memory", "🧠"),
    SETUP("Setup", "⚙️"),
}

@Composable
fun HearthBottomNav(
    selected: HearthTab,
    onSelect: (HearthTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier = modifier) {
        HearthTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                icon = { Text(tab.emoji, fontSize = 22.sp) },
                label = {
                    Text(
                        tab.label,
                        fontSize = 11.sp,
                        fontWeight = if (selected == tab) FontWeight.SemiBold else FontWeight.Normal,
                    )
                },
                colors = NavigationBarItemDefaults.colors(),
            )
        }
    }
}
