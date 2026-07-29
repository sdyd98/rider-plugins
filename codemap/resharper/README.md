# 코드맵 리샤퍼 백엔드

Rider의 .NET 백엔드(ReSharper 호스트) 안에서 도는 코드맵의 나머지 절반입니다. 플러그인이 함수 위치를 물으면 실제 C++ 파스 트리에서 답합니다.

## 왜 필요한가

JVM 쪽(코드맵 플러그인 본체)에는 **C++ 심볼이 없습니다.** CIDR/OC PSI 클래스도, `gotoSymbol`/`chooseByName` 확장 지점도 없고, `cidr.lang.navigatableSymbolSearcherBridge` 는 선언만 있고 구현이 IDE 전체에 하나도 없습니다. 프론트엔드가 백엔드에 닿는 유일한 길은 액션을 흉내내는 것(사용처 찾기)뿐입니다.

그래서 지금까지 함수 위치는 **AI가 노트에 적어둔 시그니처를 본문에서 텍스트로 찾는** 방식이었습니다. 앵커가 안 맞으면 그 함수는 회색으로 죽습니다. 이 어셈블리는 그 자리를 진짜 파서로 채우기 위한 것입니다.

역할 분담은 그대로입니다 — **사실은 리샤퍼가, 해석은 AI가.** 리샤퍼는 `HandleLogin` 이 어디 있는지 알려주지만, "이 락을 쥔 채 들어간다"는 판단은 못 합니다.

## 확인된 것 (2026-07-30, 실측)

| 단계 | 결과 |
|---|---|
| 서드파티 어셈블리를 호스트가 로드 | ✅ |
| 솔루션 컴포넌트로 `ISolution` 확보 | ✅ |
| **C++ 심볼 조회** | ✅ `.cpp` 정의 + `.h` 선언, 실제 오프셋 |
| 프론트엔드↔백엔드 왕복 | ✅ 첫 질문에 169ms |
| 앵커 실패 시 대체 | ✅ 플러그인에 연결됨 |

샌드박스에서 실제로 나온 것 — `.cpp` 의 정의와 `.h` 의 선언이 모두, 정의/선언 구분까지:

```
[Main.cpp]
  → bool Session::HandleLogin(const LoginReq& req, int retries) @ 39 (정의)
  → bool Session::Authenticate(const char* account) @ 178 (정의)
  → void Session::Close() @ 282 (정의)
[Session.h]
  → bool HandleLogin(const LoginReq& req, int retries) @ 123 (선언)
  → void Close() @ 179 (선언)
  → bool Authenticate(const char* account) @ 207 (선언)
```

오프셋은 원문과 대조해 확인했습니다 — 셋 다 선언 첫 글자를 정확히 가리킵니다.

경로는 `IPsiSourceFile → GetPsiFiles(CppLanguage.Instance) → Descendants<SimpleDeclaration>`. C++
함수는 자기 노드 타입이 없습니다 — 모든 선언이 `SimpleDeclaration` 이고, 함수냐 아니냐는 **두 번**
물어야 합니다:

- `CppFunctionDeclaration.TryCreateFromFunctionDeclaration` 은 **본문이 있는 정의만** 인정합니다.
  헤더의 선언은 전부 null 을 돌려줍니다
- 본문 없는 선언은 **자기 선언자에 매개변수 목록(`FunctionParameters`)이 달렸는지**로 봅니다.
  `Descendants` 를 그냥 쓰면 클래스 선언이 멤버들의 매개변수를 품고 있어서 `class Session` 자신이
  함수로 잡힙니다 — 그래서 각 매개변수 목록이 **이 선언에 직접 속하는지**까지 확인합니다

## 프로토콜

rdgen으로 모델 하나(`protocol/src/main/kotlin/model/CodemapModel.kt`)에서 코틀린과 C# 양쪽을 생성합니다. 손으로 쓴 RPC가 만드는 "양끝이 서로 다른 걸 믿는" 버그를 없애려면 같이 생성돼야 합니다.

질문은 **하나뿐**입니다:

```
functionsIn(파일 경로) → [{ signature, offset, line, definition }]
```

플러그인 규칙(정확한 사실만, 해석은 AI가)에 맞춘 모양입니다. 함수가 무엇을 하는지는 여전히 AI가 판단하고, 이건 애초에 판단이 아니었던 부분 — 어디 있는지 — 만 대신합니다.

백엔드는 **PSI 캐시가 안정될 때까지 기다린 뒤** 답합니다. 안 기다리면 인덱싱 중에 온 질문에 빈 목록이 가고, 빈 목록은 "이 파일엔 함수가 없다"와 구분되지 않습니다 — 호출자는 조용히 앵커로 되돌아가고 자기가 너무 일찍 물었다는 걸 영영 모릅니다. 실측으로 **첫 질문에 169ms**, 재시도 없이 완전한 답이 옵니다.

정의/선언은 리졸브가 아니라 **구문 구조**(본문 노드 유무)로 봅니다. 리졸브는 이 시점에 전부 "선언"이라고 답하는데, 그건 답을 못 하는 것보다 나쁩니다 — 정의를 선언이라고 하는 건 사실처럼 보이니까요.

## 아직 안 되는 것

- **진짜 코드베이스에 안 대봤습니다.** 검증은 함수 4개짜리 프로젝트입니다. 매크로·템플릿·다중 상속이 어떻게 나올지는 미지수입니다
- 시그니처는 선언문 첫 줄입니다. 여러 줄 시그니처는 잘립니다

## 빌드

```bash
./codemap/resharper/tools/fetch-rider-model.sh   # 최초 1회 — rdgen 이 필요로 하는 모델 정의
./gradlew :codemap:buildPlugin                   # rdgen → 백엔드 컴파일 → dotnet/ 에 DLL 배치
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
| `CodemapHost.cs` | `functionsIn` 을 답하는 솔루션 컴포넌트 |
| `Model/` | rdgen 생성물 — 커밋하지 않습니다 |
| `../protocol/` | 모델 정의(별도 Gradle 모듈) |
| `tools/fetch-rider-model.sh` | SDK zip에서 `rider-model.jar` 만 꺼내옵니다 |
