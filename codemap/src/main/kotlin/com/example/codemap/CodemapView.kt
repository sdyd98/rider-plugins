package com.example.codemap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
    Header(s, p)
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
private fun Header(s: CodemapState.Loaded, p: CodemapPalette) {
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
        if (s.pending != null) Chip("요청됨", p.accent, p)
    }

    // The one thing that must be impossible to miss when a note has drifted from the code.
    if (s.freshness == NoteStore.Freshness.STALE) {
        val at = s.note?.string("analyzedAt").orEmpty()
        val drift = s.commitsSince?.let { ", 이후 커밋 %,d개".format(it) }.orEmpty()
        Column(Modifier.padding(top = Space.sm)) {
            Banner("⚠ 분석 이후 코드가 변경됨 — $at 분석$drift", p.warn, p)
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
    // Only the function index. Every other section the note holds (요약 · 주의 · 클래스 · 상태 ·
    // 스레드/락 · 패킷 · 의존 · 흐름 · 데이터 연결) is deliberately not on screen: the panel is being
    // rebuilt from nothing, adding back only what use actually proves necessary. [Details] still
    // renders all of them and is one call away from returning.
    Functions(note, s, vm, p)

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
            onSave = { v -> vm.edit { note -> note.function(name)?.addProperty("purpose", v) } },
        )
        f.string("thread")?.let { AttrRow("스레드", it, p) }
        FunctionDetail(f, p, indent = false, showGotchas = false)

        // What the note says about this function's place in the file…
        CallGraphFor(note, name, s, vm, p)

        // …and a handoff to the backend for what the note cannot know.
        Row(
            Modifier.padding(top = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            // Two buttons, not one: the graph is what the notes know, 사용처 is what the backend knows.
            // Collapsing them under a single label would make whichever name we chose a lie.
            ActionButton("호출 그래프", p, primary = false) { vm.openGraph(name) }
            s.functionLoc[name]?.let { loc ->
                ActionButton("사용처 찾기", p, primary = false) { vm.showUsages(loc, name) }
            }
        }

        EditableList(
            f.strings("gotchas"),
            p,
            addLabel = "＋ 이 함수 주의 추가",
            onChange = { list -> vm.edit { note -> note.function(name)?.add("gotchas", list.toJsonArray()) } },
        ) { text -> Text("⚠ $text", color = p.warn, fontSize = Type.micro, lineHeight = 15.sp) }
    }
}

/**
 * Callers above, callees below, this function in the middle — assembled from the note alone.
 *
 * Callers are the functions in this same note whose `calls` mention this one, which is arithmetic over
 * recorded data rather than a claim about the codebase. The graph is therefore FILE-SCOPED and says so;
 * everything outside is what the 사용처 찾기 button is for.
 */
@Composable
private fun CallGraphFor(
    note: JsonObject,
    name: String,
    s: CodemapState.Loaded,
    vm: CodemapViewModel,
    p: CodemapPalette,
) {
    val fns = note.objects("functions")
    fun node(label: String): CallNode {
        val loc = s.functionLoc[label] ?: s.functionLoc.entries
            .firstOrNull { it.key.substringAfterLast("::") == label.substringAfterLast("::") }?.value
        return CallNode(label, loc?.let { { vm.jumpTo(it) } })
    }

    val bare = name.substringAfterLast("::")
    val callers = fns.filter { f ->
        f.strings("calls").any { it == name || it.substringAfterLast("::") == bare }
    }.mapNotNull { it.string("name") }.filter { it != name }

    val callees = fns.firstOrNull { it.string("name") == name }?.strings("calls").orEmpty()
    if (callers.isEmpty() && callees.isEmpty()) return

    Text("이 파일 안에서", color = p.mutedText, fontSize = Type.micro, modifier = Modifier.padding(top = Space.sm))
    CallGraph(callers.map(::node), name, callees.map(::node), p)
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
@Suppress("unused") // Not on screen right now — see the note in LoadedView.
@Composable
internal fun Details(note: JsonObject, s: CodemapState.Loaded, vm: CodemapViewModel, p: CodemapPalette) {
    val present = buildList {
        if (note.string("purpose") != null) add("요약")
        if (note.strings("gotchas").isNotEmpty()) add("주의")
        if (note.objects("entryPoints").isNotEmpty()) add("진입점")
        if (note.objects("classes").isNotEmpty()) add("클래스")
        if (note.objects("keyState").isNotEmpty()) add("상태")
        if (note.obj("threading") != null) add("스레드")
        if (note.objects("packets").isNotEmpty()) add("패킷")
        if (note.objects("dependsOn").isNotEmpty() || note.objects("usedBy").isNotEmpty()) add("의존")
        if (note.objects("flows").isNotEmpty()) add("흐름")
        if (note.objects("dataSources").isNotEmpty()) add("데이터")
    }
    if (present.isEmpty()) return

    var open by remember { mutableStateOf(false) }
    CollapsibleSection("파일 전체", present.joinToString(" · "), open, p, { open = !open }) {
        SectionHeader("요약", p)
        Editable(note.string("purpose").orEmpty(), p, onSave = { v -> vm.edit { it.addProperty("purpose", v) } })
        Editable(
            note.string("roleInSystem").orEmpty(),
            p,
            fontSize = Type.label,
            color = p.mutedText,
            placeholder = "(시스템에서의 위치 없음)",
            onSave = { v -> vm.edit { it.addProperty("roleInSystem", v) } },
        )

        // Then gotchas: of everything in here, these are the lines that stop a bug.
        SectionHeader("주의", p, color = p.warn)
        EditableList(
            note.strings("gotchas"),
            p,
            addLabel = "＋ 주의 추가",
            onChange = { list -> vm.edit { it.add("gotchas", list.toJsonArray()) } },
        ) { text -> WarnCard(text, p) }

        val entries = note.objects("entryPoints")
        if (entries.isNotEmpty()) {
            SectionHeader("여기부터 읽기", p)
            entries.forEachIndexed { i, o ->
                val symbol = o.string("symbol").orEmpty()
                NumberedEntry(i + 1, symbol, o.string("note").orEmpty(), p, s.functionLoc[symbol]?.let { { vm.jumpTo(it) } })
            }
        }

        note.objects("classes").section(p, "클래스") { o ->
            DefEntry(o.string("name").orEmpty(), o.string("role").orEmpty(), p)
        }
        note.objects("keyState").section(p, "핵심 상태") { o ->
            DefEntry(o.string("member").orEmpty(), o.string("note").orEmpty(), p)
        }
        note.obj("threading")?.let { t ->
            SectionHeader("스레드 / 락", p)
            t.string("model")?.let { Body(it, p) }
            t.string("affinity")?.let { NotedLine("스레드 소속", it, p) }
            t.objects("locks").forEach { l ->
                SubtleBlock(p) {
                    Mono("🔒 " + l.string("name").orEmpty(), p.text, weight = FontWeight.Medium)
                    l.string("guards")?.let { AttrRow("보호", it, p) }
                    l.string("order")?.let { AttrRow("순서", it, p) }
                }
            }
        }
        note.objects("packets").section(p, "패킷") { o ->
            val (label, color) = when (o.string("dir")) {
                "in" -> "수신" to p.inbound
                "out" -> "송신" to p.outbound
                else -> "" to p.mutedText
            }
            PacketRow(label, color, o.string("id").orEmpty(), o.string("handler") ?: o.string("sentBy").orEmpty(), p)
        }
        note.objects("dependsOn").section(p, "의존") { o ->
            DefEntry(o.string("target").orEmpty(), o.string("why").orEmpty(), p)
        }
        note.objects("usedBy").section(p, "사용하는 쪽") { o ->
            DefEntry(o.string("source").orEmpty(), o.string("context").orEmpty(), p)
        }
        note.objects("flows").section(p, "흐름") { o -> Flow(o, p) }
        note.objects("dataSources").section(p, "데이터 연결") { o ->
            DefEntry(
                listOfNotNull(o.string("kind"), o.string("ref")).joinToString("  "),
                o.string("note").orEmpty(),
                p,
            )
        }
    }
}

/**
 * The file's function table of contents — the section that makes a 6,000-line file navigable.
 *
 * Every function the AI recorded is listed in declaration order, so the list reads alongside the code
 * instead of re-ranking it. Names jump to the definition; the filter appears only past the point where
 * scanning stops working, and it is behind a toggle for the same reason the question field is (a
 * Compose text field left in the panel captures the IDE's shortcuts the moment the tool window opens).
 */
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
    if (fns.size >= FILTER_THRESHOLD && !filtering) {
        Row(Modifier.padding(vertical = Space.xs)) {
            ActionButton("검색", p, primary = false) { setFiltering(true) }
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

/**
 * One flow, drawn rather than written out.
 *
 * Which drawing depends on what the note holds: `steps` of objects carry `from`/`to`, so they become a
 * sequence diagram; `steps` of plain strings are a straight chain and become a flow chart. Participants
 * are taken in order of first appearance, which keeps the columns in the order the flow actually moves.
 */
@Composable
internal fun Flow(o: JsonObject, p: CodemapPalette) {
    Text(
        o.string("name").orEmpty(),
        color = p.text,
        fontSize = Type.label,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = Space.xs),
    )

    val raw = o.array("steps") ?: return
    val structured = raw.mapNotNull { it as? JsonObject }
    if (structured.size == raw.size() && structured.isNotEmpty()) {
        val steps = structured.mapNotNull { st ->
            val from = st.string("from") ?: return@mapNotNull null
            val to = st.string("to") ?: return@mapNotNull null
            SeqStep(
                from = from,
                to = to,
                label = st.string("call") ?: st.string("label").orEmpty(),
                ret = st.string("kind") == "return",
            )
        }
        val participants = LinkedHashSet<String>().apply {
            steps.forEach { add(it.from); add(it.to) }
        }.toList()
        SequenceDiagram(participants, steps, p)
    } else {
        FlowChart(o.strings("steps"), p)
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

    s.pending?.let { req ->
        Text(
            "${req.requestedAt} 요청됨 — ${req.question.ifEmpty { "(질문 없음)" }}",
            color = p.accent,
            fontSize = Type.label,
            lineHeight = 17.sp,
        )
    }

    // The question field is opt-in, and that is a correctness requirement, not a style choice: a
    // Compose text field sitting in the panel takes focus the moment the tool window opens and then
    // swallows the IDE's own shortcuts — Cmd+1, Cmd+Shift+O and the arrow keys all end up as text.
    // Behind a toggle, the panel has nothing focusable until the user actually asks for it.
    var asking by remember(s.rel) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    if (asking) {
        TextField(
            state = question,
            placeholder = { Text("질문 (선택) — 예: 이 세션이 락을 두 개 쓰는 이유") },
            modifier = Modifier.fillMaxWidth().padding(vertical = Space.sm)
                .focusRequester(focus)
                .onPreviewKeyEvent { e ->
                    // Esc gives the keyboard back to the IDE instead of trapping it here.
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                        question.edit { replace(0, length, "") }
                        asking = false
                        true
                    } else {
                        false
                    }
                },
        )
        LaunchedEffect(Unit) { focus.requestFocus() }
    }

    when (val a = vm.analysis) {
        is CodemapViewModel.Analysis.Running -> {
            Row(
                Modifier.padding(top = Space.sm),
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("분석 중… ${a.path.substringAfterLast('/')}", color = p.accent, fontSize = Type.label)
                ActionButton("취소", p, primary = false) { vm.cancelAnalysis() }
            }
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
        // 실행 runs Claude Code here and now; 큐에 넣기 keeps the batch path for a pile of files.
        ActionButton(if (s.note == null) "분석 실행" else "재분석 실행", p) {
            vm.analyzeNow(question.text.toString())
            question.edit { replace(0, length, "") }
            asking = false
        }
        ActionButton("큐에 넣기", p, primary = false) {
            vm.requestAnalysis(question.text.toString())
            question.edit { replace(0, length, "") }
            asking = false
        }
        if (!asking) ActionButton("질문 달기", p, primary = false) { asking = true }
        if (s.pendingTotal > 0) Text("대기 %,d건".format(s.pendingTotal), color = p.mutedText, fontSize = Type.label)
    }
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

