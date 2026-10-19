# TV integration

| Responsibility | Owner |
| --- | --- |
| JS execution, Promise jobs, values, references and JNI lifetime | wrapper |
| Console, timers, delay scheduling, encoding and Web APIs | wrapper |
| Bytecode cache storage, integrity and engine format | wrapper |
| HTTP, cookies, sessions, sockets and proxy streams | TV |
| Sites, invocations, module resolution and cache policy | TV |

## Artifacts

Use one successful Actions run and verify `SHA256SUMS`.

| Producer | Consumer |
| --- | --- |
| Java API JAR | `TV/quickjs/libs/wrapper-java.jar` |
| Native AAR | `TV/quickjs/libs/runtime-release.aar` |
| Same Java API JAR | `CatVodSpider/app/libs/quickjs-api.jar` |

The JAR contains Java classes, scripts and R8 rules. The AAR contains native
libraries for both ABIs. TV packages these with its plugin and JS assets in
the QuickJS runtime APK. The App supplies shared CatVod, Host and network APIs.

Manual CI builds `spider_ref` against its CatVod SDK and the same run's
QuickJS API JAR. Crawler JARs must exclude both SDK implementations.
After shared TV API changes, export `apiJar` and update CatVodSpider first.

## Ownership

TV loads native code through the QuickJS loader callback and checks its
configured compatibility ID. Keep the JAR, native library and JS assets matched.
Engine and cache revisions are separate from host compatibility.

TV supplies invocation scopes through `setAsyncScopeCapture` and `bindAsync`.
Awaited work keeps its caller's scope. Shared cache producers have independent
lifetimes, so canceling one consumer does not cancel the others.

TV supplies the bytecode cache directory, identity, ABI and limits.
`net.sleep` uses `WebPlatform.delay`; TV cancels waits with their invocation,
session or site. Ordinary JS timers have a separate lifetime.

TV installs Console for Logcat and SpiderDebug for background failures.
See [Java API](API.md) and [Web APIs](WEB.md) for wrapper contracts.

## Streams and shutdown

An invocation owns unclaimed streams. Returning a proxy stream transfers
ownership to the server. Cancellation, disconnect and site destruction close
the resources they own. Close chunk readers in finally; shared cache loaders
must release streams they do not return.

Spider destruction rejects new calls, cancels active work, bounds the JS
destroy hook and waits for runtime cleanup. Hook failure remains visible.
Native interruption cannot preempt blocking Java code; host I/O needs its own
timeout. Outstanding buffer leases defer final native destruction.

`net`, `htmlQuery` and proxy results are TV crawler APIs. Their usage belongs
in the [CatVodSpider guide](https://github.com/FongMi/CatVodSpider/blob/main/docs/development.md).
