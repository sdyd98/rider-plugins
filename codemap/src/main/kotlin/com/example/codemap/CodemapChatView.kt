package com.example.codemap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

private val PAD = Space.lg

/**
 * The conversation: what was said, and the one thing you can do with an answer besides read it.
 *
 * Deliberately plain. There is no markdown renderer, no syntax highlighting, no avatars — a terminal already
 * does all of that better, and the reason to be here instead of a terminal is the note beside it. Every
 * pixel spent imitating a chat app is a pixel not spent on that.
 */
@Composable
fun CodemapChatView(vm: ChatViewModel) {
    val p = rememberCodemapPalette()
    val input = remember { TextFieldState() }
    val focus = remember { FocusRequester() }
    val scroll = rememberScrollState()

    LaunchedEffect(Unit) { focus.requestFocus() }
    // Follow the conversation as it grows; a chat that leaves you scrolled to the top is a chat you fight.
    LaunchedEffect(vm.turns.size, vm.running) { scroll.animateScrollTo(scroll.maxValue) }

    Column(Modifier.fillMaxSize()) {
        Header(vm, p)
        Rule(p)

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = PAD),
        ) {
            if (vm.turns.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(vertical = Space.xl)) {
                    EmptyState(
                        "💬",
                        "이 파일에 대해 물어보세요",
                        "노트가 이미 문맥으로 들어갑니다. 답이 쓸 만하면 노트에 남길 수 있습니다.",
                        p,
                    )
                }
            }
            vm.turns.forEachIndexed { i, turn -> TurnBlock(turn, i, vm, p) }
            if (vm.running) {
                Row(Modifier.padding(vertical = Space.sm)) {
                    Working("${vm.engine.label} 생각 중", p, step = vm.step) { vm.cancel() }
                }
            }
            vm.writing?.let { what ->
                Row(Modifier.padding(vertical = Space.sm)) {
                    Working(what, p, step = vm.step) { vm.cancel() }
                }
            }
            vm.wrote?.let {
                Column(Modifier.padding(vertical = Space.sm)) { Banner("✓ $it", p.accent, p) }
            }
            vm.error?.let { Column(Modifier.padding(vertical = Space.sm)) { Banner("실패 — $it", p.warn, p) } }
            Box(Modifier.padding(bottom = Space.md))
        }

        Rule(p)
        Composer(input, focus, vm, p)
    }
}

@Composable
private fun Header(vm: ChatViewModel, p: CodemapPalette) {
    Row(
        Modifier.fillMaxWidth().padding(start = PAD, end = PAD, top = Space.md, bottom = Space.sm),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(vm.fileName, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "노트를 문맥으로 ${vm.engine.label} 와 대화 — 소스는 읽기만 합니다",
                color = p.mutedText,
                fontSize = Type.micro,
            )
        }
        if (vm.turns.isNotEmpty()) {
            // The conversation is where the understanding happened; this is how it stops being disposable.
            HintedButton(
                "이 대화로 노트 갱신",
                "지금까지의 대화를 문맥으로 붙여 구조화된 분석을 돌리고 노트를 다시 씁니다 — 사람이 고친 부분은 보존됩니다",
                p,
                enabled = vm.writing == null && !vm.running,
                primary = true,
            ) { vm.updateNote() }
            ActionButton("새 대화", p, primary = false) { vm.clear() }
        }
    }
}

/** One exchange. The assistant's side carries the actions, because that is the side worth keeping. */
@Composable
private fun TurnBlock(turn: Chat.Turn, index: Int, vm: ChatViewModel, p: CodemapPalette) {
    val user = turn.role == Chat.Role.USER
    Column(Modifier.fillMaxWidth().padding(top = if (index == 0) Space.md else Space.lg)) {
        Text(
            if (user) "나" else vm.engine.label,
            color = if (user) p.accent else p.mutedText,
            fontSize = Type.micro,
            fontWeight = FontWeight.Medium,
        )
        Box(
            Modifier.fillMaxWidth().padding(top = Space.xxs)
                .background(if (user) p.subtle else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(Radii.sm))
                .padding(if (user) Space.sm else 0.dp),
        ) {
            Text(turn.text, color = p.text, fontSize = Type.body, lineHeight = 19.sp)
        }
        if (!user) PinRow(turn.text, vm, p)
    }
}

/**
 * Keeping an answer.
 *
 * The two shapes a note actually has room for: a trap, and a function's one-liner. Anything longer belongs
 * in the conversation, not in a note that has to stay scannable — so the button says what it will do and
 * the developer decides whether the answer is that shape.
 */
@Composable
private fun PinRow(text: String, vm: ChatViewModel, p: CodemapPalette) {
    var picking by remember { mutableStateOf(false) }
    val functions = remember(vm.revision, picking) { vm.functionNames() }

    Row(
        Modifier.padding(top = Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HintedButton("주의로 추가", "이 답을 노트의 주의에 남깁니다 — 사람이 쓴 것으로 기록되어 재분석에도 남습니다", p) {
            vm.pinAsGotcha(text)
        }
        if (functions.isNotEmpty()) {
            if (picking) {
                Picker(
                    label = "함수 설명으로",
                    items = functions.map { PickerItem(it) },
                    selected = 0,
                    palette = p,
                ) { i ->
                    vm.pinAsPurpose(functions[i], text)
                    picking = false
                }
            } else {
                HintedButton("함수 설명으로", "이 답을 한 함수의 한 줄 설명으로 저장합니다", p) { picking = true }
            }
        }
    }
}

/** The input. Enter sends, ⇧Enter and ⌘Enter make a new line. */
@Composable
private fun Composer(input: TextFieldState, focus: FocusRequester, vm: ChatViewModel, p: CodemapPalette) {
    fun send() {
        val q = input.text.toString()
        if (q.isBlank() || vm.running) return
        vm.ask(q)
        input.clearText()
    }

    Column(Modifier.fillMaxWidth().padding(PAD)) {
        TextField(
            state = input,
            placeholder = { Text("질문 — Enter 로 보내기, ⇧Enter 로 줄바꿈") },
            modifier = Modifier.fillMaxWidth().widthIn(max = 900.dp)
                .focusRequester(focus)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        e.key != Key.Enter && e.key != Key.NumPadEnter -> false
                        e.isShiftPressed || e.isMetaPressed -> false
                        else -> { send(); true }
                    }
                },
        )
        Row(
            Modifier.fillMaxWidth().padding(top = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionButton("보내기", p, enabled = !vm.running) { send() }
            if (vm.running) ActionButton("중단", p, primary = false) { vm.cancel() }
            Box(Modifier.weight(1f))
            Text(
                "대화는 이 탭에만 남습니다 — 노트에 남기려면 답 아래 버튼을",
                color = p.mutedText,
                fontSize = Type.micro,
            )
        }
    }
}
