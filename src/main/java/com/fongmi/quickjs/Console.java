package com.fongmi.quickjs;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/** Installs the shared JavaScript console and value formatter. */
public final class Console {

    private static final String BINDING = "__quickjsConsoleStdout";
    private static final String FORMAT = """
            if (typeof globalThis.format !== 'function') globalThis.format = function(value) {
                if (typeof value === 'string') return "'" + value + "'";
                if (typeof value === 'bigint') return String(value) + 'n';
                if (Object.is(value, -0)) return '-0';
                if (value === null || typeof value !== 'object') return String(value);
                if (value instanceof Error) return String(value);
                const seen = new WeakSet();
                return JSON.stringify(value, function(key, item) {
                    if (typeof item === 'bigint' || typeof item === 'function') return String(item);
                    if (item && typeof item === 'object') {
                        if (seen.has(item)) return '[Circular]';
                        seen.add(item);
                    }
                    return item;
                });
            };
            """;
    private static final String CONSOLE = RuntimeScript.read("console.js");
    private static final String INSTALL = "'use strict'; try {\n" + FORMAT + CONSOLE + """
                console.stdout = (function(output) {
                    return function(...values) {
                        if (values.length !== 2) throw new Error('console.stdout requires a level and a message');
                        output(...values.map(value => value == null ? 'null' : String(value)));
                    };
                })(globalThis.$binding);
            } finally { delete globalThis.$binding; } void 0;
            """.replace("$binding", BINDING);

    private Console() {}

    public static CompletableFuture<Void> install(QuickJS runtime) {
        return install(runtime, Console::write);
    }

    private static void write(String level, String message) {
        int priority = switch (level) {
            case "info" -> 1;
            case "warn" -> 2;
            case "error" -> 3;
            default -> 0;
        };
        QuickJS.writeConsole(priority, message.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Installs a console whose sink receives a level and formatted message on the runtime
     * owner thread. A user-defined global format function is preserved. JavaScript may
     * replace console.stdout or console.print to intercept subsequent output.
     *
     * <p>Reinstallation releases the previous modern sink registration, so saved stdout
     * functions from an earlier installation can no longer call that sink. The internal
     * binding name __quickjsConsoleStdout is reserved during installation. Legacy contexts
     * keep their independently owned callbacks until those contexts close.
     */
    public static CompletableFuture<Void> install(QuickJS runtime, BiConsumer<String, String> sink) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(sink, "sink");
        return runtime.submit(() -> {
            runtime.releaseBinding(BINDING).join();
            runtime.bind(BINDING, values -> {
                sink.accept((String) values[0], (String) values[1]);
                return null;
            }).join();
            try {
                runtime.evaluate(INSTALL, "<console>").join();
            } catch (Throwable error) {
                try {
                    runtime.releaseBinding(BINDING).join();
                } catch (Throwable cleanup) {
                    error.addSuppressed(cleanup);
                }
                throw error;
            }
            return null;
        });
    }

    /** Initializes the formatter and a missing console for a legacy context, without a sink. */
    public static CompletableFuture<Void> initialize(QuickJS runtime) {
        return runtime.evaluate(FORMAT + "if (typeof globalThis.console === 'undefined') {" + CONSOLE + "}; void 0;",
                "<console>").thenApply(ignored -> null);
    }

    /** Resets the console methods before a legacy context installs its own stdout callback. */
    public static CompletableFuture<Void> reset(QuickJS runtime) {
        return runtime.evaluate(CONSOLE + "void 0;", "<console>").thenApply(ignored -> null);
    }
}
