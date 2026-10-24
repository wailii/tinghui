package com.xiaodeng.tinghui

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** 下拉快捷开关：点一下开录，再点一下结束 */
class RecTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val rec = Live.state.value.recording
        tile.state = if (rec) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (rec) "结束录音" else "听会录音"
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (Live.state.value.recording) {
            RecService.send(this, RecService.STOP)
            qsTile?.apply {
                state = Tile.STATE_INACTIVE
                label = "听会录音"
                updateTile()
            }
            return
        }
        // 开麦克风要 App 在前台，所以先把 App 拉起来，由它开录
        val i = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_RECORD)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 7, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
