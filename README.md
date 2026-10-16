# FongMi QuickJS wrapper

A Java/JNI runtime for TV crawlers, with shared host APIs and Android packaging.
The engine is [FongMi/quickjs](https://github.com/FongMi/quickjs/tree/fongmi),
branch `fongmi`, with upstream updates after v0.17.0.

- [Java API](docs/API.md): setup, calls, ownership and modules.
- [TV integration](docs/TV.md): responsibilities and artifact handoff.
- [Third-party sources](THIRD_PARTY.md): engine changes and licenses.

## Source checkout

The `vendor/quickjs` submodule pins an exact engine commit. To inspect it locally:

```powershell
git submodule update --init vendor/quickjs
```

Repeat after changing the engine pin. Artifact consumers can leave it
uninitialized. Publish and verify engine changes on `fongmi` before updating
the wrapper's gitlink. Local tests and build outputs remain ignored.

## Build

Native compilation runs in the [Build workflow](.github/workflows/build.yml).
Pushes build Android and Windows artifacts. Manual runs also build the
CatVodSpider revision selected by `spider_ref`; `compare_lto` adds ThinLTO
comparison variants and defaults to false.

| Actions artifact | Contents |
| --- | --- |
| `quickjs-android` | Java API JAR and native AAR for arm64-v8a / armeabi-v7a |
| `quickjs-host-windows` | Windows x64 JNI DLL for host verification |
| `quickjs-spider-sdk` | CatVod SDK, QuickJS API JAR, Function JAR and MD5; manual runs |
| `quickjs-android-lto` | Android ThinLTO variant; comparison runs |
| `quickjs-host-windows-clang` / `quickjs-host-windows-lto` | Clang without/with ThinLTO; comparison runs |

Each artifact includes `SHA256SUMS` and expires after three days. Use matched
JAR/native artifacts from one successful run. Compare ThinLTO on the target
workload before enabling it for distribution.

Toolchain: JDK 21, SDK/build-tools 37, CMake 3.22.1 and NDK
29.0.14206865. Java targets 17; Android requires API 24 with core-library
desugaring. The AAR uses static C++ and 16 KB ELF alignment; APK consumers
must preserve compatible ZIP alignment.

CI checks Android API use, JNI exports, both ABIs and ELF alignment. Local regressions, engine Test262/sanitizers and device tests
run separately.

## Java API

```java
import com.fongmi.quickjs.QuickJS;

try (QuickJS runtime = new QuickJS()) {
    Object answer = runtime.evaluate("Promise.resolve(6 * 7)").join();
}
```

Each runtime has one owner executor. Evaluation returns a future that awaits
JavaScript Promises. Avoid blocking the owner on its own callbacks, and close
owned references and resources. See [runtime setup](docs/API.md#runtime-setup)
for optional capabilities and [TV integration](docs/TV.md#artifact-handoff)
for consumer paths and runtime packaging.
