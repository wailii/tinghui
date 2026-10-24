package com.xiaodeng.tinghui

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.ln

/**
 * 录音 + 后台转写都在这个前台服务里。
 * 录音时通知栏必须有一条「正在录音」（安卓规定），录完换成转写进度，转完就消失。
 */
class RecService : Service() {
    companion object {
        const val START = "start"
        const val STOP = "stop"
        const val MARK = "mark"
        const val PAUSE = "pause"
        const val RESUME = "resume"
        const val PROCESS = "process"
        private const val MIN_MS = 3000L
        private const val LEVELS = 64

        /** event：从飞书日历开录的那场会，标题、参会人带进录音 */
        fun start(ctx: Context, event: String? = null) =
            ctx.startForegroundService(Intent(ctx, RecService::class.java).setAction(START).putExtra("event", event))
        fun process(ctx: Context) = ctx.startForegroundService(Intent(ctx, RecService::class.java).setAction(PROCESS))
        fun send(ctx: Context, action: String) = ctx.startService(Intent(ctx, RecService::class.java).setAction(action))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recorder: MediaRecorder? = null
    private var ampJob: Job? = null
    private var workJob: Job? = null
    private var wake: PowerManager.WakeLock? = null
    /** 录音文件自己开，好定时 sync 到磁盘：手机死机、没电关机也只丢最后几秒 */
    private var out: java.io.FileOutputStream? = null
    /** 从什么时候开始麦克风被占（录音时长毫秒），-1 是没被占 */
    private var silencedAt = -1L
    private val recCb = object : android.media.AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>) {
            checkSilenced()
        }
    }
    private var workTitle = ""
    private var workStep = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifs.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> startRecording(intent.getStringExtra("event")?.let { Cal.get(it) })
            STOP -> stopRecording()
            MARK -> mark()
            PAUSE -> pause()
            RESUME -> resume()
            else -> {
                showForeground()
                ensureWork()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // 被系统停掉时把正在录的收个尾，文件是 ADTS，前面录的都还在
        if (recorder != null) stopRecording(quiet = true)
        scope.cancel()
        Live.working.value = false
        super.onDestroy()
    }

    private fun showForeground() {
        val st = Live.state.value
        if (st.recording) {
            startForeground(
                Notifs.NID_FG, Notifs.recording(this, st),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(
                Notifs.NID_FG, Notifs.working(this, workTitle.ifEmpty { "听会" }, workStep.ifEmpty { "准备转写" }),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        }
    }

    private fun refreshNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        val st = Live.state.value
        runCatching {
            nm.notify(
                Notifs.NID_FG,
                if (st.recording) Notifs.recording(this, st)
                else Notifs.working(this, workTitle.ifEmpty { "听会" }, workStep.ifEmpty { "准备转写" }),
            )
        }
    }

    // ---------- 录音 ----------

    private fun startRecording(ev0: Cal.Event? = null) {
        if (recorder != null) {
            showForeground()
            return
        }
        val now = System.currentTimeMillis()
        // 没从日程开录：看日历里这会儿有没有会，有就当成这场（参会人当热词，会前提醒也靠它找上一次）
        val ev = ev0 ?: Cal.events.value.filter { it.end - it.start < 20 * 3600_000L && now in (it.start - 15 * 60_000L)..(it.end - 5 * 60_000L) }
            .minByOrNull { kotlin.math.abs(it.start - now) }
        val id = Store.newId(now)
        Store.put(
            Rec(
                id, ev?.title ?: Store.defaultTitle(now), now, 0, "audio.aac", status = Status.Recording,
                // 日历里的会名是你自己起的，留着不让大模型改
                titleManual = ev0 != null,
                eventUid = ev?.uid ?: "", event = ev?.context() ?: "", hot = ev?.people ?: emptyList(),
            )
        )
        val f = File(Store.dir(id), "audio.aac")
        Live.state.value = LiveState(recId = id, since = SystemClock.elapsedRealtime())
        showForeground()
        try {
            val fos = java.io.FileOutputStream(f).also { out = it }
            recorder = MediaRecorder(this).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioChannels(1)
                setAudioEncodingBitRate(32000)
                setOutputFile(fos.fd)
                // 来电、微信通话、别的 App 在前台用麦克风时，系统不停我们，只给静音：要能看出来
                registerAudioRecordingCallback(mainExecutor, recCb)
                setOnErrorListener { _, _, _ -> onRecorderError() }
                prepare()
                start()
            }
            silencedAt = -1L
        } catch (e: Exception) {
            recorder?.release()
            recorder = null
            runCatching { out?.close() }
            out = null
            Live.state.value = LiveState()
            Store.delete(id)
            Toast.makeText(this, "麦克风打不开，可能被别的 App 占着", Toast.LENGTH_LONG).show()
            stopIfIdle()
            return
        }
        Live.state.value = LiveState(recId = id, since = SystemClock.elapsedRealtime())
        refreshNotification()
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tinghui:rec")
            .apply { acquire(6 * 3600_000L) }
        val buf = FloatArray(LEVELS)
        ampJob = scope.launch {
            var tick = 0
            while (isActive) {
                delay(80)
                val r = recorder ?: break
                val st = Live.state.value
                val amp = if (st.paused) 0 else runCatching { r.maxAmplitude }.getOrDefault(0)
                val lv = (ln(1.0 + amp) / ln(32768.0)).toFloat().coerceIn(0f, 1f)
                System.arraycopy(buf, 1, buf, 0, LEVELS - 1)
                buf[LEVELS - 1] = lv
                Live.levels.value = buf.copyOf()
                if (tick % 12 == 0) checkSilenced()
                if (++tick % 25 == 0) {
                    // 每 2 秒把录音和时长落盘，万一被杀或死机，前面录的都在
                    runCatching { out?.fd?.sync() }
                    val ms = st.elapsed()
                    Store.update(id) { it.copy(durationMs = ms) }
                }
            }
        }
    }

    private fun stopRecording(quiet: Boolean = false) {
        val st = Live.state.value
        val id = st.recId ?: run {
            stopIfIdle()
            return
        }
        val ms = st.elapsed()
        if (st.silenced) closeGap(ms)
        ampJob?.cancel()
        runCatching { recorder?.unregisterAudioRecordingCallback(recCb) }
        runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null
        runCatching { out?.fd?.sync() }
        runCatching { out?.close() }
        out = null
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        Live.state.value = LiveState()
        Live.levels.value = FloatArray(0)
        val r = Store.get(id)
        if (ms < MIN_MS || r == null) {
            Store.delete(id)
            if (!quiet) Toast.makeText(this, "太短了，没保存", Toast.LENGTH_SHORT).show()
            stopIfIdle()
            return
        }
        val real = runCatching { Adts.durationMs(Store.audio(r)) }.getOrDefault(0L).takeIf { it > 0 } ?: ms
        Store.update(id) { it.copy(durationMs = real, status = Status.Queued) }
        if (quiet) return
        workTitle = r.title
        workStep = "准备转写"
        showForeground()
        ensureWork()
    }

    private fun mark() {
        val st = Live.state.value
        val id = st.recId ?: return
        val at = st.elapsed()
        // 暂停时连点会得到同一个时间点，记一次就够
        if (Store.get(id)?.marks?.any { !it.gap && kotlin.math.abs(it.atMs - at) < 500 } == true) return
        Store.update(id) { it.copy(marks = it.marks + Mark(at)) }
        Live.state.value = st.copy(marks = st.marks + 1)
        runCatching {
            getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createOneShot(40, 180))
        }
        refreshNotification()
    }

    private fun checkSilenced() {
        val r = recorder ?: return
        val st = Live.state.value
        if (!st.recording || st.paused) return
        val now = runCatching { r.activeRecordingConfiguration?.isClientSilenced }.getOrNull() ?: return
        if (now == st.silenced) return
        val at = st.elapsed()
        if (now) {
            silencedAt = at
        } else closeGap(at)
        Live.state.value = Live.state.value.copy(silenced = now)
        refreshNotification()
    }

    /** 被占结束：超过 3 秒的记成一段「没录到」 */
    private fun closeGap(at: Long) {
        val from = silencedAt
        silencedAt = -1L
        val id = Live.state.value.recId ?: return
        if (from >= 0 && at - from > 3000) Store.update(id) { it.copy(marks = it.marks + Mark(from, endMs = at)) }
    }

    /** 录音出错（比如存储满了）：已录的保存好，马上告诉你 */
    private fun onRecorderError() {
        val title = Live.state.value.recId?.let { Store.get(it)?.title }
        stopRecording(quiet = false)
        Notifs.alert(this, "录音中断了", "${title ?: "这场录音"}：已录的部分已保存。手机存储可能满了")
    }

    private fun pause() {
        val st = Live.state.value
        if (!st.recording || st.paused) return
        runCatching { recorder?.pause() }
        Live.state.value = st.copy(paused = true, acc = st.elapsed())
        refreshNotification()
    }

    private fun resume() {
        val st = Live.state.value
        if (!st.recording || !st.paused) return
        runCatching { recorder?.resume() }
        Live.state.value = st.copy(paused = false, since = SystemClock.elapsedRealtime())
        refreshNotification()
    }

    // ---------- 转写 ----------

    private fun ensureWork() {
        if (workJob?.isActive == true) return
        Live.working.value = true
        workJob = scope.launch {
            var netFails = 0
            while (isActive) {
                // 先把转写都做完，再一场一场出纪要
                val next = Store.nextPending()
                if (next == null) {
                    val v = Store.nextVoice()
                    if (v != null) {
                        workTitle = v.title
                        workStep = "整理中"
                        if (!Live.state.value.recording) refreshNotification()
                        Pipeline.voice(this@RecService, v.id)
                        continue
                    }
                    val sum = Store.nextSummary() ?: break
                    try {
                        summarizeOne(sum)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Store.update(sum.id) { it.copy(sumState = SumState.Failed, sumError = e.message ?: "出错了") }
                    }
                    continue
                }
                workTitle = next.title
                try {
                    Pipeline.process(this@RecService, next.id) { msg ->
                        Live.setStep(next.id, msg)
                        workStep = msg
                        if (!Live.state.value.recording) refreshNotification()
                    }
                    Live.setStep(next.id, null)
                    netFails = 0
                    // 要出纪要的等纪要好了再一起通知
                    Store.get(next.id)?.let { if (it.sumState != SumState.Queued) Notifs.done(this@RecService, it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (Pipeline.isNetwork(e) && netFails < 20) {
                        netFails++
                        val msg = "网络不通，15 秒后重试（第 $netFails 次）"
                        Live.setStep(next.id, msg)
                        workStep = msg
                        if (!Live.state.value.recording) refreshNotification()
                        delay(15_000)
                        continue
                    }
                    Live.setStep(next.id, null)
                    val msg = if (Pipeline.isNetwork(e)) "网络一直不通" else (e.message ?: e.javaClass.simpleName)
                    Store.update(next.id) { it.copy(status = Status.Failed, error = msg) }
                    Notifs.failed(this@RecService, Store.get(next.id), msg)
                }
            }
            Live.working.value = false
            workTitle = ""
            workStep = ""
            if (recorder == null) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /** 出一场纪要：失败了记下原因，不影响看稿 */
    private suspend fun summarizeOne(r: Rec) {
        workTitle = r.title
        workStep = "整理纪要"
        Live.setStep(r.id, "正在整理纪要")
        Store.update(r.id) { it.copy(sumState = SumState.Working, sumError = null) }
        if (!Live.state.value.recording) refreshNotification()
        val res = try {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching {
                    Llm.summarize(r.id) { step ->
                        Live.setStep(r.id, step)
                        workStep = step
                    }
                }
            }
        } catch (e: CancellationException) {
            // 服务被停：下次接着来
            Store.update(r.id) { it.copy(sumState = SumState.Queued) }
            Live.setStep(r.id, null)
            throw e
        }
        Live.setStep(r.id, null)
        res.exceptionOrNull()?.let { e ->
            Store.update(r.id) { it.copy(sumState = SumState.Failed, sumError = e.message ?: e.javaClass.simpleName) }
        }
        if (res.isSuccess) {
            workStep = "存到飞书"
            Live.setStep(r.id, "存到飞书")
            kotlinx.coroutines.withContext(Dispatchers.IO) { AfterMinutes.run(this@RecService, r.id) }
            Live.setStep(r.id, null)
        }
        Notifs.done(this, Store.get(r.id))
    }

    private fun stopIfIdle() {
        if (recorder == null && workJob?.isActive != true) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }
}
