# XLSX Grid Viewer — Rider plugin

A frontend-only JetBrains Rider plugin (Kotlin) that opens `.xlsx` and `.xls` files in a fast,
**read-only** grid viewer — with per-sheet **filtering**, frozen header rows, and always-on
**vim-style navigation**. Built for browsing large multi-sheet data tables; it reads with Apache
POI (streaming) and never modifies the file.

On top of the viewer it adds a **relationship-graph explorer** for game-data workbooks — an ER map,
record-level data lineage, and an integrity check driven by a `refs.json` schema — plus a
deterministic `refs.json` generator and MCP tools so an AI client can author that schema
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
    RefGraphModel.kt          ER / record view-model (+ mock fallback) + TableColor.kt node colors
    RelationshipSchema.kt     refs.json parse + buildRefGraph + IndexRecordGraph (real data path)
    GameDataLoader.kt         streaming POI index (+ on-disk .idx cache)
    RelationshipNavigation.kt schema resolution (nearest refs.json) + graph→grid navigation
    RelationshipBus.kt        grid→graph event bus (Ctrl+R)
    SchemaInferencer.kt       deterministic refs.json drafter (value-overlap FK inference)
    RefsMcpToolset.kt         7 MCP tools for refs.json authoring (IDE built-in MCP server)
  (shared in ../common: PoiClassLoaders.kt, CellFormatting.kt)
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
  - **Other:** `V` visual-line select (then `j`/`k` extend, Esc cancel — `Ctrl+C` copies), `gt`/`gT`
    next/previous sheet, `/` focus filter, `?` shortcut cheat-sheet popup.

  (`Ctrl+D`/`U`/`E`/`Y` and `Ctrl+Alt+F` use `registerCustomShortcutSet` to beat IDE-global bindings.)
  The grid is read-only, so there are no editing commands. IdeaVim can't be reused — it targets text
  editors, not a grid.

- **Frozen header rows + column jump:** press **`Alt+Shift+F`** to freeze the top rows through the
  cursor (model rows `0..current`); press it again to **unfreeze**. Frozen rows pin to the top while
  scrolling **and** stay visible through filtering. Frozen rows live in a separate `frozenTable` placed in the scroll pane's column-header
  view, sharing the main table's column model so widths stay in sync; the main table's `RowFilter`
  excludes them. One frozen row is the **key row** (`Alt+K` cycles it) whose values name the columns
  in the **`Alt+\`** jump popup (type to filter, choose to move the cursor to that column).

- **Grid styling** (`GridStyling.kt`): an Excel-style row-number gutter (`RowNumberHeader`, painted
  from live table geometry so it tracks scroll/filter), a `GridCellRenderer` (zebra striping,
  right-aligned numbers, bold first/header row), and column auto-sizing from a row sample.
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
  that has no row) and orphan records (rows nothing references); the tab badges the broken count.

**Navigation is bidirectional:** press **`Ctrl+R`** on a grid row to centre the explorer on that
record (`RelationshipBus` → tool window); click a graph node to open its workbook and select the
row (`RelationshipNavigation` → `XlsxFileEditor.revealSheetRow`).

**`refs.json`** describes each table (`file`, `sheet`, `headerRow`, `dataStartRow`, `id`, `display`)
and its `refs` (a `from` column → a `to` table, with `by` / `split` for grouped or delimited
multi-value refs). The graph reads workbooks with streaming POI and a compact index cached on disk
(`.idx`, keyed by file mtime/size + `refs.json`), so reopening is fast and nothing is held in memory
whole. See `samples/gamedata/refs.json` for a worked example.

Keep **one `refs.json` at the top of your data tree**: the views use the nearest `refs.json` at or
above the open workbook, and their cost scales with the table count in *that schema* — not the number
of workbooks on disk — so a single top-level file scales to thousands of tables.

**Authoring `refs.json`** — you author it through the IDE's MCP server with an AI client (e.g. Claude
Code), not by hand. `RefsMcpToolset` exposes eight tools — `build_refs`, `list_tables`, `column_values`,
`sample_rows`, `suggest_refs`, `read_refs`, `write_refs`, `validate_refs` — and the AI drives them.

The primary path is **`build_refs(<top folder>)`**: it samples every sheet under the folder, infers
foreign keys across all of them via an inverted value→table index (so cross-folder references are found
and a multi-thousand-table tree drafts in **seconds**), writes one top-level `refs.json`, and returns a
**summary** (counts + low-confidence refs to review) rather than the whole schema — so it never floods
the AI's context. Re-runs only ADD newly-found tables, so manual refinements survive: point it at the
top once, then refine the low-confidence refs (and any the value-overlap heuristic can't decide, e.g.
polymorphic columns) with `read_refs` / `write_refs`. `suggest_refs` runs the same inference for a single
folder without writing. A client connects via the repo-local `.mcp.json` (loopback SSE to the running
IDE); the MCP dependency is **optional**, so the viewer still loads on an IDE without (or with a
disabled) MCP server.

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
