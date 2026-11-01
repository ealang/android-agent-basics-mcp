package dev.ealang.agentbasicsmcp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import dev.ealang.agentbasicsmcp.Endpoint
import dev.ealang.agentbasicsmcp.Networks

/**
 * The bind address: a text field that also drops down the phone's live
 * addresses. Type anything, or pick a row and it fills the field. The list is
 * suggestions, not the set of legal values, so a saved address stays in the
 * field when its network is down — the tailnet address is gone whenever
 * Tailscale is, and that is exactly when the user needs to see what is set.
 *
 * The line under the field says what the current value is right now: the
 * network it belongs to, or that the phone does not hold it at the moment.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BindAddressField(
    value: String,
    onValueChange: (String) -> Unit,
    /** Re-read on every open and every recomposition, so a tunnel that comes up appears. */
    live: () -> List<Endpoint>,
) {
    var expanded by remember { mutableStateOf(false) }
    val endpoints = live()

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("Bind address") },
            placeholder = { Text("Pick from the list, or type one") },
            supportingText = { Text(statusFor(value, endpoints)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            endpoints.forEach { endpoint ->
                DropdownMenuItem(
                    text = { EndpointRow(endpoint.kind, endpoint.address, endpoint.iface) },
                    onClick = {
                        onValueChange(endpoint.address)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
            DropdownMenuItem(
                text = { EndpointRow("All IPv4 interfaces", Networks.ALL_IPV4, "LAN included") },
                onClick = {
                    onValueChange(Networks.ALL_IPV4)
                    expanded = false
                },
                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
            )
        }
    }
}

@Composable
private fun EndpointRow(kind: String, address: String, detail: String) {
    Column {
        Text(kind, style = MaterialTheme.typography.bodyLarge)
        Text(
            if (detail.isEmpty()) address else "$address  ·  $detail",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** What the value in the field is, right now. */
fun statusFor(value: String, endpoints: List<Endpoint>): String {
    val address = value.trim()
    return when {
        address.isEmpty() || address == Networks.ALL_IPV4 ->
            "Every IPv4 interface, this phone's LAN included."
        !Networks.isLiteral(address) ->
            "Not an IP address. Names are refused: they resolve, they do not bind."
        else -> endpoints.firstOrNull { it.address == address }?.let {
            if (it.iface.isEmpty()) it.kind else "${it.kind}  ·  ${it.iface}"
        } ?: "Not on this phone right now. The server will bind it when it appears."
    }
}
