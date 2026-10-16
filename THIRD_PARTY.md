# Third-party sources

`vendor/quickjs` pins [FongMi/quickjs](https://github.com/FongMi/quickjs/tree/fongmi),
branch `fongmi`, with upstream updates after v0.17.0
(`6d46d07d04041b40f4f49eaa7fdebe44c314c699`).

- Upstream: <https://github.com/quickjs-ng/quickjs>
- Base release: <https://github.com/quickjs-ng/quickjs/releases/tag/v0.17.0>
- License: MIT; retain `vendor/quickjs/LICENSE` and source headers.

Read the exact engine pin with:

```powershell
git ls-tree HEAD vendor/quickjs
```

The native runtime compiles only `dtoa.c`, `libregexp.c`, `libunicode.c` and
`quickjs.c`, with their upstream headers. QuickJS libc, CLI programs, `std` and
`os` are excluded from the runtime.

## Engine changes on the fongmi branch

- Release external ArrayBuffer storage when detached transfer allocation fails.
- Expose validated buffer ranges and managed allocator exchange for JNI buffer
  leases; report the current length of length-tracking typed-array views.
- Discard queued jobs through an explicit terminal-lifecycle API.
- Add an opt-in context policy for eval/Function string compilation.
- Correct optional-chain and nested-new constructor parsing.
- Resolve imported/re-exported module bindings by their final identity, and
  complete asynchronous module state and rejection propagation in order.
- Correct typed-array `with` conversion after resize and BigInt locale formatting.

The pinned engine uses a context-based `JSInterruptHandler`, uncatchable
OOM/InternalError exceptions and bytecode version 29. See [Java API](docs/API.md)
for wrapper bytecode and ownership contracts.

## Wrapper and build tools

The Java API, UTF-8 codecs and JNI integration are FongMi code. Gradle wrapper
files come from TV under Apache License 2.0; retain `gradle/LICENSE`.
