# Java runtime API

The public API is `com.fongmi.quickjs`. Each `QuickJS` instance owns one
executor and native runtime. Separate instances execute independently.
JavaScript execution, callbacks and Promise jobs return to that owner.

```java
import com.fongmi.quickjs.QuickJS;

try (QuickJS runtime = new QuickJS()) {
    Object answer = runtime.evaluate("Promise.resolve(6 * 7)").join();
}
```

## Runtime setup

`QuickJS()` calls `System.loadLibrary("quickjs-wrapper")`. Supply a `Runnable`
loader for a runtime package or absolute DLL path. Load native code before
calling `QuickJSContext.create()`.

| Capability | Installation |
| --- | --- |
| ECMAScript, Promise jobs and engine intrinsics | `new QuickJS()` |
| Shared console and output sink | `Console.install(runtime).join()` |
| Timers only | `new Timers(runtime, errors)` |
| UTF-8 codecs only | `Encoding.install(runtime).join()` |
| Module resolution and source loading | `runtime.setModuleLoader(loader).join()` |

Install helpers before application code and close timers before the runtime.

## Calls, bindings and ownership

`evaluate`, `evaluateModule` and `invoke` unwrap returned JavaScript Promises
into their Java futures. `reference` operations retain object and Promise
results. Do not block the owner waiting for a future that requires an
asynchronous callback on that owner.
`submit` batches work on the owner; calls already there run inline. `execute`
queues work and can establish a boundary after the current native job drain.
References and callbacks belong to their originating runtime and must be closed.

`callFunction(reference, receiver, args...)` directly invokes a retained function.
Null selects an undefined receiver. Object and Promise results remain retained
references, as with `reference` operations; this method does not await a Promise.

`bind` installs a synchronous Java host function. `bindAsync` accepts a function
returning `CompletableFuture<?>` and exposes an intrinsic JavaScript Promise:

```java
runtime.bindAsync("read", args -> readAsync((String) args[0])).join();
// JavaScript: const content = await read(url);
```

The host supplies `readAsync` and its cancelable future. `bind` stays synchronous.

Keep results stable until queued conversion completes. Host throws, null
futures, source failures and ordinary conversion failures reject the JavaScript
Promise. Uncatchable engine failures fail the owning Java calls without running
JavaScript catch handlers, including after asynchronous continuation.
Canceling an owning pending call cancels its source futures and drops settlement;
it does not add cancellation to JavaScript Promise. Successful callers detach
background work, while runtime close cancels remaining sources. Shared producers
must supply independent dependent futures for their consumers.

The scoped `bindAsync` overload captures an `AsyncScope` before dispatch and
enters it around actual settlement and the complete native job drain. Its
restore action must not throw. `Callback.callDetached` omits its birth scope
while keeping delivery cancelable. TV uses these boundaries for invocation
attribution and shared cache isolation.

## Cancellation, deadlines and close

Canceling a call future interrupts its associated native JavaScript execution.
`evaluateTimed` and `invokeTimed` additionally bound owner-queue wait, synchronous
execution and Promise settlement with one monotonic deadline. Host work should
return cancelable futures and provide its own finite I/O timeout: native
interruption cannot preempt an arbitrary blocking Java function.
Timeouts are milliseconds; zero disables the deadline and negative values fail
with `IllegalArgumentException`.

`cancelPending()` cancels a snapshot of unfinished JavaScript calls while
leaving the runtime open. It requests native interruption before cancellation
continuations. Generic `submit`/`execute` work, reference release and later calls
remain outside that snapshot. Detached jobs whose call already completed are
outside the call's cancellation lifetime.

`close()` rejects further work, cancels unfinished operations and releases
native state on its owner. `onClose` registers cleanup that runs once, including
when an internal failure initiates close. Outstanding buffer leases defer final
native destruction and remain usable after logical close.

## Values and binary storage

Primitives use direct JNI conversion. Structured values use JSON or explicit
references. Carrier records install one own writable/enumerable/configurable
property into a parsed JSON metadata object; nulls, arrays and nonobject
metadata are rejected.
Java object arrays and generated key arrays also define their own indexed
properties, without invoking setters inherited from Array.prototype.

| Java value | JavaScript value |
| --- | --- |
| `QuickJS.Json(text)` | Parsed JSON value |
| `byte[]` | Copied `ArrayBuffer` |
| `QuickJS.Binary(json, property, bytes)` | Metadata with an `ArrayBuffer` property |
| `QuickJS.Text(json, property, text)` | Metadata with a Java string property |
| `QuickJS.ByteArray(json, property, bytes)` | Metadata with a signed-number Array property |
| `QuickJS.DirectBuffer(buffer)` | Explicit shared writable direct storage |

`Text` preserves UTF-16 code units, including NUL and lone surrogates. It copies
the string without JSON-encoding its body. `ByteArray` preserves Java's signed
byte convention. JavaScript `Uint8Array` reads the same bits as unsigned bytes.
Modern ArrayBuffer/TypedArray results copy their selected byte range into
`byte[]`; normalize a DataView to Uint8Array before returning it by value.
Plain objects containing binary fields still follow JSON conversion.

Do not mutate or reuse Java input while its queued conversion is pending.
Ordinary ByteBuffer values do not implicitly opt into shared storage.
`DirectBuffer` captures a fixed slice of the buffer's remaining range. Java and
JavaScript require exclusive turns; a future can complete before its native
Promise-job drain, so queue an `execute` barrier before reusing shared storage.
Detached background work must also have stopped using it. Callers must not
unmap or free the Java-managed backing allocation while JavaScript retains it.

`reference("evaluateReference", source, name, module)` retains a JavaScript
buffer reference. `leaseBuffer(reference)` pins the exact ArrayBuffer,
TypedArray or DataView range. `BufferLease.use(reader)` supplies a read-only
direct view during a short owner callback; do not retain that view or wait for
owner work inside the callback. Close the lease after use. Detach, transfer and
logical runtime close cannot free pinned bytes; resizing to a different
allocation size is rejected while pinned. This does not permit concurrent
JavaScript writes or references shared across runtimes.

## Modules, language and resource limits

`QuickJS.ModuleLoader` supplies `normalize(base, name)`, `load(name)` and optional
`url(name)` for `import.meta.url`. Hooks run on the owner and must not reenter
JavaScript. Filesystem, CLI, `std` and `os` services belong to the host.

`ModuleBytecodeCache(directory, identity, maxBytes, maxFiles, diagnostic)` stores
source-aware module bytecode with integrity and engine-header checks. Supply a
host namespace and ABI in `identity`, and a private cache directory. Entries are
limited to 4 MiB; the host supplies total-byte and file-count limits. Recoverable
I/O failures are reported through `diagnostic`; invalid entries become misses.
`cacheBytecode()` enables the loader hooks; delegate their load/store methods to
the cache. Cache only trusted bytecode.
Cache hook or decode/link failure closes the runtime: partial module
registration cannot safely roll back.
The wrapper bytecode header is v4; foreign or older caches must be regenerated
from source. The pinned engine bytecode version is 29. Persist and execute
only trusted bytecode.

Use a matched JAR/native library; the host owns selection and profile checks.
`setCodeGenerationAllowed(false)` rejects string eval and Function constructors
with EvalError. Its default is true. Nonstring eval, host evaluation, module
imports and trusted bytecode remain available. This policy is not a sandbox.

The engine includes Uint8Array Base64/Hex codecs. Encoding installs UTF-8
TextEncoder/TextDecoder with fatal/BOM/streaming options; other charset labels,
encoding streams and Intl are unsupported. Selected tests do not establish full
ECMAScript conformance. `setLimits(memory, stack)` controls engine budgets;
direct storage uses Java's budget. Engine OOM/InternalError bypass JavaScript
catch handlers; ordinary Java host exceptions become catchable JavaScript Errors.

`new QuickJS(32)` or `new QuickJS(loader, 32)` limits unfinished external
operations. Zero is the unlimited default; negative limits fail before loading
native code. Capacity includes queued, executing and Promise-waiting calls,
public submissions and lease readers. Excess work fails with
`RejectedExecutionException` before side effects; the producer is not blocked.
Completion releases capacity at the returned future boundary. Owned async
settlements, timer delivery and resource cleanup remain available at capacity.
This is an operation limit, not a bound on all internal work or total memory.

## Timers, console and background failures

```java
import com.fongmi.quickjs.Console;
import com.fongmi.quickjs.QuickJS;
import com.fongmi.quickjs.Timers;

try (QuickJS runtime = new QuickJS();
     Timers timers = new Timers(runtime, Throwable::printStackTrace)) {
    Console.install(runtime, (level, message) -> System.out.println(message)).join();
    runtime.evaluate("new Promise(resolve => setTimeout(() => resolve(42), 0))").join();
}
```

`Timers.delay(milliseconds)` returns a cancelable `CompletableFuture<Void>` using
the same scheduler. Completion runs on the runtime owner; cancellation removes
the scheduled task. Closing the helper or runtime cancels pending delays.

Timers retain callback references and start fresh invocations, detached from
the call that created them. Close the helper before the runtime. Cleanup
cancels timers, frees bindings and preserves globals replaced by user code.
Console supplies log/debug/info/warn/error and replaceable stdout/print, while
preserving a custom global formatter. Its sink runs on the owner.

`setUnhandledRejectionListener` reports background rejections at a completed
job-drain boundary. It is disabled by default. A handler attached in the same
turn suppresses reporting; awaited failures go to their call future without
duplicate notification. Diagnostic conversion does not invoke accessors,
Proxy traps or arbitrary `toString`. A listener may release resources, queue
work or close the runtime; synchronous JavaScript reentry fails explicitly.

## Synchronous Function JAR API

`QuickJSContext`, `JSObject`, `JSArray`, `JSFunction` and `JSMethod` are in
`com.fongmi.quickjs` and share the same runtime and native library as `QuickJS`.
Compile Function JARs against the matching QuickJS API JAR.
`QuickJSContext.create()` expects native loading to be complete and owns its
runtime. A context constructed from an existing runtime releases only its own
references and callbacks on close. Synchronous facade calls dispatch to the
owner; owner reentry runs inline. Synchronous evaluation returns a retained
JavaScript Promise object rather than awaiting it as the modern API does.

Objects/functions retain references and receivers; `hold`/`release` manage
acquisitions. Returning a JSObject from a JSCallFunction transfers one
acquisition to JavaScript. Byte arrays become ArrayBuffers; typed arrays remain
JSObjects. `toMap`/`toArray` preserve cycles and shared children.
`getPointer()` is an opaque identity rather than an external native address.
`toMap` and `toArray` traverse the complete graph in one owner submission,
including supplied map creators and filters. Function creators receive the
context and function identity; receiver retention belongs to the context.
`createNewJSArray(values...)` creates an array in one conversion. Map conversion
uses a Java key snapshot rather than a temporary facade array. Custom creators
must return a fresh wrapper for each acquisition. Diagnostic object records
are unordered snapshots; identity and receiver tracking do not depend on equals.
Console formatting uses compact JSON with cycle markers.
