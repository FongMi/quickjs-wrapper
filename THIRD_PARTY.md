# Third-party sources

## Engine

`vendor/quickjs` pins [FongMi/quickjs](https://github.com/FongMi/quickjs/tree/fongmi),
based on [QuickJS-NG](https://github.com/quickjs-ng/quickjs).
The submodule records the exact source revision:

```powershell
git ls-tree HEAD vendor/quickjs
```

The runtime builds the engine core without the CLI or libc helpers.
Retain its MIT license and source headers. Engine changes are maintained in
the engine repository; wrapper contracts are documented in [Java API](docs/API.md).

## Wrapper and tools

The Java API and JNI integration are FongMi code. Gradle wrapper files come
from TV under Apache License 2.0; retain [gradle/LICENSE](gradle/LICENSE).

## Web bundle

Versions are locked in [web/package-lock.json](web/package-lock.json).
The JAR includes [dependency notices](src/main/resources/META-INF/licenses/web.txt).

| Package | License |
| --- | --- |
| @exodus/bytes | MIT |
| @web3-storage/multipart-parser | Apache-2.0 AND MIT |
| core-js-pure | MIT |
| event-target-shim | MIT |
| fetch-blob | MIT |
| formdata-polyfill | MIT |
| web-streams-polyfill | MIT |
| whatwg-url | MIT |

esbuild is a build tool. The multipart-parser archive omits its license files;
copies from upstream commit `844cdf7bcb4d86274ae36fce70b1480a78a16774` are kept
in [web/licenses/multipart-parser](web/licenses/multipart-parser).

[web/build.mjs](web/build.mjs) applies the runtime adaptations and generates
the bundle and notices. Keep those checks when updating dependencies.
