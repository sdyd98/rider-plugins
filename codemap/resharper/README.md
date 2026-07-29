# 코드맵 리샤퍼 백엔드

Rider의 .NET 백엔드(ReSharper 호스트) 안에서 도는 코드맵의 나머지 절반입니다. **아직 실용 기능은 하나도 없습니다** — 지금 있는 건 "이 방식이 가능한가"를 확인한 스파이크입니다.

## 왜 필요한가

JVM 쪽(코드맵 플러그인 본체)에는 **C++ 심볼이 없습니다.** CIDR/OC PSI 클래스도, `gotoSymbol`/`chooseByName` 확장 지점도 없고, `cidr.lang.navigatableSymbolSearcherBridge` 는 선언만 있고 구현이 IDE 전체에 하나도 없습니다. 프론트엔드가 백엔드에 닿는 유일한 길은 액션을 흉내내는 것(사용처 찾기)뿐입니다.

그래서 지금까지 함수 위치는 **AI가 노트에 적어둔 시그니처를 본문에서 텍스트로 찾는** 방식이었습니다. 앵커가 안 맞으면 그 함수는 회색으로 죽습니다. 이 어셈블리는 그 자리를 진짜 파서로 채우기 위한 것입니다.

역할 분담은 그대로입니다 — **사실은 리샤퍼가, 해석은 AI가.** 리샤퍼는 `HandleLogin` 이 어디 있는지 알려주지만, "이 락을 쥔 채 들어간다"는 판단은 못 합니다.

## 확인된 것 (2026-07-30)

| 단계 | 결과 |
|---|---|
| 서드파티 어셈블리를 호스트가 로드 | ✅ |
| 솔루션 컴포넌트로 `ISolution` 확보 | ✅ |
| **C++ 심볼 조회** | ✅ 실제 선언·오프셋 획득 |

스파이크가 실제로 뱉은 것:

```
spike: 3 source files
  Main.cpp: bool Session::HandleLogin(const LoginReq& req, int retries) @ 39
  Main.cpp: bool Session::Authenticate(const char* account) @ 178
  Main.cpp: void Session::Close() @ 282
```

경로는 `IPsiSourceFile → GetPsiFiles(CppLanguage.Instance) → Descendants<SimpleDeclaration> → CppFunctionDeclaration.TryCreateFromFunctionDeclaration`. C++ 함수는 자기 노드 타입이 없습니다 — 모든 선언이 `SimpleDeclaration` 이고, 거기서 함수 선언이 만들어지느냐가 판별 기준입니다.

## 아직 안 되는 것

- **헤더의 선언이 안 잡힙니다.** 위 결과에 `Session.h` 가 없습니다. 노트는 `.h` 기준이라 이건 실용화의 전제 조건입니다
- 정의/선언 구분이 전부 "선언"으로 나옵니다 — `HasBody` 를 잘못 읽고 있습니다
- 캐시 준비를 2초 간격 폴링으로 기다립니다. 스파이크용 임시방편이고, 준비 신호를 제대로 받아야 합니다
- 프론트엔드와 말할 통로(rdgen 프로토콜)가 없습니다. 지금은 결과를 `/tmp` 파일에 씁니다

## 빌드

```bash
./gradlew :codemap:buildPlugin     # buildBackend 를 거쳐 dotnet/ 에 DLL 을 넣습니다
```

Rider가 번들한 SDK로 컴파일합니다(`<Rider>/Contents/lib/ReSharperHost/macos-arm64/dotnet`). 별도 .NET 설치는 필요 없고, `riderLocalPath` 가 없으면 이 단계는 조용히 건너뜁니다.

C++ API(`JetBrains.ReSharper.Cpp`)는 **공개 ReSharper SDK에 없습니다.** 그래서 설치된 Rider의 어셈블리를 직접 참조하고, 그 대가로 빌드가 로컬 Rider 설치에 묶입니다. Rider 업데이트로 깨질 수 있는 API라는 뜻이기도 합니다.

## 함정 (전부 실제로 밟은 것들)

- **존을 잘못 고르면 아무 말 없이 죽습니다.** `IReSharperHostCppFeatureZone` 을 요구하면 어셈블리는 카탈로그에 오르는데 컴포넌트가 하나도 안 만들어지고, **어떤 로그에도 이유가 안 남습니다.** 실패와 "아직 안 뜸"이 구분되지 않습니다. 활성인 것은 `ILanguageCppZone` 입니다. 바꾸려면 프로브로 다시 재세요
- `[ZoneMarker]` 를 짧게 쓰면 같은 이름의 클래스 자신으로 해석됩니다 → `[ZoneMarkerAttribute]`
- `[SolutionComponent]` 무인자 생성자는 obsolete입니다 → `Instantiation` 을 넘겨야 하고, 프로브처럼 아무도 요청하지 않는 컴포넌트는 `Container*` 여야 합니다(`Demand*` 는 영원히 안 뜹니다)
- `ModuleInitializerAttribute` 가 프레임워크와 `JetBrains.Platform.Core` 양쪽에 있어 충돌합니다
- 백엔드에서 `Path.GetTempPath()` 는 macOS에서 `/tmp` 가 아닙니다. 프로브 파일은 절대 경로로 쓰세요
- 샌드박스로 검증할 때 `.slnx` 는 열리다 멈춥니다. 구형 `.sln` 을 쓰세요. `.vcxproj` 에서 `$(VCTargetsPath)` 임포트도 macOS에선 로드를 실패시킵니다

## 파일

| 파일 | 하는 일 |
|---|---|
| `ZoneMarker.cs` | 이 어셈블리가 요구하는 존 |
| `CodemapBackendProbe.cs` | 로드됐음을 알리는 프로브(셸/솔루션 두 개) — 임시 |
| `CppSpike.cs` | C++ 선언을 실제로 뽑아보는 스파이크 — 임시 |
