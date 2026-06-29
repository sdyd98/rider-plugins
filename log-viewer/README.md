# Log Viewer

A fast, read-only viewer for **local and remote (SSH/SFTP)** log files inside Rider (or any
IntelliJ-based IDE). It is the second plugin in the `rider-plugins` monorepo and is a sibling of
`xlsx-editor`, sharing its design language: a virtualized Swing grid, always-on vim navigation, a
filter-only `TableRowSorter`, and a Compose/Jewel chrome.

## Features

- **Local & remote.** Open a local `.log` in the editor, or manage SSH connections in the **Log
  Viewer** tool window and live-tail remote logs over SSH (`tail -F`) / browse them over SFTP.
- **Live follow.** Both local and remote sources tail appended lines; the **Follow** toggle sticks
  the view to the bottom as lines arrive (and naturally pauses when you scroll up to read).
- **Search / filter / highlight.** Regex filter across all lines, per-level toggles
  (ERROR/WARN/INFO/DEBUG/TRACE) with live counts, and GrepConsole-style custom **highlight rules**
  (color + bold by regex). Search matches are highlighted in place.
- **Structure analysis.** Stack traces and indented payloads fold into collapsible blocks;
  `J` pretty-prints an embedded JSON payload; `T` jumps to a timestamp.
- **Vim navigation.** `hjkl`, counts, `gg`/`G`, `Ctrl+D/U`, `zz/zt/zb`, `H/M/L`, `/` filter,
  `n`/`N` search, `]e`/`[e` jump to next/prev ERROR (e/w/i/d/t = level), `za` fold, `m`/`` ` `` marks.
  Press `?` in the grid for the full cheat sheet.

This is a viewer — it never writes to the file or the remote host.

## Build & run

The repo has no standalone JDK/Gradle on PATH; point `JAVA_HOME` at a JetBrains Runtime 21 first.

```bash
JAVA_HOME="C:/Program Files/JetBrains/PyCharm 2025.2.1.1/jbr" ./gradlew :log-viewer:buildPlugin
JAVA_HOME="C:/Program Files/JetBrains/PyCharm 2025.2.1.1/jbr" ./gradlew :log-viewer:runIde
```

`buildPlugin` produces `log-viewer/build/distributions/log-viewer-0.1.0.zip` (bundling the JSch jar).
A sample log to try is in `samples/logs/server.log` (timestamps, levels, a stack trace, JSON payloads).

## Remote (SSH) setup

Open the **Log Viewer** tool window (bottom) → **연결 추가** to pick from your existing **Rider SSH
configurations** (Settings → Tools → SSH Configurations) — host/port/user/key come from Rider; no
manual host entry. The password is prompted once on first use and cached in the IDE's secure
**PasswordSafe** — never in the serialized profile. A connection starts at the root `/`; expand to
browse and right-click a folder to *pin it as a log root* (only `.log` files and folders that contain
them are shown). Host-key checking is disabled (developer tool, machines you control).

## Architecture

- **Domain** — `LogLevel`, `LogLine` (raw text + parsed level/timestamp + block ownership),
  `LogParser` (token-based level + best-effort timestamp + continuation detection), `HighlightRule`.
- **Readers** (`LogReader`) — `LocalLogReader` (a polled byte cursor: resumes at the last offset,
  buffers partial lines, detects rotation/truncation) and `ssh/RemoteLogReader` (JSch exec
  `tail -n N` for the initial read, `tail -n 0 -F` for the live follow; SFTP for browsing). Both
  decode UTF-8 by only consuming bytes up to a `\n`, so emitted lines never split a multi-byte char.
- **Model** (`LogTableModel`) — append-only Time | Level | Message columns; multi-line records
  grouped into blocks (`blockStart`); folding hides continuation rows; a live-tail cap trims the
  oldest quarter and shifts every block/fold/mark index down.
- **View** (`LogViewerPanel`) — a `JBTable` with a filter-only `TableRowSorter` (level + regex +
  fold), custom-painted renderers (`LogCellRenderer` for the Message column, `LogColumnRenderers`
  for Time/Level — severity/rule tint + in-place search highlight), a `LogGutter` (severity dot +
  line number + fold triangle), a `VimLogController`, and the Compose/Jewel chrome (`LogChrome`).
  Reused by both the editor and the tool-window tabs. The grid stays Swing for large-file performance.
- **Persistence** — `ssh/RemoteConnectionStore` (profiles), `SshSecrets` (PasswordSafe),
  `rules/HighlightRulesStore` (coloring rules), all application-level.

## Dependencies

- **JSch** (the maintained `com.github.mwiede:jsch` fork) — bundled into the plugin's `lib/` for SSH
  exec + SFTP. A single self-contained jar; password / RSA / ECDSA auth needs no BouncyCastle.
- **Compose + Jewel** — provided by the IDE at runtime (referenced via `bundledLibrary`, not shipped).
