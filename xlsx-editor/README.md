# XLSX Grid Editor — Rider plugin

A frontend-only JetBrains Rider plugin (Kotlin) that opens `.xlsx` and `.xls` files in an editable
grid — with per-sheet **filtering** and an optional **vim mode** — and saves changes back using
Apache POI.

Target: **Rider 2026.1.3** (build 261) · **JDK 21** toolchain · IntelliJ Platform Gradle Plugin 2.16.0.

This is the `xlsx-editor` module of the [`rider-plugins`](../README.md) monorepo. The Gradle build
config (`settings.gradle.kts`, root `build.gradle.kts`, `gradle.properties`, the version catalog)
lives at the repo root; `PoiClassLoaders` / cached-formula formatting live in the shared `common`
module. This module's own files:

## Project layout

```
xlsx-editor/
  build.gradle.kts                       module build (IntelliJ Platform, Apache POI, :common dep)
  src/main/resources/META-INF/plugin.xml plugin descriptor (extensions + save listener)
  src/main/kotlin/com/example/xlsx/
    XlsxFileType.kt           binary file type for *.xlsx
    XlsxFileEditorProvider.kt accepts *.xlsx, creates the editor (HIDE_OTHER_EDITORS)
    XlsxFileEditor.kt         orchestrates open (xlsx/xls), tabs, lazy edit workbook, async save
    XlsxStreamingReader.kt    .xlsx SAX event-model reader (parallel per sheet)
    XlsWorkbookReader.kt      .xls (BIFF) usermodel reader (WorkbookFactory)
    SheetTableModel.kt        compact String-based model; streams rows, records edits + undo
    SheetPanel.kt             per-sheet UI: filter bar + table + sorter + combined row filter
    ColumnFilterController.kt Excel-style per-column value filter (header funnel + checkbox popup)
    VimGridController.kt      always-on modal vim keybindings for the grid
    GridStyling.kt            cell renderer, row-number gutter, formula cell editor
    XlsxSaveListener.kt       persists edits on Ctrl+S / Save All
  (shared in ../common: PoiClassLoaders.kt, CellFormatting.kt)
```

## How it works

- **Open (streaming, parallel, incremental):** `XlsxStreamingReader` opens the package, loads the
  shared-strings + styles once, then SAX-parses each sheet's XML. Sheets are parsed on background
  threads — **in parallel for multi-sheet workbooks** — and rows are pushed to the grid in batches
  (`BATCH_SIZE`), so the tab skeleton appears almost immediately and rows stream in. Cells are kept
  as compact display strings (`SheetTableModel`), not POI objects, so memory stays low.
- **Edit (overlay):** editing a cell updates the display string and records the change in the
  model's ordered op log (`EditOp`, drained on save via `drainOps`), flagging the editor modified. On
  the first edit, the full editable `Workbook` is built lazily in the background from the original
  bytes (on `poiExecutor`, the single thread that owns all workbook access).
- **Save (async, apply overlay → write):** `XlsxSaveListener.beforeAllDocumentsSaving` calls
  `editor.requestSave()`. For files **without** formulas the heavy serialization (`workbook.write` of
  a large sheet) runs on a background single thread (`poiExecutor`) and only the final
  `setBinaryContent` happens on the EDT — so a 100k-row save never freezes the UI. Cell-only edits are
  applied as an overlay (changed cells only, by sheet name + coordinates), preserving untouched
  content, styles, and other sheets. Sheets that had a **row insert/delete** are instead rebuilt from
  the model (see *Known limitations*), because POI's `shiftRows` is pathologically slow on large
  sheets. Files **with** formulas serialize synchronously on the EDT (their live workbook is
  EDT-confined and they are typically small).
- **POI + classloaders:** every POI call is wrapped in `withPoiClassLoader { ... }` so XMLBeans
  and JAXP service discovery use the plugin classloader (otherwise parsing fails inside the IDE).
- **.xls (legacy BIFF):** there is no clean SAX streaming for HSSF, but `.xls` is hard-capped at
  65,536 rows × 256 cols, so `XlsWorkbookReader` loads it fully via `WorkbookFactory` and feeds the
  same model. Save uses the same lazy `Workbook` (built by `WorkbookFactory`, so the original format
  — BIFF vs OOXML — is preserved automatically). No extra dependency (HSSF ships in `poi-core`).
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

  The sorter is attached **only after streaming finishes** with `setSortsOnUpdates(false)`, so edits
  never reshuffle rows; `JTable` converts view↔model indices so editing stays correct under a filter.
  Pressing **Enter** in the filter field applies the filter immediately and returns focus to the grid
  (so vim navigation resumes without a mouse click).
- **Vim mode (always on):** `VimGridController` installs modal keybindings on the table's
  `WHEN_FOCUSED` InputMap (and sets `JTable.autoStartsEdit=false`): `hjkl` move, `0` row start, `$`
  last cell **with data** in the row, `gg`/`G`,
  counts (`5j`), `Ctrl+D`/`Ctrl+U` half-page, `Ctrl+E`/`Ctrl+Y` scroll one line, `i`/`a`/Enter edit,
  `x` clear cell, `dd` **delete row**, `o`/`O` **add row below/above** (then edit), `yy` yank row,
  `p`/`P` **paste the yanked row(s) as new rows below/above**, `u` **undo** (cell edits, row
  insert/delete, paste — per-sheet undo stack), `.` **repeat last change** (`x`/`dd`/`p`/`P`, with a
  count), `V` **visual-line mode** (then `j`/`k` extend the row range, `d`/`x` delete, `y` yank, Esc
  cancel), `gt`/`gT` **next/previous sheet**, `/` focus filter. (`Ctrl+D`/`U`/`E`/`Y` and `Ctrl+Alt+F`
  use `registerCustomShortcutSet` to beat IDE-global bindings.) Insert mode is just the native cell editor (focus moves to it; Esc/Enter returns to Normal)
  — no mode indicator. Row insert/delete are recorded as an ordered op log; on save a sheet that had
  any row insert/delete is rebuilt from the model (fast, value-only — see *Known limitations*), while
  cell-only sheets and live-formula files replay edits onto the workbook (`shiftRows`). IdeaVim
  can't be reused — it targets text editors, not a grid.

- **Grid styling** (`GridStyling.kt`): an Excel-style row-number gutter (`RowNumberHeader`, painted
  from live table geometry so it tracks scroll/filter/edits), a `GridCellRenderer` (zebra striping,
  right-aligned numbers, bold first/header row), column auto-sizing from a row sample, and a status
  bar showing the current cell reference (e.g. `C5`), visible/total row counts, and active filters.

### Performance note (measured)

For this data (numeric, single sheet) streaming is **not faster in raw parse time** than the full
usermodel (100k rows ≈ 0.8–1.5 s either way; tiny files are slightly slower due to package-open
overhead). The wins are: lower **memory** (compact strings vs ~600k POI cell objects), faster
**perceived** open (skeleton + first rows in tens of ms instead of a ~1 s spinner), and **parallel
per-sheet** parsing for multi-sheet workbooks. Multithreading a single sheet's XML cannot help —
it is one sequential inflate + SAX stream (Amdahl), and POI workbooks are not thread-safe to share.

## Build & run

A JDK 21 is required to *run* Gradle. This machine has no standalone JDK on PATH, so point
`JAVA_HOME` at a JetBrains Runtime 21 before running the wrapper, e.g. (PowerShell):

Run from the **repo root** with the module prefix:

```powershell
$env:JAVA_HOME = "C:\Program Files\JetBrains\PyCharm 2025.2.1.1\jbr"
./gradlew :xlsx-editor:buildPlugin   # -> xlsx-editor/build/distributions/xlsx-editor-<ver>.zip
./gradlew :xlsx-editor:runIde        # launch Rider with the plugin loaded in a sandbox
```

`gradle.properties` (at the repo root) sets `riderLocalPath` to your installed Rider so the build
does **not** download the ~1.5 GB IDE distribution. Delete that line to download Rider 2026.1.3.

## Try it

1. `./gradlew :xlsx-editor:runIde` launches a sandbox Rider.
2. Open any `.xlsx` file (or create one) — it opens in the grid editor.
3. Edit a cell, press **Ctrl+S**, reopen the file to confirm the change was written.

## Formulas

- **Display:** formula cells show their **cached result** (like Excel's normal view) — `.xlsx` via
  the SAX handler (`formulasNotResults=false`), `.xls` via `XlsWorkbookReader.render` (reads the
  cached result, not the formula text).
- **Visibility:** formula cells are **tinted** in the grid, and the status bar shows the formula of
  the active cell (e.g. `C2  •  ƒ =A2+B2  •  …`). Formula positions come from `XlsWorkbookReader`
  for `.xls` and from a lightweight `FormulaScanner` SAX pass run **in parallel** with the value
  parse for `.xlsx` (no real open-latency cost). Editing a cell clears its formula mark; row
  insert/delete shifts the marks.
- **Live editing & recalculation:** for a file with formulas the editable workbook becomes a *live*
  evaluation engine (built eagerly in the background). Then:
  - editing a value cell **recomputes formulas** in the grid (`clearAllCachedResultValues` then
    `evaluateFormulaCell` over the workbook's formula cells, chained deps included — verified
    headlessly). It currently refreshes all formula cells, not just the edited cell's dependents, so
    this assumes formula files are modest in size;
  - double-clicking a formula cell shows the **formula text** (`=A2+B2`) in the editor (`FormulaCellEditor`);
  - typing a value that starts with `=` stores a **real formula** (`EditOp.SetFormula` → `setCellFormula`)
    and shows its result;
  - failures (POI-unsupported functions) keep the last cached value rather than showing an error.

  Edits apply to the live workbook directly, so **save just writes it** (`recalcFormulas` runs a final
  `evaluateAll`, with `setForceFormulaRecalculation(true)` as a fallback). Files without formulas use
  the lighter op-log path (no live engine). Untouched formula cells are preserved.

## Known limitations

- **Cell-only edits** are written as number (if the text parses cleanly) or text, and untouched
  cells, styles, formulas, merged regions, and other sheets are preserved (the overlay applies only
  the changed cells).
- **A sheet that had a row inserted/deleted is rebuilt from the model on save** (POI's `shiftRows`
  takes ~minutes on a 100k-row sheet). The rebuild is **value-only**: cell styles/number-formats for
  *that* sheet are not preserved (text-vs-number type is kept via a round-trip check, so codes like
  `01234` are not mangled). Files with formulas never take this path (they save via the live engine).
- The whole sheet is still materialized as display strings (far lighter than POI objects, but not
  windowed). For multi-million-row files a truly virtualized/evicting model would be the next step.
- First edit triggers a background build of the full editable workbook; for a formula file, pressing
  Ctrl+S before it finishes makes the (synchronous) save wait briefly for the build.
- For a **large formula** file, save is synchronous on the EDT and can pause briefly; the async path
  covers only non-formula files (formula files are assumed small).

## Sample files

`samples/sample.xlsx` (small, 2 sheets), `samples/sample-100k.xlsx` (100,000 rows), and
`samples/sample.xls` (legacy BIFF, 2 sheets) are included for testing the editor.
