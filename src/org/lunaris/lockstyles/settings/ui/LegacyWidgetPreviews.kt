/* SPDX-License-Identifier: Apache-2.0 */
package org.lunaris.lockstyles.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Wallet
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.DeviceUnknown
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import org.lunaris.lockstyles.R
import org.lunaris.lockstyles.settings.Keys
import org.lunaris.lockstyles.settings.state
import org.lunaris.lockstyles.ui.DeviceInfoGauges

@Composable
fun DeviceInfoPreview() {
    val enabled by Keys.INFO_WIDGETS.state()
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
        .background(Color(0xFF252525)).padding(16.dp)) {
        Text(stringResource(R.string.lockscreen_display_widgets_title), color = Color.White)
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            DeviceInfoGauges(
                Modifier.fillMaxWidth(), scale = minOf(1f, maxWidth.value / 312f), enabled = enabled)
        }
    }
}

private fun legacyIcon(value: String): ImageVector = when (value) {
    "calculator" -> Icons.Rounded.Calculate
    "media" -> Icons.Rounded.MusicNote
    "timer" -> Icons.Rounded.Timer
    "torch" -> Icons.Rounded.FlashlightOn
    "weather" -> Icons.Rounded.Cloud
    "wifi" -> Icons.Rounded.Wifi
    "data" -> Icons.Rounded.DataUsage
    "ringer" -> Icons.Rounded.VolumeUp
    "bt" -> Icons.Rounded.Bluetooth
    "hotspot" -> Icons.Rounded.WifiTethering
    "wallet" -> Icons.Rounded.Wallet
    "qrscanner" -> Icons.Rounded.QrCodeScanner
    else -> Icons.Rounded.DeviceUnknown
}

private val SlotHeight = 64.dp
private val SlotSpacing = 12.dp
private val GridCorner = 22.dp

@Composable
fun LegacyWidgetPreview(slots: List<String>, enabled: Boolean) {
    val style by Keys.WIDGETS_STYLE.state()
    val opacity by Keys.WIDGETS_TRANSPARENCY.state()
    val labels = stringArrayResource(R.array.widget_values).zip(stringArrayResource(R.array.widget_entries)).toMap()
    val shape = if (style in listOf("1", "2", "4")) RoundedCornerShape(GridCorner) else CircleShape
    val fill = Color.White.copy(alpha = if (style in listOf("4", "5")) 0.12f else 0.22f)
    val wide = slots.take(2).filter { it != "none" }
    val round = slots.drop(2).filter { it != "none" }
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
        .background(Color(0xFF252525)).padding(16.dp)) {
        Box(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f)
            .border(1.5.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(GridCorner))
            .padding(SlotSpacing)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SlotSpacing)) {
                SlotRow(wide, 2, shape) { value ->
                    Row(Modifier.fillMaxSize().clip(shape).background(fill.copy(alpha = fill.alpha * opacity / 100f))
                        .padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(legacyIcon(value), null, tint = Color.White, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(labels[value].orEmpty(), color = Color.White, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium)
                    }
                }
                SlotRow(round, 4, shape) { value ->
                    Box(Modifier.fillMaxSize().clip(shape).background(fill.copy(alpha = fill.alpha * opacity / 100f)),
                        contentAlignment = Alignment.Center) {
                        Icon(legacyIcon(value), labels[value], tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                }
            }
            if (slots.all { it == "none" }) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Widgets, null, Modifier.size(32.dp), tint = Color.White.copy(alpha = 0.5f))
                    Text(stringResource(R.string.status_bar_date_none), color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun SlotRow(items: List<String>, capacity: Int, shape: androidx.compose.ui.graphics.Shape,
        content: @Composable (String) -> Unit) {
    Row(Modifier.fillMaxWidth().height(SlotHeight), horizontalArrangement = Arrangement.Center) {
        if (items.isEmpty()) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(SlotSpacing)) {
                repeat(capacity) {
                    Box(Modifier.weight(1f).fillMaxHeight()
                        .border(1.dp, Color.White.copy(alpha = 0.15f), shape))
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(items.size / capacity.toFloat()).fillMaxHeight(),
                horizontalArrangement = Arrangement.spacedBy(SlotSpacing)) {
                items.forEach { value -> Box(Modifier.weight(1f).fillMaxHeight()) { content(value) } }
            }
        }
    }
}
