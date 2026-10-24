package com.xiaodeng.tinghui.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.xiaodeng.tinghui.R
import kotlinx.coroutines.delay

private val FRAMES = intArrayOf(R.drawable.xw_0, R.drawable.xw_1, R.drawable.xw_2)

/** 小灯：线稿气泡戴耳机。三版手画线每 0.14 秒换一版，像逐帧手绘在抖；wobble=false 就停在第一版 */
@Composable
fun XiaoWaiLi(size: Dp, modifier: Modifier = Modifier, wobble: Boolean = true) {
    var f by remember { mutableIntStateOf(0) }
    if (wobble) LaunchedEffect(Unit) {
        while (true) {
            delay(140)
            f = (f + 1) % FRAMES.size
        }
    }
    Image(painterResource(FRAMES[f]), com.xiaodeng.tinghui.BuildConfig.ASSISTANT, modifier.size(size))
}
