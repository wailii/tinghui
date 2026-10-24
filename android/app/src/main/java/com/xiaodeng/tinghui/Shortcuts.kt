package com.xiaodeng.tinghui

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon

/**
 * 长按桌面图标弹出来的菜单：开始录音、我的待办、问 AI 是固定的（res/xml/shortcuts.xml）；
 * 这里按日程加一项「录：下一场会」
 */
object Shortcuts {
    const val ACTION_OPEN = "com.xiaodeng.tinghui.OPEN"

    fun update(ctx: Context) {
        val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val list = ArrayList<ShortcutInfo>()
        val recs = Store.recs.value
        Cal.events.value.firstOrNull { e -> e.end > now && e.start - now < 12 * 3600_000L && recs.none { it.eventUid == e.uid } }?.let { e ->
            list += ShortcutInfo.Builder(ctx, "next")
                .setShortLabel("录：${e.title}".take(12))
                .setLongLabel("录：${e.title}".take(25))
                .setIcon(Icon.createWithResource(ctx, R.drawable.sc_meeting))
                .setIntent(
                    Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_RECORD)
                        .putExtra(MainActivity.EXTRA_EVENT, e.uid)
                )
                .setRank(0)
                .build()
        }
        runCatching { sm.dynamicShortcuts = list }
    }
}
