package com.example.codemap

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/**
 * The 코드맵 tool window content: the note for the selected file, or — far more often on a codebase
 * this size — the exact facts about it plus a way to ask for an analysis.
 *
 * The note is read as loose JSON on purpose (see [NoteStore]): every section renders only if the AI
 * actually filled it in, and a key the plugin doesn't know about is simply not shown rather than being
 * an error. Sections appear in reading order — what it is, where to start reading, the things that bite
 * (threading, packets), then how it connects outward, and finally the traps.
 */
@Composable
fun CodemapView(vm: CodemapViewModel) {
    val p = rememberCodemapPalette()
    val question = remember { TextFieldState() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.md, vertical = Space.md),
    ) {
        when (val s = vm.state) {
            is CodemapState.NoFile ->
                EmptyState("🗺", "열린 파일이 없습니다", "에디터에서 파일을 열면 그 파일의 코드맵을 보여줍니다.", p)

            is CodemapState.Outside ->
                EmptyState("↗", "코드맵 루트 밖의 파일", "루트: ${s.root.ifEmpty { "(찾을 수 없음)" }}", p)

            is CodemapState.Loading ->
                Text(s.name, color = p.mutedText, fontSize = Type.label)

            is CodemapState.Loaded -> LoadedView(s, vm, question, p)
        }
    }
}

@Composable
private fun LoadedView(
    s: CodemapState.Loaded,
    vm: CodemapViewModel,
    question: TextFieldState,
    p: CodemapPalette,
) {
    Header(s, vm, p)
    Rule(p)

    val note = s.note
    if (note == null) {
        EmptyState("📝", "아직 분석되지 않았습니다", "궁금한 점을 적고 분석을 요청하세요.", p)
    } else {
        NoteSections(note, s, vm, p)
    }

    Rule(p, top = Space.lg)
    RequestBox(s, vm, question, p)
}

// ---- header ----

@Composable
private fun Header(s: CodemapState.Loaded, vm: CodemapViewModel, p: CodemapPalette) {
    Text(s.name, color = p.text, fontSize = Type.title, fontWeight = FontWeight.SemiBold)
    if (s.dir.isNotEmpty()) Text(s.dir, color = p.mutedText, fontSize = Type.label)

    Row(
        Modifier.fillMaxWidth().padding(top = Space.sm),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (s.freshness) {
            NoteStore.Freshness.FRESH -> Chip("분석됨", p.accent, p)
            NoteStore.Freshness.STALE -> Chip("오래됨", p.warn, p)
            NoteStore.Freshness.NO_NOTE -> Chip("미분석", p.mutedText, p)
            NoteStore.Freshness.UNKNOWN -> Chip("확인 불가", p.mutedText, p)
        }
        // A note someone has corrected by hand reads differently — and those corrections now survive a
        // re-analysis, so it matters that you can see there are some.
        if (s.edited) Chip("수정됨", p.mutedText, p)
    }

    // The one thing that must be impossible to miss when a note has drifted from the code — and the fix is
    // one press away, because a warning you have to act on elsewhere is a warning people learn to ignore.
    if (s.freshness == NoteStore.Freshness.STALE) {
        val at = s.note?.string("analyzedAt").orEmpty()
        val drift = s.commitsSince?.let { ", 이후 커밋 %,d개".format(it) }.orEmpty()
        Column(Modifier.padding(top = Space.sm)) {
            Banner("⚠ 분석 이후 코드가 변경됨 — $at 분석$drift", p.warn, p)
            if (vm.analysis !is CodemapViewModel.Analysis.Running) {
                Row(Modifier.padding(top = Space.xs)) {
                    ActionButton("지금 재분석", p, primary = false) { vm.analyzeNow("") }
                }
            }
        }
    }
}


// ---- the note ----

@Composable
private fun NoteSections(note: JsonObject, s: CodemapState.Loaded, vm: CodemapViewModel, p: CodemapPalette) {
    // ---- nothing is shown by default ----
    // The panel opens empty on purpose. What you actually need arrives from the caret — the function
    // you are reading — and anything else is a question you have not asked yet, one click away below.
    // Sections earned their way back onto this screen by being asked for, not by being available.

    // ---- follows the caret ----
    CaretFocus(note, s, vm, p)

    // ---- one click away ----
    // What earned its way back onto the screen: the traps (always open), the packets this file handles, its
    // thread and lock model, the function index, and the sequences someone asked for. The rest of the note
    // (요약 · 진입점 · 클래스 · 핵심 상태 · 의존 · 데이터 연결) is not drawn at all — it was carried for a
    // while behind a fold nobody opened, and code kept for "maybe" rots. The data is still in the note and
    // the MCP tools still read it; if one of them turns out to be wanted, it comes back as its own section
    // like these did.
    Warnings(note, vm, p)
    Packets(note, s, vm, p)
    Threading(note, p)
    Functions(note, s, vm, p)
    Sequences(note, s, vm, p)

    note.string("analyzedAt")?.let {
        Text("$it 분석", color = p.mutedText, fontSize = Type.micro, modifier = Modifier.padding(top = Space.md))
    }
}

/** The function the caret is in, pulled out of the list and shown in full. */
@Composable
private fun CaretFocus(note: JsonObject, s: CodemapState.Loaded, vm: CodemapViewModel, p: CodemapPalette) {
    val name = vm.focusedFunction ?: return
    val f = note.objects("functions").firstOrNull { it.string("name") == name } ?: return
    FocusCard(name, p) {
        Editable(
            f.string("purpose").orEmpty(),
            p,
            fontSize = Type.label,
            onSave = { v -> vm.editFunction(name, "purpose", v) },
        )
        f.string("thread")?.let { AttrRow("스레드", it, p) }
        FunctionDetail(f, p, indent = false, showGotchas = false)

        // Only the graph. Rider's own Find Usages (Alt+F7) already answers "who calls this" better than
        // a list here could — with grouping, filters and a preview — and the caret is on the function
        // already. The exhaustive answer earns a button only in the graph tab, where the graph itself is
        // notes-only and the contrast between the two is the point.
        Row(
            Modifier.padding(top = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            ActionButton("호출 그래프", p, primary = false) { vm.openGraph(name) }
            // A 6,000-line file costs minutes to re-analyze whole. The prompt has always taken a function
            // scope; until now nothing in the UI could reach it.
            if (vm.analysis !is CodemapViewModel.Analysis.Running) {
                ActionButton("이 함수만 재분석", p, primary = false) { vm.analyzeNow("", symbol = name) }
            }
        }

        EditableList(
            f.strings("gotchas"),
            p,
            addLabel = "＋ 이 함수 주의 추가",
            onChange = { list -> vm.editFunctionList(name, "gotchas", list) },
        ) { text -> Text("⚠ $text", color = p.warn, fontSize = Type.micro, lineHeight = 15.sp) }
    }
}

/**
 * An editable list of plain strings: each item corrects or deletes in place, plus one add affordance.
 * The whole list is handed back on every change so the caller writes it in one shot — these lists are a
 * handful of sentences, and a patch protocol would be more moving parts than the data deserves.
 */
@Composable
private fun EditableList(
    values: List<String>,
    p: CodemapPalette,
    addLabel: String,
    onChange: (List<String>) -> Unit,
    render: @Composable (String) -> Unit,
) {
    var adding by remember(values) { mutableStateOf(false) }

    values.forEachIndexed { i, v ->
        Editable(
            v,
            p,
            fontSize = Type.label,
            display = render,
            onSave = { next -> onChange(values.toMutableList().also { it[i] = next }) },
            onDelete = { onChange(values.filterIndexed { j, _ -> j != i }) },
        )
    }
    if (adding) {
        Editable("", p, fontSize = Type.label, placeholder = "새 항목", onSave = { next ->
            if (next.isNotEmpty()) onChange(values + next)
            adding = false
        })
    } else {
        Row(Modifier.padding(top = Space.xs)) {
            ActionButton(addLabel, p, primary = false) { adding = true }
        }
    }
}

/** The recorded function with this name, so an edit can reach into the array by identity not index. */
private fun JsonObject.function(name: String): JsonObject? =
    (get("functions") as? JsonArray)?.mapNotNull { it as? JsonObject }
        ?.firstOrNull { it.string("name") == name }

private fun List<String>.toJsonArray(): JsonArray = JsonArray().also { arr -> forEach(arr::add) }

/**
 * Everything the file records beyond the three essentials. Closed by default — these answer questions
 * you have not asked yet, and eleven open sections read as noise however well each one is written.
 */
/**
 * The traps, in amber, always open.
 *
 * The one section that does not wait to be asked for. Everything else on this screen answers a question
 * someone went looking for; this one is the answer to a question they did not know to ask, and a fold would
 * hide it exactly when it matters.
 */
@Composable
private fun Warnings(note: JsonObject, vm: CodemapViewModel, p: CodemapPalette) {
    val gotchas = note.strings("gotchas")
    if (gotchas.isEmpty()) return
    SectionHeader("주의", p, color = p.warn)
    EditableList(
        gotchas,
        p,
        addLabel = "＋ 주의 추가",
        onChange = { list -> vm.editList("gotchas", list) },
    ) { text -> WarnCard(text, p) }
}

/**
 * Which thread runs this, and what it locks.
 *
 * One of the two axes named as important from the start, and the one a reader cannot recover by looking at
 * the code for a minute — lock order in particular is a property of the whole system, not of this file.
 */
@Composable
private fun Threading(note: JsonObject, p: CodemapPalette) {
    val threading = note.obj("threading") ?: return
    val locks = threading.objects("locks")
    val model = threading.string("model")
    val affinity = threading.string("affinity")
    if (model == null && affinity == null && locks.isEmpty()) return

    var open by remember { mutableStateOf(false) }
    val subtitle = buildList {
        model?.let(::add)
        if (locks.isNotEmpty()) add("락 %,d".format(locks.size))
    }.joinToString(" · ")

    CollapsibleSection("스레드 / 락", subtitle, open, p, { open = !open }) {
        model?.let { AttrRow("모델", it, p) }
        affinity?.let { AttrRow("스레드", it, p) }
        locks.forEach { lock ->
            SubtleBlock(p) {
                Mono("🔒 " + lock.string("name").orEmpty(), p.text, weight = FontWeight.Medium)
                lock.string("guards")?.let { AttrRow("보호", it, p) }
                lock.string("order")?.let { AttrRow("순서", it, p) }
            }
        }
    }
}

/**
 * The packets this file handles — the axis this server is organised around.
 *
 * Three questions get answered in one place, and each was previously unanswerable from the panel: which ids
 * this file deals with, which function handles each one (click to go there), and — since a protocol is
 * shared — where else in the store the same id turns up. A packet that a recorded sequence traces links
 * straight to it, which is what ties the table to the flows.
 */
@Composable
private fun Packets(note: JsonObject, s: CodemapState.Loaded, vm: CodemapViewModel, p: CodemapPalette) {
    val packets = note.objects("packets")
    if (packets.isEmpty()) return

    var open by remember { mutableStateOf(false) }
    val inbound = packets.count { it.string("dir") == "in" }
    val outbound = packets.count { it.string("dir") == "out" }
    val subtitle = buildList {
        if (inbound > 0) add("수신 %,d".format(inbound))
        if (outbound > 0) add("송신 %,d".format(outbound))
    }.joinToString(" · ").ifEmpty { "%,d개".format(packets.size) }

    CollapsibleSection("패킷", subtitle, open, p, { open = !open }) {
        packets.forEach { o ->
            val id = o.string("id").orEmpty()
            val symbol = o.string("handler") ?: o.string("sentBy").orEmpty()
            val loc = s.functionLoc[symbol]
                ?: s.functionLoc.entries.firstOrNull { it.key.substringAfterLast("::") == symbol }?.value
            val (label, color) = when (o.string("dir")) {
                "in" -> "수신" to p.inbound
                "out" -> "송신" to p.outbound
                else -> "" to p.mutedText
            }

            PacketRow(
                dirLabel = label,
                dirColor = color,
                id = id,
                handler = symbol,
                palette = p,
                // The handler is the click target: from an id, the code that deals with it is the next thing
                // anyone wants. A handler whose anchor no longer resolves is not clickable rather than
                // jumping somewhere wrong.
                onJump = loc?.let { { vm.jumpTo(it) } },
            )

            // A protocol is shared, so the other half of the answer lives in other notes.
            s.packetElsewhere[id]?.takeIf { it.isNotEmpty() }?.let { others ->
                Text(
                    "다른 곳: " + others.joinToString(", ") { e ->
                        val dir = when {
                            e.inbound -> "수신"
                            e.outbound -> "송신"
                            else -> ""
                        }
                        "${e.ownerName}${if (dir.isEmpty()) "" else " $dir"}"
                    },
                    color = p.mutedText,
                    fontSize = Type.micro,
                    modifier = Modifier.padding(start = 46.dp),
                )
            }

            s.packetFlows[id]?.forEach { entry ->
                Row(Modifier.padding(start = 46.dp)) {
                    LinkRow("시퀀스: ${entry.name}", entry.ownerName, p) { vm.openSequence(entry) }
                }
            }
        }
    }
}

/**
 * The sequence diagrams for this file, and the way to ask for another.
 *
 * These are collected, not generated: each one exists because someone named a scenario and asked for it,
 * so they accumulate under their own names and a new request never costs you an old diagram. That is also
 * why each has a × — a collection you cannot prune stops being one.
 *
 * With nothing collected yet the whole section is a single button, because an empty "시퀀스 0개" heading
 * would be a section that only tells you it has nothing to say.
 */
@Composable
private fun Sequences(note: JsonObject, s: CodemapState.Loaded, vm: CodemapViewModel, p: CodemapPalette) {
    val flows = note.objects("flows")
    var adding by remember(s.rel) { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    val scenario = remember(s.rel) { TextFieldState() }

    if (flows.isEmpty() && s.appearances.isEmpty()) {
        Row(Modifier.padding(top = Space.xs)) {
            if (adding) SequenceRequest(scenario, vm, p) { adding = false }
            else ActionButton("＋ 패킷 시퀀스", p, primary = false) { adding = true }
        }
        return
    }

    val subtitle = buildList {
        if (flows.isNotEmpty()) add("%,d개".format(flows.size))
        if (s.appearances.isNotEmpty()) add("등장 %,d개".format(s.appearances.size))
    }.joinToString(" · ")

    CollapsibleSection("패킷 시퀀스", subtitle, open, p, { open = !open }) {
        // Names, not drawings. A sequence diagram wants more width than a tool window has, so clicking one
        // opens the viewer tab — the panel's job here is to say which ones exist.
        flows.forEach { f ->
            val name = f.string("name").orEmpty()
            LinkRow(name, "%,d단계".format(f.array("steps")?.size() ?: 0), p) { vm.openSequence(name) }
        }

        // Flows another note holds in which this file takes part. Without this, reading World.cpp shows
        // nothing about the three flows World runs through — they are filed under PlayerSession.h, because
        // that is where someone happened to ask. The owning file is named on every row: two files can hold
        // a class of the same name, and the match is a string comparison, so the person decides.
        if (s.appearances.isNotEmpty()) {
            Text(
                "이 파일이 등장하는 흐름",
                color = p.mutedText,
                fontSize = Type.micro,
                modifier = Modifier.padding(top = Space.sm),
            )
            s.appearances.forEach { entry ->
                LinkRow(entry.name, entry.ownerName, p) { vm.openSequence(entry) }
            }
        }

        if (adding) SequenceRequest(scenario, vm, p) { adding = false }
        else {
            Row(Modifier.padding(top = Space.sm)) {
                ActionButton("＋ 패킷 시퀀스", p, primary = false) { adding = true }
            }
        }
    }
}

/** Name the scenario, then run it now or leave it for the batch. */
@Composable
private fun SequenceRequest(
    scenario: TextFieldState,
    vm: CodemapViewModel,
    p: CodemapPalette,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    Column(Modifier.fillMaxWidth()) {
        TextField(
            state = scenario,
            placeholder = { Text("시나리오 — 예: 로그인 패킷부터 월드 입장 통보까지 (Enter)") },
            modifier = Modifier.fillMaxWidth().padding(vertical = Space.xs)
                .focusRequester(focus)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.Escape -> {
                            scenario.edit { replace(0, length, "") }
                            onClose()
                            true
                        }
                        Key.Enter, Key.NumPadEnter -> {
                            vm.addSequence(scenario.text.toString())
                            scenario.edit { replace(0, length, "") }
                            onClose()
                            true
                        }
                        else -> false
                    }
                },
        )
        LaunchedEffect(Unit) { focus.requestFocus() }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            ActionButton("그리기", p) {
                vm.addSequence(scenario.text.toString())
                scenario.edit { replace(0, length, "") }
                onClose()
            }
        }
    }
}

@Composable
private fun Functions(note: JsonObject, s: CodemapState.Loaded, vm: CodemapViewModel, p: CodemapPalette) {
    val fns = note.objects("functions")
    if (fns.isEmpty()) return

    var open by remember { mutableStateOf(false) }
    var filtering by remember(s.rel) { mutableStateOf(false) }
    val filter = remember(s.rel) { TextFieldState() }
    val focus = remember(s.rel) { FocusRequester() }
    val query = filter.text.toString().trim()

    CollapsibleSection("함수 목차", "%,d개".format(fns.size), open, p, { open = !open }) {
        FunctionList(fns, s, vm, p, filtering, filter, focus) { filtering = it }
    }
}

@Composable
private fun FunctionList(
    fns: List<JsonObject>,
    s: CodemapState.Loaded,
    vm: CodemapViewModel,
    p: CodemapPalette,
    filtering: Boolean,
    filter: TextFieldState,
    focus: FocusRequester,
    setFiltering: (Boolean) -> Unit,
) {
    val query = filter.text.toString().trim()
    if (fns.size >= FILTER_THRESHOLD) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Jumping and filtering are different jobs: this goes to one function, 거르기 narrows what is
            // on screen so several can be compared. A dropdown for the jump because it does not take
            // keyboard focus — a text field here would swallow the IDE's own shortcuts.
            val located = fns.filter { f -> s.functionLoc[f.string("name")] != null }
            if (located.isNotEmpty()) {
                Picker(
                    label = "이동",
                    items = located.map { f ->
                        PickerItem(f.string("name").orEmpty(), f.string("purpose").orEmpty().take(40))
                    },
                    selected = located.indexOfFirst { it.string("name") == vm.focusedFunction }.coerceAtLeast(0),
                    palette = p,
                    modifier = Modifier.weight(1f, fill = false),
                ) { i -> s.functionLoc[located[i].string("name")]?.let(vm::jumpTo) }
            }
            if (!filtering) ActionButton("거르기", p, primary = false) { setFiltering(true) }
        }
    }

    if (filtering) {
        TextField(
            state = filter,
            placeholder = { Text("함수 이름으로 거르기") },
            modifier = Modifier.fillMaxWidth().padding(bottom = Space.xs)
                .focusRequester(focus)
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                        filter.edit { replace(0, length, "") }
                        setFiltering(false)
                        true
                    } else {
                        false
                    }
                },
        )
        LaunchedEffect(Unit) { focus.requestFocus() }
    }

    val shown = if (query.isEmpty()) fns else fns.filter {
        it.string("name").orEmpty().contains(query, ignoreCase = true)
    }
    if (shown.isEmpty()) {
        Text("일치하는 함수 없음", color = p.mutedText, fontSize = Type.label)
        return
    }

    shown.forEach { f ->
        val name = f.string("name").orEmpty()
        val loc = s.functionLoc[name]
        FunctionRow(
            name = name,
            purpose = f.string("purpose").orEmpty(),
            badges = buildList {
                f.string("thread")?.let { add(it to p.mutedText) }
                if (f.strings("locks").any { it.isNotBlank() && it != "없음" }) add("🔒" to p.warn)
            },
            located = loc != null,
            ambiguous = (loc?.occurrences ?: 1) > 1,
            current = name == vm.focusedFunction,
            palette = p,
            onJump = { loc?.let(vm::jumpTo) },
        )
        FunctionDetail(f, p)
    }
    if (query.isNotEmpty()) {
        Text("%,d / %,d".format(shown.size, fns.size), color = p.mutedText, fontSize = Type.micro)
    }
}

/** The deep fields, shown only for the functions that earned them. */
@Composable
private fun FunctionDetail(
    f: JsonObject,
    p: CodemapPalette,
    indent: Boolean = true,
    /** Off where an editable list renders them instead — otherwise every gotcha appears twice. */
    showGotchas: Boolean = true,
) {
    val calls = f.strings("calls")
    val effects = f.strings("effects")
    val locks = f.strings("locks").filter { it.isNotBlank() && it != "없음" }
    val gotchas = if (showGotchas) f.strings("gotchas") else emptyList()
    if (calls.isEmpty() && effects.isEmpty() && locks.isEmpty() && gotchas.isEmpty()) return

    Column(Modifier.fillMaxWidth().padding(start = if (indent) Space.md else 0.dp, bottom = Space.xs)) {
        if (calls.isNotEmpty()) AttrRow("호출", calls.joinToString(", "), p)
        if (locks.isNotEmpty()) AttrRow("락", locks.joinToString(", "), p)
        effects.forEach { AttrRow("효과", it, p) }
        gotchas.forEach { Text("⚠ $it", color = p.warn, fontSize = Type.micro, lineHeight = 15.sp) }
    }
}

/** Below this many functions the eye still beats a filter box. */
private const val FILTER_THRESHOLD = 12

// ---- request ----

@Composable
private fun RequestBox(
    s: CodemapState.Loaded,
    vm: CodemapViewModel,
    question: TextFieldState,
    p: CodemapPalette,
) {
    SectionHeader(if (s.note == null) "분석 요청" else "재분석 요청", p)

    // The question field is opt-in, and that is a correctness requirement, not a style choice: a
    // Compose text field sitting in the panel takes focus the moment the tool window opens and then
    // swallows the IDE's own shortcuts — Cmd+1, Cmd+Shift+O and the arrow keys all end up as text.
    // Behind a toggle, the panel has nothing focusable until the user actually asks for it.
    var asking by remember(s.rel) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    if (asking) {
        TextField(
            state = question,
            placeholder = { Text("질문 (선택) — Enter 로 실행, Esc 로 취소") },
            modifier = Modifier.fillMaxWidth().padding(vertical = Space.sm)
                .focusRequester(focus)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        // Esc gives the keyboard back to the IDE instead of trapping it here.
                        Key.Escape -> {
                            question.edit { replace(0, length, "") }
                            asking = false
                            true
                        }
                        // Enter runs it. Typing a question and then hunting for a button is a step nobody
                        // wants twice.
                        Key.Enter, Key.NumPadEnter -> {
                            vm.analyzeNow(question.text.toString())
                            question.edit { replace(0, length, "") }
                            asking = false
                            true
                        }
                        else -> false
                    }
                },
        )
        LaunchedEffect(Unit) { focus.requestFocus() }
    }

    when (val a = vm.analysis) {
        is CodemapViewModel.Analysis.Running -> {
            Working("분석 중 — ${a.path.substringAfterLast('/')}", p) { vm.cancelAnalysis() }
            return
        }

        is CodemapViewModel.Analysis.Failed ->
            Banner("분석 실패 — ${a.reason}", p.warn, p)

        CodemapViewModel.Analysis.Idle -> Unit
    }

    Row(
        Modifier.padding(top = Space.sm),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // One button, not two: an analysis either runs now or is not asked for. The queue that used to sit
        // beside it was a to-do list nobody could see, which made it a worse way of forgetting.
        ActionButton(if (s.note == null) "노트 만들기" else "노트 다시 만들기", p) {
            vm.analyzeNow(question.text.toString())
            question.edit { replace(0, length, "") }
            asking = false
        }
        if (!asking) ActionButton("질문 달기", p, primary = false) { asking = true }
        // Two verbs, and the names have to carry the difference: one WRITES the note (the structure the
        // whole panel is drawn from), the other ASKS (prose, which the conversation can later fold back in).
        ActionButton("물어보기", p, primary = false) { vm.openChat() }
    }

    EnginePicker(vm, p)
}

/**
 * Which agent runs 분석 실행.
 *
 * A dropdown, not a button per engine: only the chosen one matters, the list will grow, and the panel has
 * no width to spend on options nobody picked. An engine that is not installed says so on its own row and
 * cannot be chosen — the failure belongs here, not at spawn time.
 */
@Composable
private fun EnginePicker(vm: CodemapViewModel, p: CodemapPalette) {
    val installed = remember(vm.engine) { Engine.entries.associateWith { vm.engineInstalled(it) } }
    val items = Engine.entries.map { e ->
        PickerItem(
            label = e.label,
            detail = if (installed[e] == true) "" else "설치를 찾지 못함 — Settings | Tools | 코드맵",
            enabled = installed[e] == true,
        )
    }
    Picker(
        label = "분석기",
        items = items,
        selected = Engine.entries.indexOf(vm.engine),
        palette = p,
        modifier = Modifier.padding(top = Space.xs),
    ) { i -> vm.chooseEngine(Engine.entries[i]) }
}

// ---- loose-JSON accessors: a missing or wrongly-typed key renders as absent, never as a crash ----

private fun JsonObject.string(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

private fun JsonObject.array(key: String): JsonArray? = get(key) as? JsonArray

private fun JsonObject.objects(key: String): List<JsonObject> =
    array(key)?.mapNotNull { it as? JsonObject }.orEmpty()

private fun JsonObject.strings(key: String): List<String> =
    array(key)?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }.orEmpty()

/** Render a section only when the AI filled it in — an absent key leaves no empty heading behind. */
@Composable
private fun List<JsonObject>.section(p: CodemapPalette, title: String, row: @Composable (JsonObject) -> Unit) {
    if (isEmpty()) return
    SectionHeader(title, p)
    forEach { row(it) }
}

