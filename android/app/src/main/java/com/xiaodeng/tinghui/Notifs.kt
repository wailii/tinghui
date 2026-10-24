package com.xiaodeng.tinghui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object Notifs {
    const val CH_REC = "rec"
    const val CH_DONE = "done"
    const val CH_MEET = "meet"
    const val NID_FG = 1

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_REC, "录音和转写进度", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "转写好了", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            enableVibration(false)
        })
        // 开会提醒要弹出来、响一下
        nm.createNotificationChannel(NotificationChannel(CH_MEET, "开会提醒", NotificationManager.IMPORTANCE_HIGH))
    }

    fun openApp(ctx: Context, recId: String? = null): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (recId != null) i.putExtra(MainActivity.EXTRA_REC, recId)
        return PendingIntent.getActivity(ctx, recId?.hashCode() ?: 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun action(ctx: Context, act: String, code: Int): PendingIntent =
        PendingIntent.getService(ctx, code, Intent(ctx, RecService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE)

    fun recording(ctx: Context, st: LiveState): Notification {
        val b = Notification.Builder(ctx, CH_REC)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(openApp(ctx))
            .setColor(0xFFFF5A1F.toInt())
        if (st.silenced && !st.paused) {
            b.setContentTitle("麦克风被占用，暂时录不到").setUsesChronometer(false).setShowWhen(false)
        } else if (st.paused) {
            b.setContentTitle("已暂停 · ${clock(st.elapsed())}").setUsesChronometer(false).setShowWhen(false)
        } else {
            b.setContentTitle("听会正在录音")
                .setUsesChronometer(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis() - st.elapsed())
        }
        b.setContentText(if (st.marks > 0) "已标记 ${st.marks} 处" else "点「标记」记下重要的地方")
        b.addAction(Notification.Action.Builder(null, "标记", action(ctx, RecService.MARK, 1)).build())
        b.addAction(
            if (st.paused) Notification.Action.Builder(null, "继续", action(ctx, RecService.RESUME, 2)).build()
            else Notification.Action.Builder(null, "暂停", action(ctx, RecService.PAUSE, 3)).build()
        )
        b.addAction(Notification.Action.Builder(null, "结束", action(ctx, RecService.STOP, 4)).build())
        return b.build()
    }

    fun working(ctx: Context, title: String, step: String): Notification =
        Notification.Builder(ctx, CH_REC)
            .setSmallIcon(R.drawable.ic_stat_done)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(title)
            .setContentText(step)
            .setProgress(0, 0, true)
            .setContentIntent(openApp(ctx))
            .build()

    fun done(ctx: Context, r: Rec?) {
        r ?: return
        val text = buildString {
            append("${clock(r.durationMs)} · ${r.speakers.size} 人说话")
            if (r.folder != null) append(" · ${r.folder}")
            if (r.sumState == SumState.Failed) append(" · 纪要没出来")
        }
        val mine = r.todos.count { it.mine && it.task.isBlank() && !it.done }
        val done = r.sumState == SumState.Done && r.summary.isNotBlank()
        val n = Notification.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_done)
            .setContentTitle(if (done) "纪要好了：${r.title}" else "转写好了：${r.title}")
            .setContentText(if (done && r.todos.isNotEmpty()) "${r.todos.count { it.mine }} 条是你的 · " + text else text)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, r.id))
        // 你的待办还没进飞书任务：通知上直接点
        if (done && mine > 0 && Prefs.feishu.value.ready) {
            n.addAction(Notification.Action.Builder(null, "加到飞书任务", AfterMinutes.tasksIntent(ctx, r.id)).build())
            n.addAction(Notification.Action.Builder(null, "看纪要", openApp(ctx, r.id)).build())
        }
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(r.id.hashCode(), n.build()) }
    }

    fun alert(ctx: Context, title: String, text: String) {
        val n = Notification.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(title.hashCode(), n) }
    }

    /** 飞书日历里的会快开始了：点「开始录音」直接开录，标题和参会人带进去 */
    fun meeting(ctx: Context, e: Cal.Event) {
        val rec = Intent(ctx, MainActivity::class.java)
            .setAction(MainActivity.ACTION_RECORD)
            .putExtra(MainActivity.EXTRA_EVENT, e.uid)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pRec = PendingIntent.getActivity(ctx, e.uid.hashCode(), rec, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val mins = ((e.start - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
        val n = Notification.Builder(ctx, CH_MEET)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle(if (mins > 0) "$mins 分钟后：${e.title}" else "开始了：${e.title}")
            .setContentText(buildString {
                append("${fmt.format(e.start)}–${fmt.format(e.end)}")
                if (e.people.isNotEmpty()) append(" · ${e.people.take(4).joinToString("、")}${if (e.people.size > 4) " 等 ${e.people.size} 人" else ""}")
            })
            .setCategory(Notification.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setTimeoutAfter(e.end - System.currentTimeMillis())
            .setContentIntent(openApp(ctx))
            .setColor(0xFFFF5A1F.toInt())
            .addAction(Notification.Action.Builder(null, "开始录音", pRec).build())
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(e.uid.hashCode(), n) }
    }

    fun failed(ctx: Context, r: Rec?, msg: String) {
        r ?: return
        post(ctx, r, "没转成：${r.title}", "$msg。点开重试")
    }

    private fun post(ctx: Context, r: Rec, title: String, text: String) {
        val n = Notification.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, r.id))
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(r.id.hashCode(), n) }
    }
}

fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}
