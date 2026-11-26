/*
 * SPDX-FileCopyrightText: 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lunaris.lockstyles.ui

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.LruCache
import androidx.compose.runtime.staticCompositionLocalOf
import java.io.File
import org.lunaris.clockmaker.ClockPreviewGeometry
import org.lunaris.lockstyles.CustomClocks
import org.lunaris.lockstyles.GalleryRenderer
import org.lunaris.lockstyles.GalleryStore
import org.lunaris.lockstyles.GalleryWallpaper
import org.lunaris.lockstyles.settings.Keys

// One bounded cache per gallery launch. The pager, zooming card and editor share
// immutable bitmap assets, independently of the shared preview motion controller.
internal class GalleryPreviewCache {
    private val entries = object : LruCache<Any, PreviewBitmaps>(48 * 1024 * 1024) {
        override fun sizeOf(key: Any, value: PreviewBitmaps): Int =
            (value.background?.allocationByteCount ?: 0) +
                (value.foreground?.allocationByteCount ?: 0)
    }

    fun get(key: Any): PreviewBitmaps? = entries.get(key)
    fun put(key: Any, value: PreviewBitmaps) { entries.put(key, value) }
}

internal val LocalGalleryPreviewCache = staticCompositionLocalOf<GalleryPreviewCache?> { null }
internal data class PreviewBitmaps(val background: Bitmap?, val foreground: Bitmap? = null)

internal data class PhotoPreviewKey(
    val path: String, val revision: Long, val length: Long,
    val variant: Int, val width: Int, val height: Int,
)

internal fun photoPreviewKey(wallpaper: GalleryWallpaper, width: Int, height: Int): PhotoPreviewKey? {
    if (wallpaper.kind != GalleryWallpaper.Kind.PHOTO) return null
    val path = wallpaper.photos.firstOrNull() ?: return null
    if (path.startsWith("content:")) return null
    val file = File(path)
    return PhotoPreviewKey(path, file.lastModified(), file.length(), wallpaper.variant, width, height)
}

internal fun loadPhotoPreview(context: Context, key: PhotoPreviewKey, cache: GalleryPreviewCache): Bitmap? {
    cache.get(key)?.background?.let { return it }
    val source = GalleryRenderer.loadPhoto(context, key.path, maxOf(key.width, key.height)) ?: return null
    val bitmap = Bitmap.createBitmap(key.width, key.height, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = GalleryRenderer.filter(key.variant) }
    GalleryRenderer.drawCentreCrop(Canvas(bitmap), source, key.width.toFloat(), key.height.toFloat(), paint)
    cache.put(key, PreviewBitmaps(bitmap))
    return bitmap
}

internal data class DepthPreviewKey(
    val subjectPath: String, val revision: Long, val length: Long,
    val width: Int, val height: Int, val flag: Int, val wallpaperId: Int,
)

internal fun depthPreviewKey(context: Context, path: String, geometry: ClockPreviewGeometry,
    useAppliedPhoto: Boolean): DepthPreviewKey {
    val file = File(path)
    val wm = WallpaperManager.getInstance(context)
    val lockId = if (useAppliedPhoto) wm.getWallpaperId(WallpaperManager.FLAG_LOCK) else 0
    val flag = if (!useAppliedPhoto) 0 else if (lockId > 0) WallpaperManager.FLAG_LOCK
        else WallpaperManager.FLAG_SYSTEM
    val id = if (flag == 0) 0 else if (lockId > 0) lockId else wm.getWallpaperId(flag)
    return DepthPreviewKey(path, file.lastModified(), file.length(), geometry.screenWidth,
        geometry.screenHeight, flag, id)
}

internal fun loadDepthPreview(context: Context, key: DepthPreviewKey,
    cache: GalleryPreviewCache?): PreviewBitmaps {
    cache?.get(key)?.let { return it }
    val raw = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(key.subjectPath, options)
        var sample = 1
        while (options.outWidth / (sample * 2) >= key.width &&
            options.outHeight / (sample * 2) >= key.height) sample *= 2
        options.inSampleSize = sample
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        BitmapFactory.decodeFile(key.subjectPath, options)
    }.getOrNull()
    val applied = if (raw != null && key.flag != 0) runCatching {
        WallpaperManager.getInstance(context).getWallpaperFile(key.flag)?.use {
            BitmapFactory.decodeFileDescriptor(it.fileDescriptor)
        }
    }.getOrNull() else null
    return PreviewBitmaps(applied, raw).also {
        // Transient failures must not prevent a later preview from retrying.
        if (raw != null && (key.flag == 0 || applied != null)) cache?.put(key, it)
    }
}

internal fun prepareGalleryPreview(context: Context, store: GalleryStore, cache: GalleryPreviewCache) {
    val wallpaper = store.lockScreens.firstOrNull { it.id == store.currentId }?.wallpaper ?: return
    if (wallpaper.kind != GalleryWallpaper.Kind.PHOTO) return
    val geometry = CustomClocks.geometry(context)
    val subject = Keys.DEPTH_SUBJECT.read(context)
    if (Keys.DEPTH.read(context) && !subject.isNullOrEmpty()) {
        val key = depthPreviewKey(context, subject, geometry,
            Keys.DEPTH_SPATIAL.read(context) && wallpaper.effect == 0)
        loadDepthPreview(context, key, cache)
    }
    photoPreviewKey(wallpaper, geometry.screenWidth, geometry.screenHeight)?.let {
        loadPhotoPreview(context, it, cache)
    }
}
