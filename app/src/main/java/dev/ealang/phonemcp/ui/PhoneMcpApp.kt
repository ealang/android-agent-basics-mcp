package dev.ealang.phonemcp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.ealang.phonemcp.MainActivity

/**
 * The shell: four top-level destinations on a bottom bar, per the platform
 * convention for 3-5 peers. The top bar retitles per page — the app is
 * really two apps in one shell (a Pebble recording pipeline and a phone MCP
 * server), and a constant title lied about whichever side was showing. Tab
 * labels stay short; the top bar carries the full name. Each tab is its own
 * file; the activity is passed down because it owns the non-UI glue (config,
 * service calls, permission requests) and the refresh tick every tab
 * subscribes to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneMcpApp(activity: MainActivity) {
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (tab) {
                            0 -> "Status"
                            1 -> "Pebble webhook"
                            2 -> "Phone MCP"
                            else -> "Server config"
                        },
                    )
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Info, contentDescription = null) },
                    label = { Text("Status") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Watch, contentDescription = null) },
                    label = { Text("Pebble") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Smartphone, contentDescription = null) },
                    label = { Text("MCP") },
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Servers") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> StatusTab(activity)
                1 -> PebbleTab(activity)
                2 -> McpTab(activity)
                3 -> ServersTab(activity)
            }
        }
    }
}
