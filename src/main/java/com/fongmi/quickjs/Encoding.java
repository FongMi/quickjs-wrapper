package com.fongmi.quickjs;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Installs the optional UTF-8 TextEncoder and streaming TextDecoder host capability. */
public final class Encoding {

    private static final String SOURCE = RuntimeScript.read("encoding.js");

    private Encoding() {}

    /**
     * Installs TextEncoder and TextDecoder with WHATWG UTF-8 algorithms and Web IDL
     * argument conversions. TextDecoder accepts the six UTF-8 labels in the Encoding
     * Standard. Other encodings, including valid legacy labels, throw RangeError;
     * this bounded host capability does not implement the full Encoding Standard.
     * TextEncoderStream and TextDecoderStream are not installed.
     *
     * <p>Each installation replaces the two configurable global constructors. Saved
     * instances remain usable. Codec state stays private to JavaScript; no host
     * callbacks or runtime cleanup registrations are retained.
     */
    public static CompletableFuture<Void> install(QuickJS runtime) {
        Objects.requireNonNull(runtime, "runtime");
        return runtime.evaluate(SOURCE, "<encoding>").thenApply(ignored -> null);
    }
}
