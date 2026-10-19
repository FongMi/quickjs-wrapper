package com.fongmi.quickjs;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class WebPlatform implements AutoCloseable {

    private static final AtomicInteger NEXT_HELPER = new AtomicInteger();
    private static final String SOURCE = RuntimeScript.read("web.js");
    private static byte[] bytecode;

    private final QuickJS runtime;
    private final Consumer<Throwable> errors;
    private final Timers timers;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final String name = "__quickjsWeb" + NEXT_HELPER.incrementAndGet();

    public WebPlatform(QuickJS runtime, Consumer<Throwable> errors) {
        this(runtime, errors, null);
    }

    public WebPlatform(QuickJS runtime, Consumer<Throwable> errors, String baseURL) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.errors = Objects.requireNonNull(errors, "errors");
        timers = new Timers(runtime, errors);
        try {
            Encoding.install(runtime).join();
            runtime.submit(() -> {
                install(baseURL);
                return null;
            }).join();
        } catch (RuntimeException | Error error) {
            try {
                close();
            } catch (RuntimeException | Error failure) {
                error.addSuppressed(failure);
            }
            throw error;
        }
    }

    private void install(String baseURL) {
        bindHostFunctions();
        runtime.reference("execute", bytecode(runtime)).join();
        installGlobals(baseURL);
        runtime.onClose(() -> close(false)).join();
    }

    private void bindHostFunctions() {
        runtime.bind(name + "Report", args -> {
            errors.accept(new IllegalStateException((String) args[0]));
            return null;
        }).join();
        runtime.bind(name + "Random", args -> WebCrypto.random(((Number) args[0]).intValue())).join();
        runtime.bind(name + "Uuid", args -> UUID.randomUUID().toString()).join();
        runtime.bindAsync(name + "Crypto", args -> CompletableFuture.supplyAsync(() -> WebCrypto.perform(args))).join();
    }

    private void installGlobals(String baseURL) {
        String script = "try { Object.defineProperty(globalThis, '" + name
                + "', {value: __quickjsInstallWeb({report: globalThis." + name
                + "Report, random: globalThis." + name + "Random, uuid: globalThis." + name
                + "Uuid, crypto: globalThis." + name + "Crypto, baseURL: "
                + (baseURL == null ? "null" : quote(baseURL))
                + "}), configurable: true}); } finally { delete globalThis.__quickjsInstallWeb; "
                + "for (const suffix of ['Report','Random','Uuid','Crypto']) delete globalThis['" + name + "' + suffix]; } void 0;";
        runtime.evaluate(script, "<web>").join();
    }

    private static synchronized byte[] bytecode(QuickJS runtime) {
        if (bytecode == null) bytecode = (byte[]) runtime.reference("compile", SOURCE, "<web>", false).join();
        return bytecode;
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\') result.append('\\').append(character);
            else if (character < 32 || character >= 127) result.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
            else result.append(character);
        }
        return result.append('"').toString();
    }

    public CompletableFuture<Void> delay(long milliseconds) {
        return timers.delay(milliseconds);
    }

    @Override
    public void close() {
        close(true);
    }

    private void close(boolean bindings) {
        if (!closed.compareAndSet(false, true)) return;
        timers.close();
        if (!bindings) return;
        runtime.continueOnOwner(this::releaseBindings, true).whenComplete((value, error) -> {
            if (error != null) report(error);
        });
    }

    private void releaseBindings() {
        try {
            runtime.evaluate("globalThis." + name + "?.close(); delete globalThis." + name + "; void 0;", "<web-close>").join();
        } finally {
            for (String suffix : new String[]{"Report", "Random", "Uuid", "Crypto"}) runtime.releaseBinding(name + suffix).join();
        }
    }

    private void report(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
        if (!(cause instanceof QuickJS.RuntimeClosedException)) errors.accept(error);
    }
}
