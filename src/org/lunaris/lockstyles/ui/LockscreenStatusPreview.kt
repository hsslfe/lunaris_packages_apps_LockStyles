/* SPDX-License-Identifier: Apache-2.0 */
package org.lunaris.lockstyles.ui

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.os.BatteryManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.internal.util.lunaris.OmniJawsClient
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lunaris.lockstyles.CustomClocks
import org.lunaris.lockstyles.settings.Keys
import org.lunaris.lockstyles.settings.state
import org.lunaris.lockstyles.settings.ui.rememberWidgetLifecycle

private data class WeatherReading(
    val temperature: String,
    val city: String,
    val condition: String,
    val wind: String,
    val humidity: String,
    val icon: Bitmap?,
)

// The pager, animated copy and editor share data, not independent pollers.
private class StatusPreviewData {
    var weather by mutableStateOf<WeatherReading?>(null)
    var readings by mutableStateOf<List<Int?>>(List(4) { null })
    var weatherUsers by mutableIntStateOf(0)
    var deviceUsers by mutableIntStateOf(0)
}

private val LocalStatusPreviewData = staticCompositionLocalOf<StatusPreviewData?> { null }

@Composable
internal fun LockscreenStatusPreviewScope(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val data = remember(context) { StatusPreviewData() }
    BindStatusPreview(data)
    CompositionLocalProvider(LocalStatusPreviewData provides data, content = content)
}

@Composable
private fun BindStatusPreview(data: StatusPreviewData) {
    val context = LocalContext.current
    val lifecycle = rememberWidgetLifecycle()
    val weatherWanted = data.weatherUsers > 0
    val deviceWanted = data.deviceUsers > 0
    LaunchedEffect(data, context, lifecycle, weatherWanted) {
        if (!weatherWanted) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val client = OmniJawsClient.get()
            val updates = Channel<Unit>(Channel.CONFLATED)
            val observer = object : OmniJawsClient.OmniJawsObserver {
                override fun weatherUpdated() { updates.trySend(Unit) }
                override fun weatherError(errorReason: Int) { updates.trySend(Unit) }
                override fun updateSettings() { updates.trySend(Unit) }
            }
            val observing = runCatching { client.addObserver(context, observer) }.isSuccess
            try {
                updates.trySend(Unit)
                for (event in updates) {
                    val previous = data.weather
                    data.weather = withContext(Dispatchers.IO) {
                        val reading = readWeather(context, client)
                        val previousIcon = previous?.icon
                        val icon = reading?.icon
                        // Identical provider updates must not invalidate the shared
                        // clock bitmap just as a new preview owner takes over.
                        if (reading != null && icon != null && previousIcon != null &&
                            icon.sameAs(previousIcon)) reading.copy(icon = previousIcon)
                        else reading
                    }
                }
            } finally {
                if (observing) runCatching { client.removeObserver(context, observer) }
                updates.close()
            }
        }
    }
    LaunchedEffect(data, context, lifecycle, deviceWanted) {
        if (!deviceWanted) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            coroutineScope {
                val updates = Channel<Unit>(Channel.CONFLATED)
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        updates.trySend(Unit)
                    }
                }
                val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
                    addAction(AudioManager.VOLUME_CHANGED_ACTION)
                    addAction(AudioManager.STREAM_MUTE_CHANGED_ACTION)
                }
                val registered = runCatching {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                }.isSuccess
                // RAM has no change broadcast. Only sample while a visible
                // preview needs it, with immediate battery/volume updates.
                val memoryRefresh = launch {
                    while (true) {
                        delay(5000L)
                        updates.trySend(Unit)
                    }
                }
                try {
                    updates.trySend(Unit)
                    for (event in updates) {
                        data.readings = withContext(Dispatchers.Default) { readDeviceInfo(context) }
                    }
                } finally {
                    memoryRefresh.cancel()
                    if (registered) runCatching { context.unregisterReceiver(receiver) }
                    updates.close()
                }
            }
        }
    }
}

private fun readWeather(context: Context, client: OmniJawsClient): WeatherReading? = runCatching {
    client.queryWeather(context)
    val info = client.weatherInfo ?: return@runCatching null
    if (info.temp.isNullOrBlank()) return@runCatching null
    val icon = runCatching {
        val drawable = client.getWeatherConditionImage(context, info.conditionCode)
        Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also {
            drawable.setBounds(0, 0, it.width, it.height)
            drawable.draw(Canvas(it))
        }
    }.getOrNull()
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    val condition = info.condition.orEmpty().split(" ").joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }
    WeatherReading(info.temp + info.tempUnits.orEmpty(), info.city.orEmpty(), condition,
        listOf(info.windSpeed, info.windUnits, info.pinWheel).filterNotNull()
            .filter { it.isNotBlank() }.joinToString(" "), info.humidity.orEmpty(), icon)
}.getOrNull()

private fun readDeviceInfo(context: Context): List<Int?> {
    val battery = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.takeIf { it >= 0 }
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val memory = runCatching {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
        if (info.totalMem > 0) ((info.totalMem - info.availMem) * 100 / info.totalMem).toInt()
            .coerceIn(0, 100) else null
    }.getOrNull()
    val temperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        ?.takeIf { it != Int.MIN_VALUE }?.div(10)
    val volume = runCatching {
        val audio = context.getSystemService(AudioManager::class.java) ?: return@runCatching null
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max > 0) (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max)
            .coerceIn(0, 100) else null
    }.getOrNull()
    return listOf(level?.let { (it * 100 / scale.coerceAtLeast(1)).coerceIn(0, 100) },
        memory, temperature, volume)
}

@Composable
private fun statusData(weather: Boolean = false, device: Boolean = false): StatusPreviewData {
    val shared = LocalStatusPreviewData.current
    val context = LocalContext.current
    val data = shared ?: remember(context) { StatusPreviewData() }
    if (shared == null) BindStatusPreview(data)
    DisposableEffect(data, weather, device) {
        if (weather) data.weatherUsers++
        if (device) data.deviceUsers++
        onDispose {
            if (weather) data.weatherUsers--
            if (device) data.deviceUsers--
        }
    }
    return data
}

@Composable
internal fun rememberLockscreenWeather(): CustomClocks.PreviewWeather? {
    val enabled by Keys.WEATHER.state()
    if (!enabled) return null
    val showLocation by Keys.WEATHER_LOCATION.state()
    val showCondition by Keys.WEATHER_TEXT.state()
    val showWind by Keys.WEATHER_WIND.state()
    val showHumidity by Keys.WEATHER_HUMIDITY.state()
    val reading = statusData(weather = true).weather ?: return null
    val text = buildList {
        add(reading.temperature)
        if (showLocation && reading.city.isNotBlank()) add(reading.city)
        if (showCondition && reading.condition.isNotBlank()) add(reading.condition)
        if (showWind && reading.wind.isNotBlank()) add(reading.wind)
        if (showHumidity && reading.humidity.isNotBlank()) add(reading.humidity)
    }.joinToString(" • ")
    return CustomClocks.PreviewWeather(text, reading.icon)
}

private fun systemUiContext(context: Context): Context? =
    runCatching { context.createPackageContext("com.android.systemui", 0) }.getOrNull()

@Composable
internal fun WeatherWallpaperLayer(
    clockBottom: Float?,
    weather: CustomClocks.PreviewWeather?,
    embedded: Boolean,
): Float? {
    if (clockBottom == null || weather == null || embedded) return clockBottom
    val context = LocalContext.current
    val geometry = CustomClocks.geometry(context)
    val density = LocalDensity.current
    val systemUi = remember(context) { systemUiContext(context) }
    val iconSize = remember(systemUi, geometry.density) {
        val res = systemUi?.resources
        val id = res?.getIdentifier("weather_image_max_size", "dimen", "com.android.systemui") ?: 0
        if (id != 0 && res != null) res.getDimensionPixelSize(id).toFloat()
        else 24f * geometry.density
    }
    // Keep the measured height separate from its anchor. onSizeChanged
    // does not run when only the clock's position or the weather text changes.
    var rowHeight by remember { mutableIntStateOf(0) }
    var sceneScale by remember { mutableFloatStateOf(1f) }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val scale = constraints.maxWidth / geometry.screenWidth.toFloat()
        SideEffect { sceneScale = scale }
        val top = clockBottom + 8f * scale
        val left = geometry.clockLeft.coerceIn(0, geometry.screenWidth - 1)
        val width = (geometry.contentLeft + geometry.contentWidth - left).coerceAtLeast(1)
        Row(
            Modifier.align(Alignment.TopStart)
                .absoluteOffset { IntOffset((left * scale).roundToInt(), top.roundToInt()) }
                .width(with(density) { (width * scale).toDp() })
                .onSizeChanged { rowHeight = it.height },
            horizontalArrangement = Arrangement.spacedBy(with(density) { (12f * scale).toDp() }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            weather.icon?.let {
                Image(it.asImageBitmap(), null,
                    Modifier.size(with(density) { (iconSize * scale).toDp() }))
            }
            Text(weather.text, color = Color.White, fontSize = (16f * scale).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    return clockBottom + 8f * sceneScale + maxOf(rowHeight.toFloat(), iconSize * sceneScale)
}

// The original SystemUI gauge uses a 400px bitmap and 40px rounded arc stroke,
// with the clock typeface, 40dp text and 36dp drawable inside the bitmap.
private fun gaugeBitmap(context: Context, systemUi: Context?, value: Int?,
    index: Int, tint: Int): Bitmap {
    val density = context.resources.displayMetrics.density
    val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val arc = RectF(25f, 25f, 375f, 375f)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 40f
        strokeCap = Paint.Cap.ROUND
        color = android.graphics.Color.argb(75, 255, 255, 255)
    }
    canvas.drawArc(arc, 135f, 275f, false, paint)
    paint.color = tint
    canvas.drawArc(arc, 135f, 275f * (value ?: 0).coerceIn(0, 100) / 100f, false, paint)
    val fontId = context.resources.getIdentifier("config_clockFontFamily", "string", "android")
    val family = if (fontId != 0) context.getString(fontId) else null
    val text = if (value == null) if (index == 2) "N/A" else "..."
        else if (index == 2) "$value℃" else "$value%"
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 40f * density
        color = tint
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(family, Typeface.BOLD)
    }
    canvas.drawText(text, 200f, (400f - textPaint.ascent() * 0.7f) / 2f, textPaint)
    val icons = listOf("ic_battery", "ic_memory", "ic_temperature", "ic_volume_eq")
    val res = systemUi?.resources
    val iconId = res?.getIdentifier(icons[index], "drawable", "com.android.systemui") ?: 0
    if (iconId != 0 && systemUi != null) runCatching {
        val icon = systemUi.getDrawable(iconId)?.mutate() ?: return@runCatching
        val size = (36f * density).toInt()
        val left = (400 - size) / 2
        val top = 400 - (size / 1.3f).toInt() - 45 - (4f * density).toInt()
        icon.setBounds(left, top, left + size, top + size)
        icon.setTint(tint)
        icon.draw(canvas)
    }
    return bitmap
}

@Composable
internal fun DeviceInfoGauges(modifier: Modifier = Modifier, scale: Float = 1f,
    enabled: Boolean = true) {
    val context = LocalContext.current
    val values = statusData(device = enabled).readings
    val mode by Keys.WIDGETS_COLOR_MODE.state()
    val custom by Keys.WIDGETS_CUSTOM_COLOR.state()
    val opacity by Keys.WIDGETS_TRANSPARENCY.state()
    val tint = when (mode) {
        "custom" -> custom
        "accent" -> context.getColor(android.R.color.system_accent1_100)
        else -> android.graphics.Color.WHITE
    }
    val systemUi = remember(context) { systemUiContext(context) }
    Row(modifier.alpha(opacity.coerceIn(0, 100) / 100f * if (enabled) 1f else 0.4f),
        horizontalArrangement = Arrangement.spacedBy((24f * scale).dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        values.forEachIndexed { index, value ->
            val bitmap = remember(context, systemUi, value, index, tint) {
                gaugeBitmap(context, systemUi, value, index, tint)
            }
            Image(bitmap.asImageBitmap(), null, Modifier.size((60f * scale).dp))
        }
    }
}

@Composable
internal fun DeviceInfoWallpaperLayer(contentBottom: Float?): Float? {
    val enabled by Keys.INFO_WIDGETS.state()
    val extraInfo by Keys.EXTRA_INFO_WIDGETS.state()
    if (!enabled || extraInfo || contentBottom == null) return contentBottom
    val geometry = CustomClocks.geometry(LocalContext.current)
    val density = LocalDensity.current
    var occupiedHeight by remember(geometry, density) {
        mutableFloatStateOf(geometry.infoTopOffset + minOf(60f * geometry.density,
            geometry.contentWidth * 60f / 312f))
    }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val scale = constraints.maxWidth / geometry.screenWidth.toFloat()
        val top = contentBottom + geometry.infoTopOffset * scale
        val left = geometry.contentLeft * scale
        val width = geometry.contentWidth * scale
        val availableDp = with(density) { width.toDp().value }
        val gaugeScale = minOf(scale * geometry.density / density.density, availableDp / 312f)
        val height = with(density) { (60f * gaugeScale).dp.roundToPx() }
        SideEffect { occupiedHeight = geometry.infoTopOffset * scale + height }
        DeviceInfoGauges(
            Modifier.align(Alignment.TopStart)
                .absoluteOffset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .width(with(density) { width.toDp() }),
            scale = gaugeScale,
        )
    }
    return contentBottom + occupiedHeight
}
