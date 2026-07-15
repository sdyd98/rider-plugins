# UI 가이드라인 — 모던 IntelliJ 플랫폼 UI 작성 규칙

이 레포의 플러그인 UI를 작성/수정할 때 따르는 규칙. 대부분 실제로 겪은 문제에서 나온 것이므로
"왜" 열의 사례를 무시하지 말 것. (대상: Rider 2026.1 / build 261 기준 IntelliJ 플랫폼)

## 1. 설계 언어 (레포 관례 — CLAUDE.md의 architecture invariants와 동일)

- **데이터 그리드는 Swing** (`JBTable`) — 대용량 성능 때문에 의도적 선택. Compose로 그리드를 만들지 말 것.
- **크롬(필터 바·상태 바·팝업·설정)은 Compose/Jewel** — `ComposeChrome.kt`, `LogChrome.kt`가 기준 예시.
  단, **플랫폼이 크롬을 제공하는 곳에서는 플랫폼을 쓴다**: 디프 뷰어의 툴바/상태 패널은
  `FrameDiffTool.ToolbarComponents`, 툴윈도우 제목줄 액션 등.
- **모든 뷰어는 read-only.** 편집/저장 경로를 만들지 않는다.
- **vim은 항상 켜져 있다** — 새 그리드에는 `:common`의 `VimTableController`를 상속한 컨트롤러를 붙인다
  (`VimGridController`, `VimLogController`, `VimDiffGridController`가 기준 예시).

## 2. 컴포넌트 선택 — 날 것 Swing 금지, JB 대응물 사용

| 쓰지 말 것 | 대신 쓸 것 | 왜 |
|---|---|---|
| `JSplitPane` | `OnePixelSplitter` (또는 `JBSplitter`) | 1px 디바이더, 테마 연동. JSplitPane은 OS 룩앤필이 샌다 |
| `JTabbedPane` | `JBTabbedPane` | IDE 스타일 탭 |
| `JScrollPane` | `JBScrollPane` | JB 스크롤바/부드러운 스크롤 |
| `JTable` | `JBTable` | 테마·스트라이핑·빈 텍스트 |
| `JLabel` | `JBLabel` | 테마 폰트/색 |
| `JComboBox` | `com.intellij.openapi.ui.ComboBox` | 테마 렌더링 |
| `new JBColor` 없이 `Color` 하나 | `JBColor(light, dark)` | 다크 테마에서 깨짐 |
| `setBorder(EmptyBorder(px))` | `JBUI.Borders.empty(...)` | 스케일 대응 |

## 3. 색상 — 의미 있는 색은 스킴에서 읽는다

- **디프 색**: `TextDiffType.INSERTED/DELETED/MODIFIED` — `getColor(null)`(강조) /
  `getIgnoredColor(null)`(옅은 변형). 텍스트 디프와 같은 소스라 사용자 스킴 편집·테마 전환이 그대로
  적용된다. **페인트 시점마다 읽을 것** (캐시하면 테마 전환에 안 따라감).
- **예외 — 확정 결정: 삭제는 붉은 계열.** IntelliJ 기본 스킴의 DELETED는 회색인데, 데이터 그리드에서
  회색 삭제는 "아무것도 아님"으로 읽힌다. 삭제 행(+반대편 플레이스홀더)은 명시적 red `JBColor` 쌍을
  쓴다 (`DiffCellRenderer` 참조, 메모리 `diff-deletions-red`).
- 의미 없는 장식(그리드 라인, 행 번호 회색 등)은 `JBColor(light, dark)` 쌍으로 직접 지정해도 된다.
- 에디터 텍스트 색·폰트가 필요하면 `EditorColorsManager.getInstance().globalScheme`.

## 4. 크기 — 픽셀 값은 반드시 스케일링

- 모든 고정 px는 `JBUI.scale(n)` (컬럼 폭, 최소 크기 등). 안 하면 HiDPI/200% 스케일에서 반토막.
- 여백은 `JBUI.Borders.*` / `JBUI.insets(...)`.

## 5. 스레딩 — EDT를 절대 막지 않는다

- **무거운 로드는 `executeOnPooledThread` + "로딩 중" 라벨 → `invokeLater`로 교체** 패턴
  (`XlsxGridDiffViewer.loadAndBuild` 참조). POI 호출은 `withPoiClassLoader` 필수.
- **생산자는 소비자를 기다리며 블록하면 안 된다.** 특히 **EDT에서 오는 콜백**(콘솔 `textAdded`,
  리스너)이 "EDT가 소화할 때까지 블록"하는 백프레셔와 동기로 만나면 **EDT가 자기 자신을 기다리는
  교착 = IDE 전체 멈춤**. 실제 사고: log-viewer 0.5.0 디버그 탭. 해법: 큐 + 전용 전달 스레드
  (`ProcessOutputBuffer` 참조).
- dispose는 리소스 소유자에 연결: `Content.setDisposer`, `Disposer.register`, 리스너의
  parentDisposable 파라미터.

## 6. 스크롤/테이블 — 이번에 밟은 지뢰들

- **두 스크롤 영역 동기화는 뷰포트 직접 동기화**로 한다 (`viewport.addChangeListener` + 위치 복사 +
  동등성 가드). **스크롤바 `BoundedRangeModel` 공유 금지** — `JBScrollPane`의 스크롤바 UI는 모델
  교체 후 리스너를 안 옮겨서 스크롤이 통째로 죽는다.
- **`VERTICAL_SCROLLBAR_NEVER` 금지** — Swing 휠 핸들러는 대상 스크롤바가 화면에 보이지 않으면 휠
  이벤트를 버린다. 스크롤바를 숨긴 영역 위에서는 휠이 죽는다.
- **정렬은 끄고 필터만**: `TableRowSorter` + 전 컬럼 `setSortable(false)`. 스트리밍 중에는 정렬러를
  떼었다가 완료 후 장착 (행 append가 재정렬을 유발하면 안 됨).
- **view↔model 인덱스 변환은 필터가 없어도 항상** (`convertRowIndexToModel/View`) — 나중에 필터가
  붙는 순간 렌더러/네비게이션이 어긋난다.

## 7. 액션/키/데이터 컨텍스트

- 액션은 `DumbAwareAction`; 툴바 토글은 `ToggleAction`. **`getActionUpdateThread()`를 반드시
  명시** (대개 `ActionUpdateThread.EDT`).
- IDE 전역 바인딩을 이겨야 하는 단축키(`Ctrl+D/U/E/Y` 등)는 InputMap이 아니라
  `registerCustomShortcutSet(component)` (vim 베이스가 이미 처리).
- 컴포넌트가 플랫폼 액션에 데이터를 제공할 때는 **`UiDataProvider.uiDataSnapshot(DataSink)`**
  (구 `DataProvider.getData(String)`는 deprecated). 예: 디프 뷰어의 F7 =
  `DiffDataKeys.PREV_NEXT_DIFFERENCE_ITERABLE` 제공.

## 8. 플랫폼 통합 확장점 — 기준 예시

| 하고 싶은 것 | 확장점/API | 레포 예시 |
|---|---|---|
| 커스텀 디프 뷰어 | `diff.DiffTool` (+`order="first"`로 기본화) | `XlsxGridDiffTool` |
| 바이너리를 디프 가능한 텍스트로 | `filetype.decompiler` | `XlsxDecompiler` |
| 디버그/실행 탭에 콘텐츠 추가 | `RunnerLayoutUi.createContent/addContent` | `DebugLogTabListener` |
| 콘솔 출력 구독 | `ObservableConsoleView.addChangeListener` + `getText()` 시딩 (둘 다 EDT) | `DebugLogTabListener` |
| 커스텀 파일 에디터 | `fileEditorProvider` (+`HIDE_OTHER_EDITORS`) | `XlsxFileEditorProvider` |
| 툴윈도우 | `toolWindow` + Compose 호스트 | `RefGraphToolWindow` |

주의: VCS 디프의 이전 리비전 가상 파일은 **원본 로컬 경로를 path로 보고한다** — 바이트를 읽을 때
`isInLocalFileSystem` 가드 필수 (`readWorkbookBytes` 참조). 안 하면 현재 파일을 자기 자신과 비교하게 된다.

## 9. 테스트 가능성 — UI와 모델을 분리

- 순수 로직(정렬/페어링/버퍼링/파싱)은 플랫폼 타입 없는 객체로 분리해 헤드리스 테스트를 붙인다.
  기준 예시: `XlsxDiffModel`(+`XlsxDiffModelTest`), `ProcessOutputBuffer`(+Test),
  `VimTableController`(+Test). 플랫폼 글루(뷰어/리스너)는 얇게 유지.
- 이 레포의 헤드리스 테스트는 IDE 런타임 없이 돈다 — 테스트에 플랫폼 UI 타입을 끌어들이지 말 것.

## 10. 완료 기준

- `./gradlew :<module>:test :<module>:buildPlugin` 통과.
- 실기기 확인이 필요한 UI는 `runIde` 샌드박스에서 검증 (테스트 데이터: `samples/`).
- 라이트/다크 테마 둘 다 확인 (특히 색을 만졌으면).
- 모듈 README(+log-viewer는 CHANGELOG) 갱신.
