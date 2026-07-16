# XLSX Grid Viewer — Rider plugin

A frontend-only JetBrains Rider plugin (Kotlin) that opens `.xlsx` and `.xls` files in a fast,
**read-only** grid viewer — with per-sheet **filtering**, frozen header rows, and always-on
**vim-style navigation**. Built for browsing large multi-sheet data tables; it reads with Apache
POI (streaming) and never modifies the file.

On top of the viewer it adds a **relationship-graph explorer** for game-data workbooks — an ER map,
record-level data lineage, and an integrity check driven by a `refs.json` schema — plus MCP tools so
an AI client can author that schema. The tools are deliberately judgment-free (they enumerate sheets,
expose raw cells, compute overlap numbers, and validate); every interpretive decision — layout, ids,
references — is the AI's, made primarily from the game source
(see [Relationship graph & refs.json](#relationship-graph--refsjson-game-data) below).

Target: **Rider 2026.1.3** (build 261) · **JDK 21** toolchain · IntelliJ Platform Gradle Plugin 2.16.0.

This is the `xlsx-editor` module of the [`rider-plugins`](../README.md) monorepo. The Gradle build
config (`settings.gradle.kts`, root `build.gradle.kts`, `gradle.properties`, the version catalog)
lives at the repo root; `PoiClassLoaders` / cached-formula formatting live in the shared `common`
module. This module's own files:

## Project layout

```
xlsx-editor/
  build.gradle.kts                       module build (IntelliJ Platform, Apache POI, :common dep)
  src/main/resources/META-INF/plugin.xml plugin descriptor (file type + editor provider)
  src/main/kotlin/com/example/xlsx/
    XlsxFileType.kt           binary file type for *.xlsx / *.xls
    XlsxFileEditorProvider.kt accepts the file type, creates the viewer (HIDE_OTHER_EDITORS)
    XlsxFileEditor.kt         orchestrates open (xlsx/xls) + tabs (read-only)
    XlsxStreamingReader.kt    .xlsx SAX event-model reader (lazy per-sheet, parallel parse)
    XlsWorkbookReader.kt      .xls (BIFF) usermodel reader (build + incremental render)
    SheetTableModel.kt        compact, read-only String-based model; streams rows in
    SheetPanel.kt             per-sheet grid + sorter + combined row filter (filter/status bars are shared chrome)
    ColumnFilterController.kt Excel-style per-column value filter (header funnel + checkbox popup)
    VimGridController.kt      always-on modal vim navigation for the grid
    GridStyling.kt            cell renderer + row-number gutter
    FormulaScanner.kt         SAX pass that records .xlsx formula-cell positions (parallel with parse)
    ComposeChrome.kt          shared Compose/Jewel filter bar + status bar (editor-level)
    ComposeColumnFilter.kt    Compose popup for the per-column value filter
    ComposeHelp.kt            Compose `?` shortcut cheat-sheet popup
    --- relationship graph / refs.json (game data) ---
    RefGraphToolWindow.kt     "관계도" tool window factory (Compose host)
    DataGraphView.kt          3 tabs: ER map · record lineage · integrity check (검사)
    RefGraphPanel.kt          table-level ER map (Compose Canvas, force layout)
    RefGraphModel.kt          ER / record view-model + TableColor.kt node colors (no refs.json → guidance, no mock)
    RelationshipSchema.kt     refs.json parse + buildRefGraph + IndexRecordGraph (real data path)
    GameDataLoader.kt         streaming POI index (+ on-disk .idx cache)
    RelationshipNavigation.kt schema resolution (nearest refs.json) + graph→grid navigation
    RelationshipBus.kt        grid→graph event bus (Ctrl+R record · Ctrl+F table)
    SheetScanner.kt           judgment-free sheet access for the MCP tools (enumerate / raw rows / column values / overlap math)
    RefsMcpToolset.kt         11 MCP tools for refs.json authoring (IDE built-in MCP server)
  (shared in ../common: PoiClassLoaders.kt, CellFormatting.kt, and VimTableController.kt —
   the vim key/count/mark/scroll base class VimGridController extends)
```

## How it works

- **Open (streaming, parallel, incremental):** `XlsxStreamingReader` opens the package, loads the
  shared-strings + styles once, then SAX-parses each sheet's XML. Sheets are parsed on background
  threads — **in parallel for multi-sheet workbooks** — and rows are pushed to the grid in batches
  (`BATCH_SIZE`), so the tab skeleton appears almost immediately and rows stream in. Cells are kept
  as compact display strings (`SheetTableModel`), not POI objects, so memory stays low.
- **Read-only:** the grid is a viewer — cells are not editable and the file is never written back, so
  there is no edit overlay, save path, or editable workbook to build. Formula cells show their cached
  value (what Excel last computed); the status bar marks a selected formula cell (`ƒ =…`) so it's
  clear the value isn't live-evaluated in the IDE.
- **POI + classloaders:** every POI call is wrapped in `withPoiClassLoader { ... }` so XMLBeans
  and JAXP service discovery use the plugin classloader (otherwise parsing fails inside the IDE).
- **.xls (legacy BIFF):** there is no clean SAX streaming for HSSF, but `.xls` is hard-capped at
  65,536 rows × 256 cols, so `XlsWorkbookReader` builds it fully via `WorkbookFactory`, then shows the
  tabs and renders each sheet's cells incrementally (active sheet first). No extra dependency (HSSF
  ships in `poi-core`).
- **Filtering:** two filters combine into one `TableRowSorter` `RowFilter` (`SheetPanel`):
  - a **global text** field (`Filter:`) — a vim-style **regex** search (case-insensitive substring
    match via `containsMatchIn`), OR across all columns, debounced 150 ms. The pattern is compiled
    once per rebuild (not per cell); an incomplete/invalid pattern typed mid-keystroke falls back to a
    literal contains so filtering never errors;
  - **Excel-style per-column value filters** (`ColumnFilterController`) — each header shows a funnel
    icon; clicking it (or pressing **Ctrl+Alt+F** for the selected column) opens a searchable
    checkbox list of that column's distinct values, and the selected subset keeps only matching rows.
    Multiple columns AND together; active columns show a blue/bold header. The Ctrl+Alt+F shortcut is
    registered via `DumbAwareAction.registerCustomShortcutSet` so it overrides any IDE-global binding
    while the grid is focused.

  The sorter is attached **only after streaming finishes** with `setSortsOnUpdates(false)`, so streamed
  row appends never reshuffle rows; `JTable` converts view↔model indices so navigation/selection stay
  correct under a filter.
  Pressing **Enter** in the filter field applies the filter immediately and returns focus to the grid
  (so vim navigation resumes without a mouse click).

  The bar's **mode chip** (or **`Alt+H`**, both in the grid and in the field) switches the query
  between **▼ Filter** (rows hidden, as above) and
  **⌕ Highlight** — a NON-filtering search: rows stay put (context preserved), matching **cells**
  are tinted with the editor scheme's search-result color (live theme/scheme aware), the bar shows
  the matched-cell count, **Enter** jumps to the first match, and **`n`/`N`** jump between matches
  (an active highlight search outranks the last `*`/`#` value; with no search active `n`/`N` repeat
  `*`/`#` as before). Column filters still apply in highlight mode — only the text query stops hiding
  rows.
- **Vim navigation (always on):** `VimGridController` installs modal keybindings on the table's
  `WHEN_FOCUSED` InputMap (a-z are all captured so a mark name is read — the grid is read-only, so vim
  owns the keys):
  - **Move:** `hjkl`, counts (`5j`), `0` row start, `^`/`$` first/last cell **with data** in the row,
    `gg`/`G` top/bottom, `w`/`e`/`b` next-word-start / word-end / prev-word (a *word* = a run of
    non-empty cells).
  - **Scroll:** `Ctrl+D`/`Ctrl+U` half-page, `Ctrl+E`/`Ctrl+Y` one line, `zz`/`zt`/`zb` cursor row to
    center/top/bottom, `H`/`M`/`L` top/middle/bottom of the visible screen.
  - **Search/jump:** `*`/`#` next/prev row with the **same value in this column**, `n`/`N` repeat;
    `{`/`}` prev/next blank row; `m{a-z}` set a mark / `` `{a-z} `` jump to it (marks are stored by
    model coordinates, so they survive filtering/streaming).
  - **Copy:** `yy` copies the current cell; `v` starts a cell-wise visual block (extend with `hjkl`)
    and `V` a row-wise visual selection (`j`/`k`) — `y` copies the selection with tabs between cells
    and newlines between rows (pastes straight into Excel), Esc cancels, `Ctrl+C` also works.
  - **Other:** `gt`/`gT` next/previous sheet, **`Alt+S`** type-to-filter sheet jump popup,
    `/` focus filter, `?` shortcut cheat-sheet popup.

  (`Ctrl+D`/`U`/`E`/`Y` and `Ctrl+Alt+F` use `registerCustomShortcutSet` to beat IDE-global bindings.)
  The grid is read-only, so there are no editing commands. IdeaVim can't be reused — it targets text
  editors, not a grid.

- **Frozen header rows + column jump:** press **`Alt+Shift+F`** to freeze the top rows through the
  cursor (model rows `0..current`); press it again to **unfreeze**. Frozen rows pin to the top while
  scrolling **and** stay visible through filtering. The last freeze state (row count + key row) is
  **remembered globally** (`GridPrefs`) and auto-applied to every sheet/workbook opened afterwards —
  game-data tables share one layout, so freeze once and every table opens with its headers pinned;
  unfreezing is remembered too (new sheets open unfrozen). A manual `Alt+Shift+F` on a sheet always
  overrides the remembered default for that sheet. Frozen rows live in a separate `frozenTable` placed in the scroll pane's column-header
  view, sharing the main table's column model so widths stay in sync; the main table's `RowFilter`
  excludes them. One frozen row is the **key row** (`Alt+K` cycles it) whose values name the columns
  in the **`Alt+\`** jump popup (type to filter, choose to move the cursor to that column).

- **Grid styling** (`GridStyling.kt`): an Excel-style row-number gutter (`RowNumberHeader`, painted
  from live table geometry so it tracks scroll/filter), a `GridCellRenderer` (right-aligned numbers,
  bold first/header row), and column auto-sizing from a row sample. The background is FLAT with
  light gridlines on both axes (no zebra striping — unselected cells carry no color), so selection
  is the only thing painted in color: the **active cell keeps its normal background with a 2px
  accent ring** (a cursor never looks "selected"), **range members get an accent tint**, and the
  range draws a **1px outline around its outside edges** like Excel; the status bar also shows
  `-- VISUAL --` / `-- V-LINE --` while a visual mode is active.
- **Shared Compose chrome** (`ComposeChrome.kt`): one editor-level filter bar + status bar (Compose/
  Jewel), bound to the active sheet. The filter bar has a live match count, an invalid-regex outline,
  and removable per-column filter chips; the status bar shows the cell reference (e.g. `C5`),
  visible/total row counts, active filters, the freeze/key-row state, and streaming progress.

### Performance note (measured)

The wins of streaming are: lower **memory** (compact strings vs ~600k POI cell objects), faster
**perceived** open, and **parallel per-sheet** parsing for multi-sheet workbooks.

For **large files** the open path is tuned to show content fast (measured on a 100 MB / 20-sheet /
1.06 M-row `.xlsx`, first paint ~1.8 s → **~0.3 s**):

- Open the OPC package from the local **io File** (`VfsUtilCore.virtualToIoFile`) — random access via
  the zip central directory is ~67 ms vs ~1.5 s when inflating a 100 MB byte stream.
- `open()` reads only shared strings + styles + sheet **names**; each sheet's XML is decompressed
  **lazily** (active sheet first) on a background thread and parsed in parallel, so the tabs appear
  before the (~1.6 s) decompression of every sheet — and a 100 MB `.xlsx` is never loaded into memory
  whole (it's opened by random access from the file).
- `.xls` has no streaming (HSSF builds the whole workbook ~1.8 s), but the tabs show right after the
  build and sheets render incrementally (active first), so first paint is ~2.3 s rather than ~4.4 s.
- `.xls` measured (47 MB / 2.6M cells): full HSSF build ~0.9 s + all-sheet render ~0.8 s. Numeric
  cells route through `formatRawCellContents` explicitly — the usermodel convenience path
  (`formatCellValue`) never calls it, which would silently bypass the fast General path below.
  Rendering now sits within ~15% of a bare formatting loop, so the remaining `.xls` floor is the
  (unavoidable) HSSF workbook build.
- The `.xlsx` parse is tuned for game-data shapes (measured on a 28 MB / 5.4M-cell workbook, serial):
  the formula scan byte-checks for a `<f` tag before its SAX pass (2.0 s → 0.05 s on formula-free
  sheets), General-format numbers take a fast path around POI's per-cell BigDecimal rounding
  (`FastGeneralFormatter` — byte-identical output, pinned by a 200k-value equivalence test), and
  repeated display strings are deduped at parse time (enum-ish columns → far less heap/GC). Overall
  ~7.0 s → ~3.8 s serial CPU; wall time is lower still since sheets parse in parallel.

Large `.xls`/`.xlsx` also need the IDE's heap raised (Help → Change Memory Settings) and the plugin
lifts POI's anti-DoS array cap + bypasses the IDE's ~20 MB content-load limit so big files open.

## Relationship graph & refs.json (game data)

Game data is spread across many workbooks whose columns reference rows in other tables (an item
points at a skill id, a monster points at a drop-table id, …). This module reads those
cross-references and shows them as a graph, driven by a `refs.json` schema that sits at the data
**root**.

**Three views** (the right-hand **관계도** tool window, `RefGraphToolWindow`):

- **테이블 관계도** — a table-level **ER map**: each table's id column(s) (◆) and reference columns
  (→ with the target-table badge), and the foreign-key edges between them, laid out around the
  most-connected table (`RefGraphPanel`, Compose `Canvas` + force layout; pan/zoom/drag/hover).
- **데이터 연결** — record-level **data lineage**: centre on one row and see the rows it references
  (outgoing) and the rows that reference it (incoming), with usage counts and group members
  (`DataGraphView`).
- **검사** — a workbook-wide **integrity check**: dangling/broken references (a cell points at an id
  that has no row); the tab badges the broken count. There is deliberately NO orphan
  (unreferenced-record) detection — unreferenced rows are normal in game data (content is authored
  ahead of whatever will reference it), so flagging them was noise.

**Navigation is bidirectional:** press **`Ctrl+R`** on a grid row to centre the explorer on that
record, or **`Ctrl+F`** to centre the table-level ER map on the current sheet's table
(`RelationshipBus` → tool window); click a graph node to open its workbook and select the
row (`RelationshipNavigation` → `XlsxFileEditor.revealSheetRow`).

**`refs.json`** describes each table (`file`, `sheet`, `headerRow`, `dataStartRow`, `id`, `display`)
and its `refs` (a `from` column → a `to` table, with `by` / `split` for grouped or delimited
multi-value refs, and `when` for **conditional/polymorphic** refs — e.g.
`{"from": ["Param"], "to": "Item", "when": {"ParamType": ["3", "4"]}}` applies only to rows whose
`ParamType` is 3 or 4; sibling refs on the same column with different `when` values target other
tables; the ER map badges such refs `if`). A top-level `"nullValues": ["0", "-1"]` lists the
placeholder values that mean "no reference" (default `["0"]`) so they are never counted as broken. The graph reads workbooks with streaming POI and a compact index cached on disk
(`.idx`, keyed by file mtime/size + `refs.json`), so reopening is fast and nothing is held in memory
whole. See `samples/gamedata/refs.json` for a worked example.

Keep **one `refs.json` at the top of your data tree**: the views use the nearest `refs.json` at or
above the open workbook, and their cost scales with the table count in *that schema* — not the number
of workbooks on disk — so a single top-level file scales to thousands of tables.

**Authoring `refs.json`** — you author it through the IDE's MCP server with an AI client (e.g. Claude
Code), not by hand. `RefsMcpToolset` exposes eleven tools — `build_refs`, `list_tables`, `sample_rows`,
`column_values`, `check_ref`, `read_refs`, `read_table_refs`, `write_table_refs`, `set_null_values`,
`list_unfilled_tables`, `validate_refs` — and the AI drives them.

The tools are deliberately **judgment-free** — they enumerate, extract, compute, and validate;
**every interpretive decision is the AI's**:

- **`build_refs(<top folder>)` writes SKELETONS only.** It enumerates every sheet under the folder
  (a name scan — a multi-thousand-table tree in seconds) into `{file, sheet}` entries, **additively**
  (re-runs only ADD new sheets, so the AI's filled-in work survives). No layout, id, display, or ref
  is guessed — ever. Skeletons are inert until filled: entries without an `id` (and refs pointing at
  them) are **excluded from the graph, the index, and validation**, so a half-authored schema never
  draws or validates default-layout guesses.
- **The AI fills every entry.** `sample_rows` shows a sheet's raw top rows verbatim (1-based row
  numbers, column letters, no header/data assumption) so the AI decides `headerRow` / `dataStartRow` /
  `id` / `display` itself; `column_values` extracts one column's distinct values + counts for the
  id/enum/reference judgment. The refs are designed from the **game source** — how the loading code
  uses each field (polymorphic columns become conditional `when` refs).
- **`check_ref` verifies each hypothesis with numbers, not opinions** — pure set arithmetic: how many
  distinct from-column tokens exist among the target column's values, plus missing samples. Honest
  about its caps: past 5,000 distinct from-values / 500k target ids it flags `fromTruncated` /
  `toTruncated` so a partial check is never mistaken for a full one. The AI supplies all coordinates
  (column letters, data start rows, `split`, null placeholders) and judges the result.
- The AI records its decisions with `write_table_refs` (single or **bulk** — one call fills a whole
  area; there is deliberately NO whole-file write, so a stale copy in the AI's context can never
  clobber filled-in judgments — `read_refs` reads the whole file, `set_null_values` sets the
  top-level placeholder list). **Writes are validated mechanically before anything is written**:
  field TYPES that would otherwise null the whole schema at load (`display` must be one string or
  null, `headerRow`/`dataStartRow` numbers, `id`/`from`/`by` arrays of strings), every ref `to` must
  name an existing (or same-call) table key, every recorded **field code** (`id`, `display`, `from`,
  `when` columns — and `by` against the target's header) must actually appear in the declared
  `headerRow` of the declared sheet (catching column letters or typos that would silently blank the
  table or its display names), and replacing an already-FILLED entry requires `overwrite=true`.
  Each ref is marked `_source: "code"`
  or `"data"` (the viewer ignores `_`-prefixed keys), and the loop closes with `validate_refs` —
  scopable to a comma-separated table list with paged examples, so on a large schema the AI validates
  the area it just filled instead of everything. Reading the result is also the AI's job: breaks
  concentrated in one table point at a wrong layout/id judgment, spread-out breaks at a wrong target
  or missing `when`.
- **Large schemas span sessions**, so `list_unfilled_tables` is the mechanical progress query: which
  entries are still `{file, sheet}` skeletons (no `id`), which have an id but no `refs` key yet
  (write `refs: []` explicitly once a table is decided to have none), and which have an id but no
  `display` key yet (write `display: null` once a table is decided to have no name-like column —
  an omitted display leaves records rendering with blank names, which matters to everything that
  shows record names). The two undecided lists are orthogonal; malformed (non-object) entries are
  reported separately instead of silently counting as filled. Counts plus paged table keys, so the
  AI picks its next work batch without ever loading the whole file.

A client connects via the repo-local `.mcp.json` (loopback SSE to the running IDE); the MCP dependency
is **optional**, so the viewer still loads on an IDE without (or with a disabled) MCP server.

## Build & run

A JDK 21 is required to *run* Gradle. This machine has no standalone JDK on PATH, so point
`JAVA_HOME` at a JetBrains Runtime 21 before running the wrapper, e.g. (PowerShell):

Run from the **repo root** with the module prefix:

```powershell
$env:JAVA_HOME = "<a JetBrains Runtime 21>"   # e.g. the jbr\ folder bundled with an installed JetBrains IDE
./gradlew :xlsx-editor:buildPlugin   # -> xlsx-editor/build/distributions/xlsx-editor-<ver>.zip
./gradlew :xlsx-editor:runIde        # launch Rider with the plugin loaded in a sandbox
```

`gradle.properties` (at the repo root) sets `riderLocalPath` to your installed Rider so the build
does **not** download the ~1.5 GB IDE distribution. Delete that line to download Rider 2026.1.3.

## Try it

1. `./gradlew :xlsx-editor:runIde` launches a sandbox Rider.
2. Open any `.xlsx` / `.xls` file — it opens in the read-only grid viewer.
3. Navigate with vim keys (`hjkl`, `gg`/`G`, `gt`/`gT`), filter with `/` or the column funnels,
   freeze headers with `Alt+Shift+F`, and press `?` for the shortcut cheat-sheet.

## Formulas

- **Display:** formula cells show their **cached result** (like Excel's normal view) — `.xlsx` via
  the SAX handler (`formulasNotResults=false`), `.xls` via `XlsWorkbookReader.render` (reads the
  cached result, not the formula text).
- **Visibility:** formula cells are **tinted** in the grid, and the status bar shows the formula of
  the active cell (e.g. `C2  •  ƒ =A2+B2  •  …`). Formula positions come from `XlsWorkbookReader`
  for `.xls` and from a lightweight `FormulaScanner` SAX pass run **in parallel** with the value
  parse for `.xlsx` (no real open-latency cost).
- **No in-IDE evaluation:** formulas are not evaluated in the viewer — POI can't compute many
  real-world functions, and a viewer doesn't need to. Cells show the cached result Excel last wrote;
  to recompute, open the file in Excel.

## VCS diff (Perforce/Git/…)

Comparing two revisions of a workbook offers **two viewers** — the grid opens by DEFAULT
(`order="first"` on the tool registration; the diff window opens the first applicable viewer), the
text projection stays in the diff window's viewer combo:

- **Excel 그리드** (`XlsxGridDiffTool`, a `diff.DiffTool`) — per-sheet **side-by-side grids** with a
  shared scroll: added rows green, removed rows red (a grey placeholder slot on the other side keeps
  alignment visible), modified rows tinted with the **changed cells highlighted bold**. Sheet tabs
  are starred when they contain changes (sheet-level add/remove is labeled); the first changed sheet
  is auto-selected and each sheet **auto-scrolls to its first change** when shown. Navigation is
  first-class: the platform's **차이점 이동** (F7/⇧F7, toolbar arrows) works via
  `PrevNextDifferenceIterable`, **vim keys are always on** (`hjkl` + counts, `gg`/`G`, `0`/`$`,
  `zz`/`zt`/`zb`, `H`/`M`/`L`, `Ctrl+D/U/E/Y`, and vim diff-mode's **`]c`/`[c`** change jumps), row
  selection mirrors across the two sides, and a **"변경만 보기"** toolbar toggle hides unchanged rows.
  Loading runs off the EDT; sheets beyond 100k rows fall back to a notice (use the text diff there).
- **TSV text projection** (`XlsxDecompiler`, a `filetype.decompiler` — the mechanism `.class` files
  use for decompiled diffs) — each sheet as `===== 시트: name =====` + one tab-joined line per row,
  deterministic and line-stable (one edited cell == one changed line), ~40 MB char cap. Without it a
  binary spreadsheet diff would just say "binary files differ".

Both viewers agree by construction: the grid's row alignment runs the platform `ComparisonManager`
over the same row keys the text projection emits ([XlsxDiffModel] is the shared, headless-tested
model). Diff-side revision files are read with a local-filesystem guard — VCS old-revision virtual
files report the original workspace path, which must not be read from disk (that would diff the
current file against itself).

## Known limitations

- The whole sheet is materialized as display strings (far lighter than POI objects, but not
  windowed). For multi-million-row files a truly virtualized/evicting model would be the next step.
- Formula results are the cached values from the file, not recomputed — open the file in Excel to
  refresh them.
- Large `.xls`/`.xlsx` need enough IDE heap (Help → Change Memory Settings); the legacy `.xls` path
  must build the whole HSSF workbook in memory (no streaming), so it's heavier than `.xlsx`.

## Sample files

`samples/sample.xlsx` (small, 2 sheets), `samples/sample-100k.xlsx` (100,000 rows), and
`samples/sample.xls` (legacy BIFF, 2 sheets) are included for testing the viewer.
