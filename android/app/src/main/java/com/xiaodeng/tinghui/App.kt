package com.xiaodeng.tinghui

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Voice.init(this)
        Prefs.init(this)
        Store.init(this)
        Ask.init(this)
        Cal.init(this)
        Notifs.ensureChannels(this)
        recoverCrashed()
        // 上次整理纪要到一半被杀的，重新排队
        Store.recs.value.filter { it.sumState == SumState.Working }.forEach { r ->
            Store.update(r.id) { it.copy(sumState = SumState.Queued) }
        }
        Store.recs.value.filter { it.status == Status.Done && it.preview.isBlank() && it.sentenceCount > 0 }.forEach { r ->
            val text = Store.sentences(r.id).joinToString("") { it.text }.take(90)
            Store.update(r.id) { it.copy(preview = text) }
        }
        // 声纹库里有的人，旧录音里没认出来的再对一遍
        kotlin.concurrent.thread { runCatching { Store.rematch() } }
    }

    /** 进程刚起来还标着「录音中」的，是上次录到一半被杀了：ADTS 文件前面的都能用，收尾后排队转写 */
    private fun recoverCrashed() {
        Store.recs.value.filter { it.status == Status.Recording }.forEach { r ->
            val ms = runCatching { Adts.durationMs(Store.audio(r)) }.getOrDefault(0L)
            if (ms < 3000) Store.delete(r.id)
            else Store.update(r.id) { it.copy(durationMs = ms, status = Status.Queued) }
        }
    }
}
