# Log Viewer

Rider(및 모든 IntelliJ 기반 IDE)에서 **로컬 및 원격(SSH/SFTP)** 로그 파일을 보는 빠른 **읽기 전용** 뷰어입니다.
`rider-plugins` 모노레포의 두 번째 플러그인이며 `xlsx-editor`의 형제 플러그인으로, 같은 설계 언어를 공유합니다 —
가상화된 Swing 그리드, 항상 켜진 vim 내비게이션, 필터 전용 `TableRowSorter`, 그리고 Compose/Jewel 크롬.

## 기능

- **로컬 & 원격.** 로컬 `.log`를 에디터에서 열거나, **로그 뷰어** 툴윈도우에서 SSH 연결을 관리하며 원격 로그를
  SSH(`tail -F`)로 라이브 tail 하거나 SFTP로 탐색합니다.
- **라이브 follow.** 로컬·원격 모두 append되는 줄을 따라갑니다. **Follow** 토글은 새 줄이 들어올 때 화면을 하단에
  고정하고, 위로 스크롤해 읽으면 자연스럽게 멈춥니다.
- **검색 / 필터 / 하이라이트.** 전체 줄 정규식 필터, 레벨별 토글(ERROR/WARN/INFO/DEBUG/TRACE, 라이브 카운트),
  GrepConsole 스타일 커스텀 **하이라이트 규칙**(정규식으로 색 + 볼드). 검색 매치는 제자리에서 하이라이트됩니다.
- **구조 분석.** 스택트레이스와 들여쓰기된 페이로드는 접을 수 있는 블록으로 묶입니다. `J`는 줄 안의 JSON을 정렬해
  보여주고, `T`는 특정 시각으로 이동합니다.
- **사용자 정의 줄 형식.** Time/Level/Message 분리 기준을 직접 지정합니다(⚙ → 줄 형식). 템플릿
  `%{time} [%{thread}] (%{level}) %{message}` 또는 정규식을 순서대로 시도하고, 안 맞으면 내장 자동 분석으로
  폴백. Compose 설정 화면에서 현재 로그로 실시간 미리보기.
- **vim 내비게이션.** `hjkl`, 카운트, `gg`/`G`, `Ctrl+D/U`, `zz/zt/zb`, `H/M/L`, `/` 필터, `n`/`N` 검색,
  `]e`/`[e` 다음/이전 ERROR로 점프(e/w/i/d/t = 레벨), `za` 접기, `m`/`` ` `` 마크. 그리드에서 `?`를 누르면 전체
  단축키 표가 나옵니다.
- **인코딩 & 폰트.** ⚙ 표시 옵션 → 인코딩(Compose/Jewel 피커)에서 **UTF-8 / CP949·Windows-949(한글·ANSI) /
  EUC-KR** 중 고릅니다(선택 시 즉시 재읽기) — CP949/EUC-KR 한글 로그가 깨질 때 바로 고칩니다. 로그 본문 폰트는
  Rider **에디터 폰트**(Settings → Editor → Font)를 따라갑니다.

읽기 전용입니다 — 파일이나 원격 호스트에 아무것도 쓰지 않습니다.

## 빌드 & 실행

이 레포는 PATH에 독립 JDK/Gradle가 없습니다. 먼저 `JAVA_HOME`을 JetBrains Runtime 21로 지정하세요.

```bash
JAVA_HOME="C:/Program Files/JetBrains/PyCharm 2025.2.1.1/jbr" ./gradlew :log-viewer:buildPlugin
JAVA_HOME="C:/Program Files/JetBrains/PyCharm 2025.2.1.1/jbr" ./gradlew :log-viewer:runIde
```

`buildPlugin`은 `log-viewer/build/distributions/log-viewer-0.1.0.zip`을 만듭니다(JSch jar 포함).
시험용 샘플 로그는 `samples/logs/server.log`에 있습니다(타임스탬프, 레벨, 스택트레이스, JSON 페이로드).

## 원격(SSH) 설정

**로그 뷰어** 툴윈도우(하단) → **연결 추가**로 기존 **Rider SSH 설정**(Settings → Tools → SSH Configurations)에서
고릅니다 — host/port/user/key는 Rider에서 가져오며 직접 입력하지 않습니다. 비밀번호는 최초 사용 시 한 번만 묻고
IDE의 보안 저장소 **PasswordSafe**에 캐시됩니다(직렬화된 프로필에는 절대 저장되지 않음). 연결은 루트 `/`에서
시작하고, 펼쳐서 탐색한 뒤 폴더를 우클릭해 *로그 루트로 고정*합니다(`.log` 파일과 그것을 포함한 폴더만 표시).
Host-key 검사는 꺼져 있습니다(직접 관리하는 머신 대상의 개발 도구).

## 구조

- **도메인** — `LogLevel`, `LogLine`(원문 + 파싱된 레벨/타임스탬프 + 블록 소속), `LogParser`(토큰 기반 레벨 +
  best-effort 타임스탬프 + continuation 감지), `HighlightRule`.
- **리더**(`LogReader`) — `LocalLogReader`(폴링 바이트 커서: 마지막 오프셋에서 재개, 부분 줄 버퍼링, 회전/절단
  감지)와 `ssh/RemoteLogReader`(JSch exec — 초기 읽기는 `tail -n N`, 라이브 follow는 `tail -n 0 -F`, 탐색은
  SFTP). 둘 다 `\n`까지만 바이트를 소비해 UTF-8을 디코딩하므로 멀티바이트 문자를 쪼개지 않습니다. 공용
  `ByteLineSplitter`가 바이트→줄 분리를 담당합니다.
- **모델**(`LogTableModel`) — append-only Time | Level | Message 컬럼. 멀티라인 레코드는 블록(`blockStart`)으로
  묶이고, 접기는 continuation 행을 숨깁니다. 라이브 tail 상한에 도달하면 가장 오래된 1/4을 잘라내고 모든
  블록/접기/마크 인덱스를 당깁니다.
- **뷰**(`LogViewerPanel`) — 필터 전용 `TableRowSorter`(레벨 + 정규식 + 접기)를 단 `JBTable`, 커스텀 렌더러
  (`LogCellRenderer`는 Message 컬럼, `LogColumnRenderers`는 Time/Level — 심각도/규칙 틴트 + 제자리 검색
  하이라이트), `LogGutter`(심각도 점 + 줄 번호 + 접기 삼각형), `VimLogController`, 그리고 Compose/Jewel
  크롬(`LogChrome`). 에디터와 툴윈도우 탭이 함께 재사용합니다. 대용량 파일 성능을 위해 그리드는 Swing을 유지합니다.
- **영속성** — `ssh/RemoteConnectionStore`(프로필), `SshSecrets`(PasswordSafe), `rules/HighlightRulesStore`
  (색상 규칙). 모두 애플리케이션 레벨 서비스입니다.

## 의존성

- **JSch**(유지보수되는 `com.github.mwiede:jsch` 포크) — SSH exec + SFTP용으로 플러그인 `lib/`에 번들. 자체 완결
  단일 jar이며 password / RSA / ECDSA 인증에 BouncyCastle이 필요 없습니다.
- **Compose + Jewel** — IDE가 런타임에 제공합니다(`bundledLibrary`로 참조, ZIP에는 포함하지 않음).

## 테스트

```bash
JAVA_HOME="C:/Program Files/JetBrains/PyCharm 2025.2.1.1/jbr" ./gradlew :log-viewer:test
```

`LogPipelineTest`는 바이트→줄→파싱→모델 데이터 경로(`ByteLineSplitter`, lazy `LogLine` 필드, `LogTableModel`,
ANSI 처리)를 IDE/Swing UI 없이 헤드리스로 검증합니다.
