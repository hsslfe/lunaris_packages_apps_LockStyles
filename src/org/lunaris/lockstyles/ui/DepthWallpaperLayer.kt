/*
 * SPDX-FileCopyrightText: 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lunaris.lockstyles.ui

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.os.PowerManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lunaris.clockmaker.ClockPreviewGeometry
import org.lunaris.clockmaker.spatial.SpatialWallpaperDrawable
import org.lunaris.clockmaker.spatial.SpatialWallpaperMotion
import org.lunaris.lockstyles.ClockStyle
import org.lunaris.lockstyles.CustomClocks
import org.lunaris.lockstyles.DynamicClocks
import org.lunaris.lockstyles.GalleryRenderer
import org.lunaris.lockstyles.GalleryStore
import org.lunaris.lockstyles.GalleryWallpaper
import org.lunaris.lockstyles.settings.Keys
import org.lunaris.lockstyles.settings.state
import org.lunaris.lockstyles.settings.ui.rememberWidgetLifecycle
import kotlin.math.roundToInt

internal class DepthPreviewMotion(context: Context) {
    var horizontal by mutableFloatStateOf(0f)
    var vertical by mutableFloatStateOf(0f)
    var scale by mutableFloatStateOf(1f)
    val motion = SpatialWallpaperMotion(context) { x, y, zoom ->
        horizontal = x
        vertical = y
        scale = zoom
    }
}

// The switcher card, zooming copy and editor all represent the same installed
// depth wallpaper. Share its motion state across those composition boundaries.
private class GalleryDepthMotion(context: Context) {
    val driver = DepthPreviewMotion(context)
    var consumers by mutableIntStateOf(0)
        private set

    fun acquire() { consumers++ }
    fun release() { consumers-- }
}

private val LocalGalleryDepthMotion = staticCompositionLocalOf<GalleryDepthMotion?> { null }

@Composable
internal fun GalleryPreviewScope(cache: GalleryPreviewCache, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val shared = remember(context) { GalleryDepthMotion(context) }
    val sensitivity by Keys.DEPTH_SPATIAL_SENSITIVITY.state()
    SideEffect { shared.driver.motion.setSensitivity(sensitivity) }
    BindDepthMotion(shared.driver, shared.consumers > 0)
    CompositionLocalProvider(
        LocalGalleryPreviewCache provides cache,
        LocalGalleryDepthMotion provides shared,
        content = content,
    )
}

/** Keep one sensor listener for the shared scene and stop it as soon as it is no longer visible. */
@Composable
private fun BindDepthMotion(driver: DepthPreviewMotion, moving: Boolean) {
    val context = LocalContext.current
    val lifecycle = rememberWidgetLifecycle()
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    val wantsMotion by rememberUpdatedState(moving)

    fun refresh(animate: Boolean) {
        driver.motion.setActive(
            wantsMotion && power?.isInteractive == true &&
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            animate,
        )
    }

    DisposableEffect(driver, lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh(false)
            else if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP)
                driver.motion.setActive(false, false)
        }
        val screen = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) driver.motion.setActive(false, false)
                else refresh(false)
            }
        }
        lifecycle.addObserver(observer)
        context.registerReceiver(screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }, Context.RECEIVER_NOT_EXPORTED)
        onDispose {
            context.unregisterReceiver(screen)
            lifecycle.removeObserver(observer)
            driver.motion.destroy()
        }
    }
    LaunchedEffect(driver, lifecycle, moving) {
        // The outgoing preview may release just before the incoming preview
        // acquires. Do not reset the spatial zoom during that single frame.
        if (!moving) withFrameNanos { }
        // The gallery transition already animates the scene. Start at full
        // spatial overscan instead of adding a second, tilt-attenuating ramp.
        refresh(false)
    }
}

internal class DepthPreviewSession(
    val geometry: ClockPreviewGeometry,
    val driver: DepthPreviewMotion,
    val spatial: Boolean,
    val offsetX: Int,
    val offsetY: Int,
    val foreground: SpatialWallpaperDrawable,
    val appliedPhoto: Bitmap?,
)
internal val LocalDepthPreview = staticCompositionLocalOf<DepthPreviewSession?> { null }

private fun matchesDepthWallpaper(context: Context, wallpaper: GalleryWallpaper?): Boolean {
    if (wallpaper == null) return true
    if (wallpaper.kind != GalleryWallpaper.Kind.PHOTO) return false
    val store = GalleryStore.get(context)
    val current = store.lockScreens.firstOrNull { it.id == store.currentId }?.wallpaper ?: return false
    return current.kind == wallpaper.kind && current.photos == wallpaper.photos && current.variant == wallpaper.variant
}

@Composable
internal fun rememberDepthPreview(wallpaper: GalleryWallpaper?, enabled: Boolean): DepthPreviewSession? {
    val context = LocalContext.current
    val depth by Keys.DEPTH.state()
    val path by Keys.DEPTH_SUBJECT.state()
    if (!enabled || !depth || path.isNullOrEmpty() || !matchesDepthWallpaper(context, wallpaper)) return null
    val subjectPath = path ?: return null
    val spatial by Keys.DEPTH_SPATIAL.state()
    val offsetX by Keys.DEPTH_OFFSET_X.state()
    val offsetY by Keys.DEPTH_OFFSET_Y.state()
    val sensitivity by Keys.DEPTH_SPATIAL_SENSITIVITY.state()
    val geometry = CustomClocks.geometry(context)
    // Publish the aligned photo and cutout together, including on the first
    // composition after handing off between the card and the editor.
    val cache = LocalGalleryPreviewCache.current
    val assetKey = depthPreviewKey(context, subjectPath, geometry,
        spatial && (wallpaper?.effect ?: 0) == 0)
    val assets by key(assetKey, cache) {
        produceState(cache?.get(assetKey)) {
            if (value == null) value = withContext(Dispatchers.IO) {
                loadDepthPreview(context, assetKey, cache)
            }
        }
    }
    val raw = assets?.foreground
    val appliedPhoto = assets?.background
    val shared = LocalGalleryDepthMotion.current
    val moving = spatial && raw != null
    val driver = if (shared != null) {
        DisposableEffect(shared, moving) {
            if (moving) shared.acquire()
            onDispose { if (moving) shared.release() }
        }
        shared.driver
    } else {
        val local = remember(context, subjectPath) { DepthPreviewMotion(context) }
        SideEffect { local.motion.setSensitivity(sensitivity) }
        BindDepthMotion(local, moving)
        local
    }
    val foreground = remember(raw) { raw?.let { SpatialWallpaperDrawable(it, null, 0, 0) } }
    return foreground?.let { DepthPreviewSession(geometry, driver, spatial, offsetX, offsetY, it, appliedPhoto) }
}

@Composable
internal fun DepthPreviewProvider(wallpaper: GalleryWallpaper?, enabled: Boolean,
    content: @Composable () -> Unit) {
    val session = rememberDepthPreview(wallpaper, enabled)
    CompositionLocalProvider(LocalDepthPreview provides session, content = content)
}

internal fun Modifier.depthTransform(
    geometry: ClockPreviewGeometry,
    session: DepthPreviewSession?,
): Modifier = graphicsLayer {
    transformOrigin = TransformOrigin(geometry.wallpaperPivotX / geometry.screenWidth,
        geometry.wallpaperPivotY / geometry.screenHeight)
    val driver = session?.takeIf { it.spatial }?.driver
    scaleX = geometry.wallpaperScale * (driver?.scale ?: 1f)
    scaleY = scaleX
    val pxPerDpX = geometry.density * size.width / geometry.screenWidth
    val pxPerDpY = geometry.density * size.height / geometry.screenHeight
    val offsetX = if (session?.spatial == true) session.offsetX * scaleX else 0f
    val offsetY = if (session?.spatial == true) session.offsetY * scaleY else 0f
    translationX = ((driver?.horizontal ?: 0f) * 12f + offsetX) * pxPerDpX
    translationY = ((driver?.vertical ?: 0f) * 9f + offsetY) * pxPerDpY
}

@Composable
internal fun AppliedDepthPhoto(bitmap: Bitmap, modifier: Modifier) {
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    Canvas(modifier) {
        drawIntoCanvas {
            GalleryRenderer.drawCentreCrop(it.nativeCanvas, bitmap, size.width, size.height, paint)
        }
    }
}

@Composable
internal fun DepthWallpaperLayer(wallpaper: GalleryWallpaper? = null) {
    val session = LocalDepthPreview.current ?: return
    val geometry = session.geometry
    val opacity by Keys.DEPTH_OPACITY.state()
    val fade by Keys.DEPTH_BOTTOM_FADE.state()
    val inset by Keys.DEPTH_BOTTOM_INSET.state()
    val curve by Keys.DEPTH_FADE_CURVE.state()
    Canvas(Modifier.fillMaxSize().depthTransform(geometry, session)) {
        val drawable = session.foreground
        drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
        drawable.setImagePlacement(
            if (session.spatial) 0f else session.offsetX * geometry.density,
            if (session.spatial) 0f else session.offsetY * geometry.density,
            true,
        )
        drawable.setFadeHeightPx((fade.coerceAtLeast(0) * geometry.density).roundToInt())
        drawable.setBottomInsetPx((inset.coerceAtLeast(0) * geometry.density).roundToInt())
        drawable.setFadeCurveExponent(0.2f + curve.coerceIn(0, 100) / 100f * 0.8f)
        drawable.alpha = (opacity.coerceIn(0, 100) * 2.55f).roundToInt().coerceIn(0, 255)
        drawIntoCanvas { drawable.draw(it.nativeCanvas) }
    }
}

@Composable
internal fun DepthWallpaperPreview() {
    val context = LocalContext.current
    val geometry = CustomClocks.geometry(context)
    val subjectPath by Keys.DEPTH_SUBJECT.state()
    val wm = remember(context) { WallpaperManager.getInstance(context) }
    val wallpaperId = wm.getWallpaperId(WallpaperManager.FLAG_LOCK).takeIf { it > 0 }
        ?: wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
    val bitmap by produceState<Bitmap?>(null, wallpaperId, subjectPath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                var loaded: Bitmap? = null
                for (flag in intArrayOf(WallpaperManager.FLAG_LOCK, WallpaperManager.FLAG_SYSTEM)) {
                    wm.getWallpaperFile(flag)?.use { descriptor ->
                        loaded = BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor)
                    }
                    if (loaded != null) break
                }
                loaded
            }.getOrNull()
        }
    }
    val store = remember(context) { GalleryStore.get(context) }
    val active = store.lockScreens.firstOrNull { it.id == store.currentId }
    val dynamic = DynamicClocks.load(context)
    val clock = if (dynamic.enabled) dynamic.large
        else CustomClocks.appliedStyle(context, active?.clock ?: ClockStyle())
    Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(geometry.aspect, matchHeightConstraintsFirst = true)
            .clip(MaterialTheme.shapes.extraLarge).background(Color.Black)) {
            LockScreenPreviewLayout(Modifier.fillMaxSize(), showDepth = true) {
                val session = LocalDepthPreview.current
                val background = Modifier.fillMaxSize().depthTransform(geometry, session)
                val appliedPhoto = session?.appliedPhoto
                if (appliedPhoto != null) {
                    AppliedDepthPhoto(appliedPhoto, background)
                } else {
                    bitmap?.let { Image(it.asImageBitmap(), null, background, contentScale = ContentScale.Crop) }
                }
                if (geometry.wallpaperDimAmount > 0f)
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = geometry.wallpaperDimAmount)))
                Box(Modifier.fillMaxSize().background(Color(geometry.wallpaperScrimColor)))
                CustomClockLayer(clock, wallpaper = active?.wallpaper)
                DepthWallpaperLayer()
            }
        }
    }
}
