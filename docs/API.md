# Java API

The API package is `com.fongmi.quickjs`. Each runtime owns one execution thread;
JavaScript, callbacks and Promise jobs execute there.

## Setup

```java
import com.fongmi.quickjs.QuickJS;

try (QuickJS runtime = new QuickJS()) {
    Object answer = runtime.evaluate("Promise.resolve(6 * 7)").join();
}
```

`QuickJS()` loads `quickjs-wrapper`. Supply a `Runnable` loader when native code
comes from a separate runtime package or absolute path.

| Capability | Installation |
| --- | --- |
| Runtime | `new QuickJS()` |
| Console | `Console.install(runtime).join()` |
| Timers | `new Timers(runtime, errors)` |
| UTF-8 codecs | `Encoding.install(runtime).join()` |
| Web APIs, timers and extended codecs | `new WebPlatform(runtime, errors, baseURL)` |
| Modules | `runtime.setModuleLoader(loader).join()` |

Install helpers before application code. Close the platform or timers before
the runtime. See [Web APIs](WEB.md) for supported capabilities.

## Calls and bindings

`evaluate`, `evaluateModule` and `invoke` return futures that await JavaScript
Promises. `reference` and `callFunction` retain object and Promise results;
they do not await those Promises. Release every acquired reference once.

`submit` runs on the owner and runs inline when already there. `execute` queues
an owner turn, which can establish a boundary after the native job drain.
Do not block the owner waiting for a callback that needs the same thread.

`bind` is synchronous. `bindAsync` accepts a cancelable future and exposes a
JavaScript Promise:

```java
runtime.bindAsync("read", args -> readAsync((String) args[0])).join();
```

Keep returned values stable until conversion finishes. Java failures reject
the Promise; uncatchable engine errors fail the owning Java call directly.
Canceling an unfinished call cancels its async sources. Successful calls leave
background work detached; runtime close cancels remaining sources. Give shared
producers separate dependent futures for each consumer.

Scoped `bindAsync` and `setAsyncScopeCapture` preserve host context through
Promise settlement and jobs. Capture must not execute JS; enter/restore hooks
must not throw. Hook failure closes the runtime. Install capture before host
calls so finalization jobs have a detached context. `Callback.callDetached`
delivers without its birth scope. WeakRef targets stay alive through the current
job and its microtask checkpoint; collection in a later turn is not guaranteed.

## Cancellation and limits

Canceling a call future interrupts its native JS execution. `evaluateTimed`
and `invokeTimed` cover queue wait, execution and Promise settlement. Durations
are milliseconds; zero disables the deadline and negative values fail.
Native interruption cannot preempt blocking Java code, so host I/O needs a timeout.

`cancelPending()` cancels unfinished JS calls and leaves the runtime open.
Later calls, generic owner work and detached jobs have separate lifetimes.

`close()` rejects new work, cancels pending operations and waits for owner
cleanup. `onClose` registers cleanup that runs once. Buffer leases remain valid
after logical close and defer final native destruction until released.

`setLimits(memory, stack)` bounds engine storage and stack use; direct buffers
use Java storage. A positive constructor operation limit bounds unfinished
external calls; zero is unlimited. Excess work fails with
`RejectedExecutionException`. Cleanup and async settlement remain available.

## Values and buffers

| Java value | JavaScript value |
| --- | --- |
| `QuickJS.Json(text)` | Parsed JSON |
| `byte[]` | Copied ArrayBuffer |
| `QuickJS.Binary(json, property, bytes)` | Metadata with an ArrayBuffer property |
| `QuickJS.Text(json, property, text)` | Metadata with a string property |
| `QuickJS.ByteArray(json, property, bytes)` | Metadata with a signed-number array |
| `QuickJS.DirectBuffer(buffer)` | Shared writable direct storage |

Metadata carriers require a JSON object and define an own data property.
`Text` preserves UTF-16 code units. ArrayBuffer and TypedArray results copy their
selected range to `byte[]`; convert DataView to Uint8Array before returning it.
Nested binary fields still follow JSON conversion. Keep queued inputs stable.

`DirectBuffer` captures the buffer's remaining slice. Java and JS need exclusive
turns. Queue an `execute` barrier before Java reuses shared storage, and stop
detached JS work first. Keep the backing allocation alive while JS retains it.
Ordinary ByteBuffer values do not opt into shared storage.

`leaseBuffer(reference)` pins an ArrayBuffer or view range. `BufferLease.use`
provides a read-only direct view during a short owner callback. Do not retain
that view or wait for owner work inside the callback. Close the lease after use;
incompatible resize is rejected while pinned. Views are not shared across runtimes.

## Modules and bytecode

`QuickJS.ModuleLoader` supplies `normalize(base, name)`, `load(name)` and optional
`url(name)` for import.meta.url. Hooks run on the owner and must not reenter JS.
JSON imports use the `type: 'json'` attribute; the loader supplies raw JSON text.

`ModuleBytecodeCache(directory, identity, maxBytes, maxFiles, diagnostic)` stores
source-aware bytecode with integrity and engine checks. Supply a private directory,
host namespace and ABI. Entries are limited to 4 MiB. Enable `cacheBytecode()`
and delegate the loader's load/store hooks to the cache. I/O failures go to
`diagnostic`; invalid entries become misses. Store and execute only trusted,
matching-engine bytecode.
Cache-hook or decode/link failures close the runtime because partial module
registration cannot safely roll back. Regenerate incompatible caches from source.

`setCodeGenerationAllowed(false)` rejects string eval and Function constructors.
Host evaluation and imports remain available. This setting is not a sandbox.

## Timers and console

`Timers` installs setTimeout/setInterval and their clear methods. Timers retain
callback references and run independently of the call that created them.
They support callback arguments, string handlers and JS delay conversion.
Intervals rearm after the synchronous callback; they do not await its Promise.
Nested timers use the standard minimum delay.

`Timers.delay(ms)` returns a cancelable future completed on the owner.
`WebPlatform.delay(ms)` uses its installed scheduler. Cancellation removes the
task; helper or runtime close cancels pending delays.

Console supports log/debug/info/warn/error, assert, dir/dirxml, count/countReset,
time/timeLog/timeEnd, trace, groups, table and clear. Its default sink uses
Android Logcat or desktop stdout/stderr. Pass a `BiConsumer<String, String>`
to replace it.

`setUnhandledRejectionListener` reports background failures after the job drain.
Awaited failures go to their call future. Register a handler in the same turn
to suppress a background report; leave the listener unset to disable reporting.

## Function JAR API

`QuickJSContext`, `JSObject`, `JSArray`, `JSFunction` and `JSMethod` share the
runtime and native library. Compile Function JARs against the matching API JAR.

Load native code before `QuickJSContext.create()`. A created context owns its
runtime; a context wrapping an existing runtime releases only its own values
and callbacks. Synchronous evaluation retains Promise objects without awaiting them.

Objects retain references and receivers; balance `hold` and `release`.
Returning a JSObject from JSCallFunction transfers one acquisition to JS.
Byte arrays become ArrayBuffers; typed arrays remain JSObjects.
`toMap`/`toArray` preserve cycles and shared children in one owner turn.
Custom creators must return a fresh wrapper for each acquisition.
`getPointer()` is an opaque identity, not a native pointer.
