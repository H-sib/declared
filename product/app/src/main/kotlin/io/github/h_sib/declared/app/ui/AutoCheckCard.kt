@file:OptIn(ExperimentalLayoutApi::class)

package io.github.h_sib.declared.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.h_sib.declared.core.schedule.AutoCheck
import io.github.h_sib.declared.core.schedule.AutoConfig
import io.github.h_sib.declared.core.schedule.Interval
import io.github.h_sib.declared.core.schedule.IntervalUnit

/** What the settings card needs from the app: the choice, whether notifications can be shown, and how to change either. */
class AutoCheckControls(
    val config: AutoConfig,
    val notificationsAllowed: Boolean,
    val onChange: (AutoConfig) -> Unit,
    val openNotificationSettings: () -> Unit,
)

val LocalAutoCheck = staticCompositionLocalOf<AutoCheckControls?> { null }

/**
 * Automatic checks: off until the person turns them on, then an interval of their own (a few quick choices, or any number of minutes, hours or
 * days Android can run). It says what it does and what it does not.
 */
@Composable
fun AutoCheckCard() {
    val auto = LocalAutoCheck.current ?: return
    val config = auto.config
    val start = remember { AutoCheck.split(config.intervalMinutes) }
    var amount by rememberSaveable { mutableStateOf(start.first.toString()) }
    var unit by rememberSaveable { mutableStateOf(start.second) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = config.enabled, role = Role.Switch, onValueChange = { auto.onChange(config.copy(enabled = it)) }),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Check automatically", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (config.enabled) "On, ${AutoCheck.describe(config.intervalMinutes)}" else "Off. Nothing runs in the background.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = config.enabled, onCheckedChange = null)
            }
            if (config.enabled) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SectionLabel("How often")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (minutes in AutoCheck.presets) {
                        FilterChip(
                            selected = minutes == config.intervalMinutes,
                            onClick = {
                                val (n, u) = AutoCheck.split(minutes)
                                amount = n.toString()
                                unit = u
                                error = null
                                auto.onChange(config.copy(intervalMinutes = minutes))
                            },
                            label = { Text(AutoCheck.label(minutes)) },
                        )
                    }
                }
                SectionLabel("Or your own time")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it.filter(Char::isDigit).take(6); error = null },
                        modifier = Modifier.width(112.dp).semantics { contentDescription = "Custom time, number" },
                        label = { Text("Number") },
                        singleLine = true,
                        isError = error != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (u in IntervalUnit.entries) FilterChip(selected = unit == u, onClick = { unit = u; error = null }, label = { Text(u.label) })
                    }
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = {
                        when (val interval = AutoCheck.parse(amount, unit)) {
                            is Interval.Valid -> { error = null; auto.onChange(config.copy(intervalMinutes = interval.minutes)) }
                            else -> error = AutoCheck.intervalError(interval)
                        }
                    },
                    Modifier.heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Use this time") }
                Bullets(
                    listOf(
                        "Android may run it a little later to save battery.",
                        "It only looks and tells you. It changes nothing.",
                        "You get a notification only when something needs a look.",
                    ),
                    Status.NOTE,
                )
                if (!auto.notificationsAllowed) {
                    StatusPill(Status.LOOK, "Notifications are off for Declared")
                    OutlinedButton(onClick = auto.openNotificationSettings, Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Allow notifications") }
                }
            }
        }
    }
}
