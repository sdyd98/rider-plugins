package com.example.logview

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
import com.intellij.openapi.ui.popup.JBPopupFactory
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.ui.component.Text
import javax.swing.JComponent

private data class LogShortcut(val key: String, val desc: String)

private val LOG_SHORTCUTS = listOf(
    LogShortcut("h j k l", "이동 (h/l 컬럼 · 메시지 안 문자 · Time에서 h=폴더 트리로)"),
    LogShortcut("gt / gT", "다음 / 이전 파일(탭)"),
    LogShortcut("트리: j k h l", "폴더 트리 이동 (l/Enter=열기·펼치기 · h=접기/상위 · gg/G · Ctrl+D/U)"),
    LogShortcut("w / e / b", "메시지 안 단어 앞 / 끝 / 뒤로"),
    LogShortcut("0 / $", "컬럼: 첫/끝 · 메시지: 줄 처음/끝"),
    LogShortcut("v / V", "메시지: 문자 선택 / V: 행 선택 (→ y 복사) · Esc 취소"),
    LogShortcut("y", "복사 (v: 선택 문자 · V: 선택 행 · yy: 현재 행)"),
    LogShortcut("gg / G", "맨 위 / 맨 아래"),
    LogShortcut("5j", "카운트 (5줄 아래로)"),
    LogShortcut("Ctrl+D / U", "반 페이지 아래 / 위"),
    LogShortcut("Ctrl+E / Y", "한 줄 스크롤"),
    LogShortcut("zz / zt / zb", "커서 행을 가운데 / 위 / 아래로"),
    LogShortcut("H / M / L", "화면 위 / 가운데 / 아래 행"),
    LogShortcut("{ / }", "빈 줄(블록 경계)로"),
    LogShortcut("/", "상단 필터로 이동 · Enter로 적용 후 복귀"),
    LogShortcut("* / #", "커서 단어로 검색 (다음 / 이전)"),
    LogShortcut("n / N", "검색 매치 다음 / 이전"),
    LogShortcut("]e / [e", "다음 / 이전 ERROR (e·w·i·d·t = 레벨)"),
    LogShortcut("za", "스택트레이스 접기 / 펴기"),
    LogShortcut("zR / zM", "모두 펴기 / 모두 접기"),
    LogShortcut("m{a-z} / `{a-z}", "마크 설정 / 마크로 이동"),
    LogShortcut("Enter / 더블클릭", "스택 프레임의 소스 파일로 이동"),
    LogShortcut("J", "이 줄의 JSON 정렬해 보기"),
    LogShortcut("T", "시각으로 이동"),
    LogShortcut("?", "이 도움말 · Esc 닫기"),
)

/**
 * Compose (Jewel) shortcut help for the log viewer — Korean, with per-row animated hover. Mirrors the
 * xlsx viewer's `createComposeHelpPanel` so the two plugins' help popups look and feel identical.
 */
fun createLogHelpPanel(): JComponent = JewelComposePanel {
    val p = rememberLogPalette()
    Column(Modifier.width(380.dp).padding(Space.lg)) {
        Text("로그 뷰어 단축키", color = p.text, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        LOG_SHORTCUTS.forEach { logShortcutRow(it.key, it.desc, p) }
    }
}

/** Show the `?` cheat sheet popup centered on [anchor] (shared by the panel and the vim controller). */
fun showLogHelpPopup(anchor: JComponent) {
    JBPopupFactory.getInstance()
        .createComponentPopupBuilder(createLogHelpPanel(), null)
        .setTitle("단축키")
        .setRequestFocus(true)
        .setMovable(true)
        .createPopup()
        .showInCenterOf(anchor)
}

@Composable
private fun logShortcutRow(key: String, desc: String, p: LogPalette) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(if (hovered) p.surfaceHover else Color.Transparent)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.sm))
            .background(background)
            .hoverable(interaction)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(key, Modifier.width(128.dp), color = p.text, fontWeight = FontWeight.Bold)
        Text(desc, color = p.text)
    }
}
