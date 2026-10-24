package com.xiaodeng.tinghui

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 纪要出来以后自动做的事（存飞书、发你、建任务），和通知上点的按钮（加到飞书任务）
 */
object AfterMinutes {
    /** 纪要出来后：存到飞书、发「纪要好了」给你、按设置把你的待办建成任务 */
    fun run(ctx: Context, id: String) {
        val f = Prefs.feishu.value
        val push = Prefs.push.value
        if (f.ready) {
            if (push.autoSync) runCatching { FeishuSync.sync(id) }.onFailure { e ->
                Store.update(id) { it.copy(fs = it.fs.copy(error = e.message ?: "没存上")) }
            }
            val r = Store.get(id)
            if (r != null && push.autoTasks) runCatching { FeishuSync.createTasks(id, r.todos.filter { it.mine }.map { it.key }) }
            if (push.after) Store.get(id)?.let { runCatching { FeishuPush.after(it) } }
        }
        runCatching { Outbound.refreshStyleIfStale() }
    }

    const val ACT_TASKS = "com.xiaodeng.tinghui.ADD_TASKS"

    fun tasksIntent(ctx: Context, id: String): PendingIntent = PendingIntent.getBroadcast(
        ctx, ("t$id").hashCode(),
        Intent(ctx, ActionReceiver::class.java).setAction(ACT_TASKS).putExtra("rec", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** 通知上的「加到飞书任务」：不打开 App，后台建好再告诉你 */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != AfterMinutes.ACT_TASKS) return
        val id = intent.getStringExtra("rec") ?: return
        val pending = goAsync()
        kotlin.concurrent.thread {
            try {
                val r = Store.get(id) ?: return@thread
                val n = runCatching { FeishuSync.createTasks(id, r.todos.filter { it.mine }.map { it.key }) }
                ctx.getSystemService(android.app.NotificationManager::class.java).cancel(id.hashCode())
                Notifs.alert(
                    ctx,
                    if (n.isSuccess) "已加 ${n.getOrDefault(0)} 条到飞书任务" else "没加上",
                    if (n.isSuccess) "在「会议待办」清单里" else (n.exceptionOrNull()?.message ?: "飞书出错了"),
                )
            } finally {
                pending.finish()
            }
        }
    }
}
