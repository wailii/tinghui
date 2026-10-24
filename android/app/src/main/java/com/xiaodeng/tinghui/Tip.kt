package com.xiaodeng.tinghui

import kotlinx.coroutines.flow.MutableStateFlow

/** 页面顶部弹一条黑色小提示，可以带一个按钮。系统 Toast 会挡住底部播放条，所以自己画 */
object Tip {
    data class Msg(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null, val at: Long = System.nanoTime())

    val msg = MutableStateFlow<Msg?>(null)

    fun show(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        msg.value = Msg(text, action, onAction)
    }
}
