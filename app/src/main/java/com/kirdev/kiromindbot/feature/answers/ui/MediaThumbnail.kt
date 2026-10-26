package com.kirdev.kiromindbot.feature.answers.ui

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kirdev.kiromindbot.core.designsystem.AppMuted
import com.kirdev.kiromindbot.core.designsystem.AppSurfaceAlt
import com.kirdev.kiromindbot.feature.answers.domain.AnswerMedia
import com.kirdev.kiromindbot.feature.answers.domain.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Small decoded previews, shared by all editor screens; ~40 thumbnails of 144 px stay well under 4 MB. */
private val cache = LruCache<String, Bitmap>(40)

/**
 * Preview of a local photo or video picked through SAF; anything else (demo, links, Telegram
 * files, audio, documents) or a file that is gone falls back to the [fallback] icon.
 */
@Composable
fun MediaThumbnail(item: AnswerMedia, fallback: ImageVector) {
    val context = LocalContext.current
    val previewable = item.source.startsWith("content://") && item.kind in setOf(MediaKind.PHOTO, MediaKind.GIF, MediaKind.VIDEO)
    val bitmap by produceState(cache.get(item.source).takeIf { previewable }, item.source) {
        if (!previewable || value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            // A revoked permission or a deleted file must not break the editor.
            runCatching { context.contentResolver.loadThumbnail(Uri.parse(item.source), Size(144, 144), null) }.getOrNull()
        }?.also { cache.put(item.source, it) }
    }
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(AppSurfaceAlt), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) {
            Image(b.asImageBitmap(), contentDescription = "Миниатюра ${item.displayName}", modifier = Modifier.size(44.dp), contentScale = ContentScale.Crop)
        } else {
            Icon(fallback, null, tint = AppMuted)
        }
    }
}
