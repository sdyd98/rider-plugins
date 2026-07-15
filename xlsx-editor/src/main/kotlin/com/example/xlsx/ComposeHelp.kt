package com.example.xlsx

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.ui.component.Text
import javax.swing.JComponent

private data class Shortcut(val key: String, val desc: String)

private val SHORTCUTS = listOf(
    Shortcut("Alt+Shift+F", "헤더 행 고정 / 해제"),
    Shortcut("Alt+K", "고정된 헤더 중 컬럼 이름으로 쓸 키 행 변경"),
    Shortcut("Alt+\\", "컬럼 목록(키 행 이름) → 선택한 열로 이동"),
    Shortcut("Ctrl+Alt+F", "현재 컬럼 값으로 필터"),
    Shortcut("Ctrl+R", "현재 행을 관계도(데이터 연결)에서 보기"),
    Shortcut("Ctrl+F", "현재 테이블을 관계도(테이블 관계도)에서 보기"),
    Shortcut("/", "상단 필터(정규식)로 이동 · Enter로 적용 후 복귀"),
    Shortcut("Alt+H", "필터 ↔ 하이라이트 모드 전환 — 행을 숨기지 않고 매치 셀만 표시, n/N으로 이동"),
    Shortcut("Alt+S", "시트 점프 팝업 — 타이핑으로 시트 이름 필터, Enter로 이동"),
    Shortcut("?", "이 도움말 · Esc 닫기"),
)

private val hoverTint = Color(0xFF3574F0).copy(alpha = 0.16f)

/**
 * Compose (Jewel) shortcut help. The Compose-specific touch over the old Swing JLabel: **per-row
 * hover** with an animated background color, declared with no mouse-listener / repaint bookkeeping.
 */
fun createComposeHelpPanel(): JComponent = JewelComposePanel {
    Column(Modifier.width(360.dp).padding(16.dp)) {
        Text("단축키", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        SHORTCUTS.forEach { shortcutRow(it.key, it.desc) }
    }
}

@Composable
private fun shortcutRow(key: String, desc: String) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(if (hovered) hoverTint else Color.Transparent)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .hoverable(interaction)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(key, Modifier.width(104.dp), fontWeight = FontWeight.Bold)
        Text(desc)
    }
}
