# rider-plugins

JetBrains / Rider 플러그인(Kotlin) 모노레포입니다. IntelliJ Platform Gradle Plugin으로 빌드하며, 공용
인프라는 한 모듈에 두고 각 플러그인은 자체 모듈로 각자의 설치용 ZIP을 만듭니다.

## 구성

```
rider-plugins/
├─ settings.gradle.kts          모듈 등록
├─ build.gradle.kts             모든 모듈의 플러그인 버전 선언(apply false)
├─ gradle/libs.versions.toml    공유 의존성 버전(버전 카탈로그)
├─ gradle.properties            riderLocalPath + 공유 Gradle/Kotlin 플래그
├─ common/                      공용 라이브러리(POI 헬퍼; 그리드/vim 인프라가 이쪽으로 이동 예정)
│  └─ src/main/kotlin/…
├─ xlsx-editor/                 플러그인: IDE 내장 .xlsx/.xls 그리드 뷰어 (자체 README 참고)
│  ├─ build.gradle.kts
│  ├─ README.md
│  └─ src/main/{kotlin,resources/META-INF/plugin.xml}
└─ log-viewer/                  플러그인: 로컬 + 원격(SSH/SFTP) 로그 뷰어 (자체 README 참고)
   ├─ build.gradle.kts
   ├─ README.md
   └─ src/main/{kotlin,resources/META-INF/plugin.xml}
```

- **`common`** — 플러그인 간 공유 코드용 순수 Kotlin 라이브러리(현재 POI 스레드 컨텍스트 클래스로더 헬퍼와
  캐시된 수식 포매팅). `plugin.xml`이 없고, 의존하는 플러그인에 클래스가 번들됩니다
  (`implementation(project(":common"))`). 공유 UI(그리드 렌더러, vim 컨트롤러)가 이쪽으로 옮겨오면 플랫폼
  컴파일을 위해 `org.jetbrains.intellij.platform.module`도 적용할 예정입니다.
- **`xlsx-editor`** — 첫 번째 플러그인. Excel 파일을 IDE 내장 읽기 전용 그리드 뷰어로 엽니다(vim 내비게이션,
  필터, 고정 헤더). 동작은 [`xlsx-editor/README.md`](xlsx-editor/README.md) 참고.
- **`log-viewer`** — 두 번째 플러그인. 로컬 및 원격(SSH/SFTP) 로그 파일의 빠른 읽기 전용 뷰어(라이브 tail,
  필터/검색/하이라이트, 인코딩 선택, vim 내비게이션). 자세한 내용은
  [`log-viewer/README.md`](log-viewer/README.md) 참고.

## 빌드 & 실행

Gradle 실행에는 JDK 21이 필요합니다. 이 머신엔 PATH에 독립 JDK가 없으므로 먼저 `JAVA_HOME`을 JetBrains
Runtime 21로 지정하세요(PowerShell):

```powershell
$env:JAVA_HOME = "C:\Program Files\JetBrains\PyCharm 2025.2.1.1\jbr"

./gradlew :xlsx-editor:buildPlugin   # -> xlsx-editor/build/distributions/xlsx-editor-<ver>.zip
./gradlew :xlsx-editor:runIde        # 플러그인이 로드된 샌드박스 Rider 실행
```

`gradle.properties`의 `riderLocalPath` 덕분에 ~1.5 GB IDE를 받지 않고 로컬에 설치된 Rider로 컴파일합니다.
각 플러그인 모듈은 자체 `buildPlugin` / `runIde` 태스크가 있고, `./gradlew buildPlugin`(모듈 접두어 없이)은
모든 플러그인을 빌드합니다.

## 새 플러그인 추가

1. `<plugin>/build.gradle.kts` 생성(`xlsx-editor/build.gradle.kts`를 복사해 `plugin.xml` 조정).
2. `src/main/kotlin` + `src/main/resources/META-INF/plugin.xml` 추가.
3. `settings.gradle.kts`에 `include(":<plugin>")`.
4. 공유 코드는 `implementation(project(":common"))`로 재사용.

## 배포

각 플러그인은 자체 완결 ZIP(플러그인 + Apache POI 같은 번들 라이브러리)으로 배포됩니다. 다른 머신엔
**Settings → Plugins → ⚙ → Install Plugin from Disk…** 로 설치합니다(대상 IDE는 호환 빌드여야 함 —
`xlsx-editor`/`log-viewer` 모두 Rider 2026.1.x 대상). 릴리스는 플러그인별로 게시합니다(예:
`xlsx-editor-v0.3.0`, `log-viewer-v0.2.0` 태그의 GitHub Release에 해당 모듈 ZIP 첨부).
