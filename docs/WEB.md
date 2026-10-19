# Web APIs

`WebPlatform` installs Web APIs, timers and encoding on one runtime.
Install console separately and close the platform before the runtime.

```java
import com.fongmi.quickjs.Console;
import com.fongmi.quickjs.QuickJS;
import com.fongmi.quickjs.WebPlatform;

try (QuickJS runtime = new QuickJS();
     WebPlatform web = new WebPlatform(runtime, Throwable::printStackTrace,
             "https://example.com/spiders/main.js")) {
    Console.install(runtime).join();
    runtime.evaluate("new URL('../api', 'https://example.com/spiders/main.js').href").join();
}
```

Install one platform per runtime. Its base URL resolves relative Request
inputs; URL uses its explicit base. Closing removes its unchanged globals
and cancels timers. UTF-8 codecs remain installed.

| Capability | Supported APIs |
| --- | --- |
| Timers | setTimeout/setInterval, clear methods, arguments and string handlers |
| URL | URL and URLSearchParams |
| Events and abort | Event, EventTarget, CustomEvent, AbortController and AbortSignal |
| Clone | structuredClone, cycles, containers and ArrayBuffer transfer |
| Streams | ReadableStream, WritableStream, TransformStream, BYOB, tee and async iteration |
| Binary and forms | Blob, File, FormData, Headers, Request, Response and body methods |
| Encoding | TextEncoder, TextDecoder, GBK/GB18030/Big5 and encoding streams |
| Crypto | getRandomValues, randomUUID, SHA digests, HMAC, AES-GCM and AES-CBC |

The engine also supplies DOMException, queueMicrotask, performance.now and atob/btoa.
HTTP transport and module loading are supplied by the host.

## Bodies and abort

Bodies support text, JSON, bytes, Blob, FormData and streams. Body methods are
single use; clone tees the stream. AbortSignal supports abort/timeout/any,
reason and throwIfAborted. Dispatching an event alone does not abort an operation.

## Crypto

Supported keys use raw or octet JWK import/export, key generation and
usage/extractability checks. HMAC supports sign/verify; AES supports
encrypt/decrypt and wrapKey/unwrapKey. SHA digests support SHA-1/256/384/512.

AES-GCM appends its tag to ciphertext and supports 96, 104, 112, 120 and 128-bit
tags. AES-CBC uses PKCS#7 padding. HMAC keys contain complete bytes.
Inputs are captured before asynchronous work begins. Operations use the host's
Java crypto provider; verify the provider on the target Android device.

## Bundle

See [Third-party sources](../THIRD_PARTY.md) for dependencies and licenses.
Actions rebuilds the bundle and notices, then checks them against committed
resources. Keep body, clone and BYOB regression checks when updating it.
