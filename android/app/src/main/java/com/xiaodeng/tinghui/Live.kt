package com.xiaodeng.tinghui

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** 正在录的这场：时长 = acc + (没暂停时)从 since 到现在 */
data class LiveState(
    val recId: String? = null,
    val since: Long = 0,
    val acc: Long = 0,
    val paused: Boolean = false,
    val marks: Int = 0,
    /** 麦克风被通话或别的 App 占着，这会儿录到的是静音 */
    val silenced: Boolean = false,
) {
    val recording get() = recId != null
    fun elapsed(now: Long = SystemClock.elapsedRealtime()) = acc + if (paused || recId == null) 0 else now - since
}

object Live {
    val state = MutableStateFlow(LiveState())

    /** 最近一段时间的音量（0~1），画波形用 */
    val levels = MutableStateFlow(FloatArray(0))

    /** 后台正在跑的转写，每场录音当前在干嘛 */
    private val _steps = MutableStateFlow<Map<String, String>>(emptyMap())
    val steps: StateFlow<Map<String, String>> = _steps

    val working = MutableStateFlow(false)

    fun setStep(id: String, s: String?) {
        _steps.update { if (s == null) it - id else it + (id to s) }
    }
}
