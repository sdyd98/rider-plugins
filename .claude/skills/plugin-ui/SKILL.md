---
name: plugin-ui
description: >
  Design system + UI rules for building JetBrains-plugin UI in this repo (xlsx-editor, log-viewer).
  Read BEFORE writing or changing any UI code: Swing grid components, Compose/Jewel chrome (filter
  bars, status bars, popups), renderers, colors/theming, keyboard/vim bindings, or any new panel,
  tool window, or visualization. Not needed for pure logic (parsers, readers, models) or build files.
---

# Plugin UI — rules for this repo

## The one architectural split (never violate)

**Data grids are Swing; everything around them is Compose/Jewel.**

- Grid = virtualized `JBTable` + custom-painted `TableCellRenderer`. Swing is kept deliberately:
  it survives million-row models where Compose lists don't. Never rebuild a data grid in Compose.
- Chrome = filter bar, status bar, popups, settings panels → Compose via `JewelComposePanel`
  (see `LogChrome.kt`, `ComposeChrome.kt`). Never build new chrome in raw Swing.
- Both plugins share this language plus: filter-only `TableRowSorter` (sorting disabled on every
  column; attach the sorter only AFTER initial streaming — see `LogViewerPanel.ensureSorterAttached`),
  and an always-on modal vim controller extending `:common`'s `VimTableController`.
- Both plugins are **strictly read-only viewers**. No edit/save/write-back UI, ever.

## JetBrains platform rules (HiDPI + theming)

- **Never hardcode pixels.** Every size/inset/border goes through `JBUI.scale(n)`,
  `JBUI.Borders.*`, `JBUI.insets(...)`. A raw `new Dimension(200, 24)` is a bug on HiDPI.
- **Never hardcode colors.** Every color must be theme-aware:
  - Swing: `JBColor(lightRgb, darkRgb)` or `JBColor.namedColor(...)`; generic surfaces via
    `UIUtil.getPanelBackground()`, `UIUtil.getTableSelectionBackground(...)`, `JBColor.border()`.
  - Log-viewer severity/accent colors live ONLY in `LogStyling` — extend it, don't scatter colors.
  - Compose chrome colors come from `rememberLogPalette()` (`LogUi.kt`) — extend the palette,
    don't inline `Color(0x...)` in composables (exception: alpha-tinted derivations of palette/
    accent colors).
- **Prefer JB components** over raw Swing: `JBTable`, `JBScrollPane`, `JBLabel`, `OnePixelSplitter`,
  `JBPopupFactory`. They track theme + density for free.
- Fonts: log/grid body text uses the editor font via `LogFonts` — don't `new Font(...)`.

## Keyboard & vim

- Plain character keys → the vim controller's `keyChars` + `pressChar` dispatch (installed on the
  table's `WHEN_FOCUSED` InputMap by `VimTableController`). Check `keyChars` for conflicts before
  claiming a new key, and update the `?` cheat sheet (`ComposeLogHelp` / `ComposeHelp`) in the
  same change.
- Shortcuts that must beat IDE-global bindings (`Ctrl+D/U/E/Y`, `Enter`, `Ctrl+Alt+F`, …) →
  `registerChord(...)` (a `DumbAwareAction` + `registerCustomShortcutSet`), NOT the InputMap.
- Marks/navigation state must be stored in MODEL coordinates and converted view↔model explicitly,
  so it survives filtering and streaming.

## EDT / performance contract (large files are the norm here)

- Parsing, regex work, format detection, grouping → OFF the EDT; model mutation + Swing → ON it.
- Anything producing batches for the EDT must be bounded (see the `MAX_INFLIGHT_BATCHES`
  semaphore in `LogViewerPanel`) — an unbounded `invokeLater` queue froze the IDE on a 3 GB file.
- Renderers may only do per-cell work proportional to VISIBLE rows (~50). Full-model scans on a
  paint or per-keystroke path need an index (see per-level `IntVec` in `LogTableModel`) or a memo
  (see `LogRowFilter`).
- Measure text with `FontMetrics` only behind a cheap upper-bound guard (see `sizeContentColumnFor`).
- `LogLine`'s cached getters (`message`, `timeText`, spans, …) are EDT-only by contract.

## Visualizations (heatmaps, stripes, charts)

- Follow `LogHeatmap` as the template: aggregate into pixel buckets first (O(rows + height)),
  paint runs, never one fillRect per row; repaint via listeners (model/sorter/scrollbar), never a
  polling timer.
- Colors: reuse severity colors from `LogStyling.levelDot/levelBackground`; any new palette entry
  needs a light AND dark value chosen together (check both themes before calling it done).
- Interactive overlays (click-to-jump) map through model coordinates and go through the panel's
  `seekToModelRow` so filtering/folding is respected.

## Text & language

- UI strings are **Korean** (buttons, tooltips, popups, status text) — match the existing tone:
  short, plain, no honorifics ("중지", "에러 요약", "앞 2.9 GB 건너뜀"). Code, comments, commit
  messages stay English.
- Counts are formatted with thousands separators (`"%,d"`), sizes via the panel's `fmtBytes`.

## Popups

Use the established pattern (`ComposeLogHelp`, `ComposeErrorSummary`): a `create*Panel(): JComponent`
returning `JewelComposePanel { ... }`, shown with `JBPopupFactory.createComponentPopupBuilder`,
hover rows via `MutableInteractionSource` + `animateColorAsState`, Esc/click-outside to close.
For list-style popups cap the visible items and say what was cut ("… 외 N종류").

## Definition of done for any UI change

1. `./gradlew buildPlugin` passes (the standard verification — there is no UI test harness).
2. Pure logic extracted from the panel and covered by headless tests (pattern: `LogRowFilter`,
   `ErrorSummary`, `LogSourceLink.find`).
3. Works in light AND dark theme; no unscaled pixels.
4. `?` cheat sheet updated if keys changed; module README + `log-viewer/CHANGELOG.md` updated for
   behavior changes.
5. Nothing writes to files or remote hosts.
