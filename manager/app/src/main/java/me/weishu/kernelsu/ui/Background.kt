package me.weishu.kernelsu.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import me.weishu.kernelsu.R

/**
 * 固定的内置背景：图片放在 res/drawable-nodpi/app_bg.jpg，用户不能更改。
 */
@Composable
fun CustomBackground(scrim: Color) {
    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.app_bg),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(Modifier.fillMaxSize().background(scrim)) // 遮罩，保证文字清晰
    }
}

// 始终有背景，页面底色一律透明
fun materialPageColor(): Color = Color.Transparent

fun miuixPageColor(): Color = Color.Transparent
