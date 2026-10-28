package dev.bridgey.android

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Date

internal fun RouteClass.labelRes(): Int = when (this) {
    RouteClass.LOCAL -> R.string.health_route_local
    RouteClass.SHARED -> R.string.health_route_shared
    RouteClass.OTHER -> R.string.health_route_other
    RouteClass.UNKNOWN -> R.string.health_route_unknown
}

internal fun DisconnectReason.labelRes(): Int = when (this) {
    DisconnectReason.HEARTBEAT -> R.string.health_drop_heartbeat
    DisconnectReason.CONNECTION_LOST -> R.string.health_drop_connection
    DisconnectReason.USER -> R.string.health_drop_user
    DisconnectReason.PROTOCOL -> R.string.health_drop_protocol
}

/** Connection health without addresses, names, identifiers or content. */
@Composable
internal fun ConnectionHealthCard(health: ConnectionHealthSnapshot, batteryOptimizationIgnored: Boolean) {
    val context = LocalContext.current
    val time: (Long) -> String = { DateFormat.getTimeFormat(context).format(Date(it)) }
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.health_title), fontWeight = FontWeight.SemiBold)
            val detail = MaterialTheme.typography.bodySmall
            if (health.connected) {
                Text(stringResource(R.string.health_connected, stringResource(health.route.labelRes()),
                    stringResource(if (health.dialledHere == true) R.string.health_opened_here else R.string.health_opened_peer)), style = detail)
                health.connectedSinceMillis?.let { Text(stringResource(R.string.health_since, time(it)), style = detail) }
                val last = health.lastRttMillis
                val median = health.medianRttMillis
                Text(if (last == null || median == null) stringResource(R.string.health_rtt_waiting)
                    else stringResource(R.string.health_rtt, last, median), style = detail)
            } else {
                Text(stringResource(R.string.health_not_connected), style = detail)
            }
            val drop = health.lastDisconnect
            val dropAt = health.lastDisconnectAtMillis
            if (drop != null && dropAt != null) Text(stringResource(R.string.health_last_drop, stringResource(drop.labelRes()), time(dropAt)), style = detail)
            Text(stringResource(R.string.health_attempts, health.attemptsToday), style = detail)
            Text(stringResource(if (batteryOptimizationIgnored) R.string.health_battery_exempt else R.string.health_battery_optimized),
                style = detail, color = if (batteryOptimizationIgnored) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary)
        }
    }
}
