# FongMi QuickJS wrapper

A Java/JNI runtime with shared host APIs and Android packaging.
The engine is [FongMi/quickjs](https://github.com/FongMi/quickjs/tree/fongmi),
pinned by the `vendor/quickjs` submodule.

- [Java API](docs/API.md): setup, calls and ownership.
- [Web APIs](docs/WEB.md): supported capabilities.
- [TV integration](docs/TV.md): responsibilities and artifact handoff.
- [Third-party sources](THIRD_PARTY.md): sources and licenses.

## Source

Initialize the engine when inspecting or modifying native code:

```powershell
git submodule update --init vendor/quickjs
```

Publish engine changes before updating the submodule pin. Local tests and
build outputs remain ignored.

## Build

Start builds manually in [GitHub Actions](.github/workflows/build.yml).
Each run builds Android and Windows artifacts and the CatVodSpider revision
selected by `spider_ref`.

| Artifact | Contents |
| --- | --- |
| `quickjs-android` | Java JAR and native AAR for arm64-v8a / armeabi-v7a |
| `quickjs-host-windows` | Windows JNI DLL for local verification |
| `quickjs-spider-sdk` | CatVod SDK, QuickJS API JAR, Function JAR and MD5 |

Use JAR and native artifacts from one successful run and verify `SHA256SUMS`.
The optional `compare_lto` input adds ThinLTO comparison builds.
Toolchain versions are defined in the workflow; Java and Android requirements
are defined in [build.gradle](build.gradle) and [runtime/build.gradle](runtime/build.gradle).
Android libraries use static C++ and 16 KB ELF alignment.

## Use

```java
import com.fongmi.quickjs.QuickJS;

try (QuickJS runtime = new QuickJS()) {
    Object answer = runtime.evaluate("Promise.resolve(6 * 7)").join();
}
```

Each runtime executes on its own owner thread. Calls return futures that await
JavaScript Promises. Close owned references and resources before the runtime.
See [Java API](docs/API.md) for helpers and cancellation.
