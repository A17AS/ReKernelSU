package me.weishu.kernelsu.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import kotlin.math.max

object BgState {
    var bitmap by mutableStateOf<ImageBitmap?>(null)
        private set
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.filesDir, "custom_bg.jpg")

    suspend fun load(ctx: Context, force: Boolean = false) {
        if (loaded && !force) return
        bitmap = withContext(Dispatchers.IO) { decode(file(ctx)) }
        loaded = true
    }

    suspend fun save(ctx: Context, uri: Uri) {
        withContext(Dispatchers.IO) {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                file(ctx).outputStream().use { input.copyTo(it) }
            }
        }
        load(ctx, force = true)
    }

    suspend fun clear(ctx: Context) {
        withContext(Dispatchers.IO) { file(ctx).delete() }
        bitmap = null
    }

    private fun decode(f: File, maxSide: Int = 1920): ImageBitmap? {
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        return BitmapFactory.decodeFile(
            f.path,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )?.asImageBitmap()
    }
}

@Composable
fun CustomBackground(scrim: Color) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { BgState.load(ctx) }
    val bg = BgState.bitmap ?: return
    Box(Modifier.fillMaxSize()) {
        Image(
            bitmap = bg,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(Modifier.fillMaxSize().background(scrim)) // 遮罩，保证文字清晰
    }
}

@Composable
fun materialPageColor(): Color =
    if (BgState.bitmap != null) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer

@Composable
fun miuixPageColor(): Color =
    if (BgState.bitmap != null) Color.Transparent else MiuixTheme.colorScheme.surface
