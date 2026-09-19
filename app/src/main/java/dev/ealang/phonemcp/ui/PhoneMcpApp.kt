package dev.ealang.phonemcp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.ealang.phonemcp.MainActivity

/**
 * MOCK-UP shell: two tabs, one per half of the app. Each page is
 * self-contained — its server's switch on top, then its setup, its status,
 * its settings — so there is no shared status page to cross-reference and
 * no title bar needed to say which half you are looking at. The activity is
 * passed down because it owns the non-UI glue (config, service calls,
 * permission requests) and the refresh tick every tab subscribes to.
 */
@Composable
fun PhoneMcpApp(activity: MainActivity) {
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Watch, contentDescription = null) },
                    label = { Text("Webhook") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Smartphone, contentDescription = null) },
                    label = { Text("MCP") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> WebhookTab(activity)
                1 -> McpTab(activity)
            }
        }
    }
}
