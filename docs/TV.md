# TV crawler integration

| Responsibility | Owner |
| --- | --- |
| JavaScript execution, Promise jobs, value conversion, references and native lifetime | wrapper |
| Console, timers, async delay scheduling and encoding | wrapper |
| Bytecode cache storage, integrity checks and engine format | wrapper |
| HTTP, cookies, sessions, sockets and proxy streams | TV |
| Invocation attribution, site keys, host services and cache policy | TV |
| CatVod entry points, DEX loading, module resolution, cache directory and limits | TV |

HTML queries, `net` helpers and proxy results are TV APIs.

## Artifact handoff

Use one successful wrapper run and verify `SHA256SUMS`:

| Producer file | Consumer path |
| --- | --- |
| `wrapper-java-0.1.0-SNAPSHOT.jar` | `TV/quickjs/libs/wrapper-java.jar` |
| `runtime-release.aar` | `TV/quickjs/libs/runtime-release.aar` |
| The same Java API JAR | `CatVodSpider/app/libs/quickjs-api.jar` |

The JAR contains Java classes, scripts and R8 rules. The AAR supplies native
libraries for both ABIs without Java API classes. TV packages its Java plugin,
JS assets and native library in a separate runtime APK.

Manual runs build `spider_ref` against its committed `catvod-api.jar` and the
same run's `quickjs-api.jar`, producing `quickjs-spider-sdk`. The job removes
embedded wrapper classes from the CatVod SDK; it does not regenerate that SDK.
After shared TV API changes, export `apiJar` and update CatVodSpider first.
Keep both SDKs separate and exclude their implementations from the crawler JAR.

## Runtime and invocation ownership

TV loads the runtime through the `QuickJS` library-loader callback and checks
profile `fongmi-quickjs-ng-0.17-v12`. Keep Java, JS assets and JNI matched.
The QuickJS module consumes the JAR; the runtime build consumes the AAR.

Global's scoped `bindAsync` bindings enter the captured Invocation around
Promise settlement and the complete native job drain, then restore the previous
context. Successive awaits keep ownership. Shared cache producers use their
own Invocation: canceling the first consumer must not cancel another consumer's
producer. Background cache completions, socket events and timers have detached
lifetimes. Site destruction still closes site-owned producers and streams.

TV delegates module bytecode storage to `ModuleBytecodeCache` and supplies its
directory, namespace, ABI and size limits. The wrapper validates engine headers;
cache format changes do not change the host compatibility ID.

TV installs SpiderDebug for background rejections. See [Java API](API.md)
for ownership and cancellation.

## HTTP and stream boundaries

`net.http` text bodies use `QuickJS.Text`; buffer=1 uses `QuickJS.ByteArray`
for a signed-number Array. Both copy their values.
`net.map(items, callback, limit=4)` fills bounded concurrency slots as work
completes and preserves input order. HTTP errors remain values; callback
rejection stops queued admission while active siblings retain their lifetimes.

Stream opening completes at headers and returns metadata with a stream handle.
An invocation owns unclaimed streams; returning or consuming a proxy result
transfers ownership to the server. Discard, cancellation, HTTP disconnect and
site destruction close their owned upstream resources. A successful shared
cache loader must also release any unclaimed stream before runtime close.

`net.openStream(url, options)` exposes metadata, `await read(size)` and `close()`.
One read may be outstanding at a time; the default chunk is 64 KiB and the
maximum is 1 MiB. Its JNI boundary uses private direct storage, while transport
layers may still copy. Cancellation cancels the Call promptly and lets the read
worker release the response body. The `net.stream` proxy passthrough contract
remains separate.

## Spider destruction

Spider destruction is terminal and idempotent: it rejects new calls, cancels
active work and gives the JS destroy hook five seconds, including owner-queue
wait, execution and Promise settlement. This also applies to Host.NONE;
ordinary calls use the host timeout. Invoke destruction outside the owner.

Expiry interrupts JavaScript and cancels owned async sources. Errors and
TimeoutException remain visible. Every path closes the runtime and awaits
owner cleanup; concurrent callers share cleanup and preserve interruption.
Outstanding buffer leases defer final native destruction.

Return means cleanup finished, not hook success. The budget cannot preempt
blocking Java code or transport close; host I/O needs its own finite timeout.

## HTML field queries

`htmlQuery(html, plan)` synchronously parses HTML into ordinary objects, one
row array per named query. Define a plan and collect each page in one call:

```js
const VIDEO_QUERY = {
    queries: {
        cards: {
            selector: ".video-card",
            fields: {
                title: {selector: ".title"},
                href: {selector: "a.title", attribute: "href"},
                image: {selector: "img", attribute: "data-src"},
                actors: {selector: ".actors a", all: true}
            }
        },
        pages: {
            selector: ".pagination a",
            fields: {href: {attribute: "href"}, text: {}}
        }
    }
};

const response = await net.http(url);
if (response.error) throw new Error(response.error);
if (response.code < 200 || response.code >= 300) throw new Error(`HTTP ${response.code}`);
const {cards, pages} = htmlQuery(response.content, VIDEO_QUERY);
const list = cards.map(row => ({
    vod_name: (row.title ?? "").trim(),
    vod_id: row.href ? joinUrl(url, row.href) : "",
    vod_pic: row.image ? joinUrl(url, row.image) : "",
    vod_actor: row.actors.map(value => (value ?? "").trim()).filter(Boolean).join(" / ")
}));
```

Selectors follow Jsoup semantics and are parsed on each call. Adjust this
example to the site, then trim, deduplicate and resolve URLs in JavaScript.

| Option | Contract |
| --- | --- |
| queries | 1..16 named queries; each result is an array |
| query.selector | Required Jsoup CSS selector for rows |
| query.fields | 1..32 named field definitions |
| query.maxRows | Optional integer 1..1024, default 1024; overflow throws, never truncates |
| field.selector | Optional descendant selector; excludes the row itself. Omit to read the row |
| field.attribute | Optional attribute name; omit for descendant text |
| field.all | Optional boolean, default false; true returns all selected values |

A query with no matched rows returns `[]`. A missing selected node/attribute produces `null`;
an existing empty value produces `""`. An all-field with no matches produces
`[]`, and missing attributes remain null entries. Text preserves whitespace
and concatenates descendant text/data without inserting breaks. URLs remain
as written; there is no automatic base URL.

Input is bounded to 2,097,152 UTF-16 code units; the JSON plan to 16,384; total
escaped JSON output across query groups to 1,048,576. Unknown options and
invalid selectors/types throw.
