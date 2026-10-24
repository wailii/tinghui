package com.xiaodeng.tinghui.ui

import androidx.compose.ui.graphics.Color
import com.xiaodeng.tinghui.Speaker
import com.xiaodeng.tinghui.Voice

object Palette {
    val Bg = Color(0xFFF6F5F1)
    val Card = Color(0xFFFFFFFF)
    val Ink = Color(0xFF121212)
    val Sub = Color(0xFF8C8A84)
    val Faint = Color(0xFFEFEDE7)
    val Line = Color(0xFFE6E4DE)
    val Hint = Color(0xFFC9C7C0)
    val Accent = Color(0xFFFF5A1F)
    val AccentSoft = Color(0xFFFFEDE4)
    val AccentText = Color(0xFFB0573A)
    val Good = Color(0xFF1F9D55)
    val Island = Color(0xFF111111)
    val IslandButton = Color(0xFF2B2B2B)
    val IslandSub = Color(0xFF9A9893)
    val Rec = Color(0xFFFF3B30)

    /** 认得的人：颜色跟着人走，同一个人在哪场会里都是同一个颜色 */
    private val people = listOf(
        Color(0xFFFF5A1F), Color(0xFF2F6BFF), Color(0xFF1F9D55), Color(0xFF9B51E0),
        Color(0xFFD4A000), Color(0xFF0FA3A3), Color(0xFFD6336C), Color(0xFF3F51B5),
    )

    /** 还没认出来的：灰一档的颜色，一眼看出「这个还没起名」 */
    private val strangers = listOf(
        Color(0xFF8E8E93), Color(0xFFA1887F), Color(0xFF7D93AE), Color(0xFFA48FC0), Color(0xFF7FA89A),
    )

    fun speaker(s: Speaker?, idx: Int): Color {
        val pid = s?.personId
        if (pid != null) {
            val i = Voice.people.value.indexOfFirst { it.id == pid }
            if (i >= 0) return people[i % people.size]
        }
        return strangers[((idx % strangers.size) + strangers.size) % strangers.size]
    }
}
