package net.prototux.aare.ui.car.navi

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.prototux.aare.R
import net.prototux.aare.Settings
import net.prototux.aare.ui.car.Mate

/**
 * The route settings at the car (O188, 16.11.2026: "routen optionen sollten schon über ein zahnrad in der app sichtbar
 * sein. da will man nicht fürs grundmenü aufs tablet zurück"): the same four as on the phone's page Navi (O181), kept in
 * the same settings - a change counts for the next planning and for the next new route while guiding.
 */
@Composable
internal fun NaviRouteOptions(onBack: () -> Unit, onClose: () -> Unit) {
    val s = Settings(LocalContext.current)
    PanelHeader(stringResource(R.string.navi_options), onBack, onClose)  // named as the button that opens it (O211)
    Spacer(Modifier.size(12.dp))
    Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var ferries by remember { mutableStateOf(s.avoidFerries) }
        var motorways by remember { mutableStateOf(s.avoidMotorways) }
        var tolls by remember { mutableStateOf(s.avoidTolls) }
        var borders by remember { mutableStateOf(s.avoidBorders) }
        OptionRow(stringResource(R.string.navi_avoid_motorways), motorways) { motorways = it; s.avoidMotorways = it }
        OptionRow(stringResource(R.string.navi_avoid_tolls), tolls) { tolls = it; s.avoidTolls = it }
        OptionRow(stringResource(R.string.navi_avoid_ferries), ferries) { ferries = it; s.avoidFerries = it }
        OptionRow(stringResource(R.string.navi_avoid_borders), borders) { borders = it; s.avoidBorders = it }
        // F2: the traffic on the map (TomTom through the map server)
        var traffic by remember { mutableStateOf(s.trafficShown) }
        // with the day's TomTom calls under it (TomTomBudget)
        val ctx = LocalContext.current
        OptionRow(stringResource(R.string.navi_traffic_shown), traffic,
            stringResource(if (TomTomBudget.exhausted(ctx)) R.string.navi_traffic_budget_out else R.string.navi_traffic_budget, TomTomBudget.usedToday(ctx), TomTomBudget.LIMIT)) {
            traffic = it; s.trafficShown = it; NaviTrafficSwitch.on.value = it }
        // charging at the car too, as ABRP has it (16.11.2026): the chosen networks only, the least at the destination,
        // the highest level - the same settings as the phone app's Routenplanung (O205, O213)
        Spacer(Modifier.size(10.dp))
        Text(stringResource(R.string.navi_charging), style = MaterialTheme.typography.titleSmall, color = Mate.textDim, modifier = Modifier.padding(start = 8.dp))
        val chosen = s.chargeNetworks
        if (chosen.isNotEmpty()) {
            var only by remember { mutableStateOf(s.chargeNetworksOnly) }
            OptionRow(stringResource(R.string.navi_networks_only, chosen.mapNotNull { ChargeNetworks.name(it) }.sorted().joinToString(", ")), only) { only = it; s.chargeNetworksOnly = it }
        }
        var atDest by remember { mutableStateOf(s.chargeMinAtDestination) }
        ChoiceRow(stringResource(R.string.navi_min_destination), listOf(5, 10, 15, 20, 30), atDest) { atDest = it; s.chargeMinAtDestination = it }
        var upTo by remember { mutableStateOf(s.chargeMaxLevel) }
        ChoiceRow(stringResource(R.string.navi_max_level), listOf(70, 80, 90, 100), upTo) { upTo = it; s.chargeMaxLevel = it }
    }
}

/** A setting with a few values in percent, side by side, large enough to hit while the car stands. */
@Composable
private fun ChoiceRow(title: String, values: List<Int>, value: Int, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Mate.surface).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Mate.text)
        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            values.forEach { v ->
                val on = v == value
                Text("$v %", style = MaterialTheme.typography.titleMedium, color = if (on) Mate.onAccent else Mate.text,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (on) Mate.accent else Mate.bg)
                        .clickable { onChange(v) }.heightIn(min = 56.dp).padding(vertical = 14.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
private fun OptionRow(title: String, on: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(16.dp)).background(Mate.surface)
        .clickable { onChange(!on) }.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Mate.text)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = Mate.textDim)
        }
        Switch(checked = on, onCheckedChange = onChange, colors = SwitchDefaults.colors(
            checkedThumbColor = Mate.bg, checkedTrackColor = Mate.accent, checkedBorderColor = Mate.accent,
            uncheckedThumbColor = Mate.textDim, uncheckedTrackColor = Mate.surface, uncheckedBorderColor = Mate.textDim))
    }
}

/** The gear that opens them: what is avoided now, or that nothing is. */
@Composable
internal fun RouteOptionsButton(modifier: Modifier, onClick: () -> Unit) {
    val s = Settings(LocalContext.current)
    val avoided = listOfNotNull(
        stringResource(R.string.navi_short_motorways).takeIf { s.avoidMotorways }, stringResource(R.string.navi_short_tolls).takeIf { s.avoidTolls },
        stringResource(R.string.navi_short_ferries).takeIf { s.avoidFerries }, stringResource(R.string.navi_short_borders).takeIf { s.avoidBorders })
    Row(modifier.clip(RoundedCornerShape(18.dp)).background(Mate.surface).clickable(onClick = onClick).heightIn(min = 64.dp)
        .padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Settings, null, tint = if (avoided.isEmpty()) Mate.textDim else Mate.accent, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Text(if (avoided.isEmpty()) stringResource(R.string.navi_options) else stringResource(R.string.navi_avoiding, avoided.joinToString(", ")),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, color = if (avoided.isEmpty()) Mate.textDim else Mate.text, maxLines = 1)
    }
}
