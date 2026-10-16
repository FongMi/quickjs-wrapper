package com.fongmi.quickjs;

import java.nio.ByteBuffer;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A QuickJS runtime with one owning thread. Separate instances execute independently.
 *
 * <p>All JavaScript calls return futures. A future for a returned JavaScript Promise settles
 * when that Promise settles. Do not wait for a pending future from the owning thread: that
 * thread also delivers host callbacks and runs Promise jobs.
 */
public final class QuickJS implements AutoCloseable {

    private static final class Deadlines {
        private static final ScheduledThreadPoolExecutor TIMER = timer();

        private static ScheduledThreadPoolExecutor timer() {
            ScheduledThreadPoolExecutor result = new ScheduledThreadPoolExecutor(1, work -> {
                Thread thread = new Thread(work, "QuickJS-deadlines");
                thread.setDaemon(true);
                return thread;
            });
            result.setRemoveOnCancelPolicy(true);
            return result;
        }
    }

    /** A JSON value parsed by the runtime when passed to JavaScript. */
    public record Json(String text) {
        public Json {
            Objects.requireNonNull(text, "text");
        }
    }

    /** A JSON object with one property replaced by an ArrayBuffer. */
    public record Binary(String json, String property, byte[] bytes) {
        public Binary {
            Objects.requireNonNull(json, "json");
            Objects.requireNonNull(property, "property");
            Objects.requireNonNull(bytes, "bytes");
        }
    }

    /** A JSON object with one own data property replaced by an unescaped string. */
    public record Text(String json, String property, String text) {
        public Text {
            Objects.requireNonNull(json, "json");
            Objects.requireNonNull(property, "property");
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * A JSON object with one own data property replaced by a JS Array of signed
     * numbers (-128..127). Keep bytes stable until conversion completes; native
     * conversion copies their values into the Array.
     */
    public record ByteArray(String json, String property, byte[] bytes) {
        public ByteArray {
            Objects.requireNonNull(json, "json");
            Objects.requireNonNull(property, "property");
            Objects.requireNonNull(bytes, "bytes");
        }
    }

    /**
     * Shares the fixed remaining range of a writable direct buffer with JavaScript.
     * Keep its backing allocation alive and use exclusive turns. Before Java reads
     * or writes again, await an owner execute barrier and ensure detached JS work
     * no longer uses the buffer. Returning a JS buffer to Java still creates byte[].
     */
    public record DirectBuffer(ByteBuffer buffer) {
        public DirectBuffer {
            Objects.requireNonNull(buffer, "buffer");
            if (!buffer.isDirect() || buffer.isReadOnly()) throw new IllegalArgumentException("Buffer must be direct and writable");
            buffer = buffer.slice();
        }

        @Override public ByteBuffer buffer() { return buffer.duplicate(); }
    }

    @FunctionalInterface
    public interface Function {
        Object call(Object... args) throws Exception;
    }

    /** Returns an owned, cancelable future; its value must remain stable until conversion. */
    @FunctionalInterface
    public interface AsyncFunction {
        CompletableFuture<?> call(Object... args) throws Exception;
    }

    /** Enters a captured owner-thread context; returns its nonthrowing restore hook. */
    @FunctionalInterface
    public interface AsyncScope {
        Runnable enter();
    }

    public interface ModuleLoader {
        String normalize(String base, String name);

        String load(String name);

        /**
         * Absolute URL of this module, or null when the host has no URL identity.
         * Must not synchronously reenter this runtime. Initialization failure is
         * terminal because a registered engine module cannot be rolled back.
         */
        default String url(String name) { return null; }

        default byte[] loadBytecode(String name) {
            return null;
        }

        /** Opt in once when installed; old loaders do not pay serialization costs. */
        default boolean cacheBytecode() { return false; }

        /** Source-aware cache for trusted, matching-engine bytes. Null is a miss. */
        default byte[] loadBytecode(String name, String source) { return null; }

        /**
         * Stores the already compiled module. Classify recoverable I/O failures in
         * the cache implementation and report them there. A hook failure or a
         * cached decode/link failure closes this runtime: partially registered
         * module definitions cannot be rolled back through the engine's public API.
         * Cache hooks must not reenter JavaScript synchronously.
         */
        default void storeBytecode(String name, String source, byte[] bytes) {}
    }

    /** An owned reference to a JavaScript object; each acquisition must be released once. */
    public static final class Reference implements AutoCloseable {
        private final QuickJS runtime;
        private final long id;
        private final String kind;
        private boolean closed;

        private Reference(QuickJS runtime, long id, String kind) {
            this.runtime = runtime;
            this.id = id;
            this.kind = kind;
        }

        public long id() {
            return id;
        }

        public String kind() {
            return kind;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            runtime.reference("free", this);
        }
    }

    /**
     * Retains a fixed byte range without a payload copy. Use supplies a read-only
     * direct view for a short owner-thread callback; the callback must not retain
     * that view or wait for owner work. JavaScript can change the bytes between
     * callbacks. Resize to a different allocation size fails while pinned;
     * detach/transfer may retire the JS owner without invalidating the lease.
     * A lease remains usable after runtime close and delays native destruction.
     */
    public static final class BufferLease implements AutoCloseable {
        private final BufferCleanup cleanup;

        private BufferLease(QuickJS runtime, long id) {
            cleanup = new BufferCleanup(this, runtime, id);
            BufferReaper.LIVE.add(cleanup);
        }

        public CompletableFuture<Void> use(Consumer<ByteBuffer> reader) {
            return cleanup.runtime.useBuffer(this, Objects.requireNonNull(reader, "reader"));
        }

        @Override public void close() { cleanup.close(); }
    }

    private static final class BufferReaper {
        private static final ReferenceQueue<BufferLease> QUEUE = new ReferenceQueue<>();
        private static final Set<BufferCleanup> LIVE = ConcurrentHashMap.newKeySet();
        static {
            Thread thread = new Thread(() -> {
                while (true) {
                    try {
                        ((BufferCleanup) QUEUE.remove()).close();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "QuickJS-buffer-reaper");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private static final class BufferCleanup extends PhantomReference<BufferLease> {
        private final QuickJS runtime;
        private final long id;
        private boolean closed;

        private BufferCleanup(BufferLease lease, QuickJS runtime, long id) {
            super(lease, BufferReaper.QUEUE);
            this.runtime = runtime;
            this.id = id;
        }

        private void close() { runtime.closeBuffer(this); }
    }

    /** Transfers one retained reference to JavaScript when returned by a host function. */
    public record Transfer(Reference value) {
        public Transfer {
            Objects.requireNonNull(value, "value");
        }
    }

    /** A JavaScript function retained by this runtime until explicitly closed. */
    public static final class Callback implements AutoCloseable {
        private final QuickJS runtime;
        private final long id;
        private boolean closed;

        public Callback(QuickJS runtime, long id) {
            this.runtime = Objects.requireNonNull(runtime, "runtime");
            this.id = id;
        }

        public synchronized CompletableFuture<Object> call(Object... args) {
            return call(args, false);
        }

        /** Starts an independent delivery without inheriting the function's birth call. */
        public synchronized CompletableFuture<Object> callDetached(Object... args) {
            return call(args, true);
        }

        private CompletableFuture<Object> call(Object[] args, boolean detached) {
            if (closed) return failedFuture(new IllegalStateException("Callback is closed"));
            Object[] values = Objects.requireNonNull(args, "args").clone();
            return runtime.call((handle, callId) -> runtime.callback(handle, callId, id, values, detached));
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            runtime.schedule(() -> {
                runtime.release(runtime.handle, id);
                return null;
            }, true, true);
        }
    }

    public static final class ScriptException extends RuntimeException {
        public ScriptException(String message) {
            super(message);
        }
    }

    static final class RuntimeClosedException extends IllegalStateException {
        private RuntimeClosedException() { super("Runtime is closed"); }
    }

    @FunctionalInterface
    private interface Operation {
        void run(long handle, long callId);
    }

    private static final AtomicInteger NEXT_RUNTIME = new AtomicInteger();
    private final Object lifecycle = new Object();
    private final ThreadPoolExecutor executor;
    private final int maxPendingOperations;
    private final Set<PendingOperation> admittedOperations = new HashSet<>();
    private final Map<Long, CompletableFuture<Object>> pending = new HashMap<>();
    private final Set<Long> cleanupCalls = new HashSet<>();
    private final Map<Integer, Function> functions = new HashMap<>();
    private record AsyncBinding(AsyncFunction function, Supplier<AsyncScope> capture) {}
    private final Map<Integer, AsyncBinding> asyncFunctions = new HashMap<>();
    private final Map<Long, AsyncCall> asyncCalls = new HashMap<>();
    private Map<Long, CompletableFuture<Object>> asyncOwners = Map.of();
    private final Map<String, Integer> bindings = new HashMap<>();
    private final Set<CompletableFuture<?>> submissions = new HashSet<>();
    private final ArrayList<Runnable> closeHooks = new ArrayList<>();
    private final CompletableFuture<Void> destroyed = new CompletableFuture<>();
    private volatile Thread ownerThread;
    private ModuleLoader moduleLoader;
    private Consumer<ScriptException> unhandledRejectionListener;
    private boolean reportingRejection;
    private boolean inModuleHook;
    private boolean moduleFailed;
    private long handle;
    private long nextCallId;
    private int nextFunctionId;
    private long nextAsyncId;
    private boolean closed;
    private int bufferLeases;
    private boolean closeCleaned;
    private final Set<Long> timedOutCalls = new HashSet<>();

    public QuickJS() {
        this(0);
    }

    /** Zero is unlimited; positive limits include queued, running and Promise-waiting work. */
    public QuickJS(int maxPendingOperations) {
        this(() -> System.loadLibrary("quickjs-wrapper"), maxPendingOperations);
    }

    /** The loader can load the native library from an Android runtime package. */
    public QuickJS(Runnable libraryLoader) {
        this(libraryLoader, 0);
    }

    /** Limits new external operations; owner reentry and owned continuations do not consume slots. */
    public QuickJS(Runnable libraryLoader, int maxPendingOperations) {
        if (maxPendingOperations < 0) throw new IllegalArgumentException("Pending operation limit must be nonnegative");
        this.maxPendingOperations = maxPendingOperations;
        Objects.requireNonNull(libraryLoader, "libraryLoader").run();
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), work -> {
            Thread thread = new Thread(() -> {
                ownerThread = Thread.currentThread();
                work.run();
            }, "QuickJS-" + NEXT_RUNTIME.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        try {
            schedule(() -> {
                long created = create();
                if (created == 0) throw new IllegalStateException("Native runtime creation failed");
                synchronized (lifecycle) {
                    handle = created;
                }
                return null;
            }, true, true).join();
        } catch (CompletionException error) {
            executor.shutdown();
            throw error;
        }
    }

    public CompletableFuture<Object> evaluate(String source) {
        return evaluate(source, "<eval>");
    }

    public CompletableFuture<Object> evaluate(String source, String name) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(name, "name");
        return call((handle, callId) -> evaluate(handle, callId, source, name, false));
    }

    /** Includes owner-queue time and Promise waits. Zero disables the deadline. */
    public CompletableFuture<Object> evaluateTimed(long timeoutMillis, String source, String name) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(name, "name");
        return call((handle, callId) -> evaluate(handle, callId, source, name, false), null, null, false, timeoutMillis);
    }

    public CompletableFuture<Object> evaluateModule(String source, String name) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(name, "name");
        return call((handle, callId) -> evaluate(handle, callId, source, name, true));
    }

    public CompletableFuture<Object> invoke(String objectName, String method, Object... args) {
        Objects.requireNonNull(objectName, "objectName");
        Objects.requireNonNull(method, "method");
        Object[] values = Objects.requireNonNull(args, "args").clone();
        return call((handle, callId) -> invoke(handle, callId, objectName, method, values));
    }

    /** Runs owner-thread hooks around the native call and its Promise jobs. */
    public CompletableFuture<Object> invoke(String objectName, String method,
            Consumer<CompletableFuture<Object>> before, Runnable after, Object... args) {
        Objects.requireNonNull(objectName, "objectName");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Object[] values = Objects.requireNonNull(args, "args").clone();
        return call((handle, callId) -> invoke(handle, callId, objectName, method, values), before, after, false);
    }

    /** A deadline for one invocation, including its returned Promise and owner-queue wait. */
    public CompletableFuture<Object> invokeTimed(long timeoutMillis, String objectName, String method,
            Consumer<CompletableFuture<Object>> before, Runnable after, Object... args) {
        Objects.requireNonNull(objectName, "objectName");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Object[] values = Objects.requireNonNull(args, "args").clone();
        return call((handle, callId) -> invoke(handle, callId, objectName, method, values), before, after, false, timeoutMillis);
    }

    public CompletableFuture<Void> bind(String name, Function function) {
        return bind(name, function, false);
    }

    /** Binds a Java future as a JavaScript Promise without changing synchronous bind. */
    public CompletableFuture<Void> bindAsync(String name, AsyncFunction function) {
        return bindAsync(name, function, () -> () -> () -> {});
    }

    /**
     * Captures context once before host dispatch, then enters it around resolve/reject
     * and the complete native Promise-job drain. Capture/enter run on the owner outside
     * lifecycle locks. A ticket canceled or closed before settlement claim never enters;
     * restore must not throw.
     * Context is independent of cancellation owners and can outlive its birth call.
     */
    public CompletableFuture<Void> bindAsync(String name, AsyncFunction function, Supplier<AsyncScope> capture) {
        if (isOwnerThread() && reportingRejection) return failedFuture(new IllegalStateException("JavaScript reentry from a rejection listener"));
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(function, "function");
        Objects.requireNonNull(capture, "capture");
        return submit(() -> {
            int id = ++nextFunctionId;
            asyncFunctions.put(id, new AsyncBinding(function, capture));
            try {
                bindAsync(handle, name, id);
                bindings.put(name, id);
            } catch (Throwable error) {
                asyncFunctions.remove(id);
                throw error;
            }
            return null;
        });
    }

    /** Binds a host function whose object arguments retain their JavaScript identity. */
    public CompletableFuture<Void> bindReference(String name, Function function) {
        return bind(name, function, true);
    }

    private CompletableFuture<Void> bind(String name, Function function, boolean references) {
        if (isOwnerThread() && reportingRejection) return failedFuture(new IllegalStateException("JavaScript reentry from a rejection listener"));
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(function, "function");
        return submit(() -> {
            int id = ++nextFunctionId;
            functions.put(id, function);
            try {
                bind(handle, name, id, references);
                bindings.put(name, id);
            } catch (Throwable error) {
                functions.remove(id);
                throw error;
            }
            return null;
        });
    }

    /** Releases the host registration after its JavaScript binding has been removed. */
    public CompletableFuture<Void> releaseBinding(String name) {
        Objects.requireNonNull(name, "name");
        return schedule(() -> {
            Integer id = bindings.remove(name);
            if (id != null) {
                functions.remove(id);
                asyncFunctions.remove(id);
            }
            return null;
        }, true, true);
    }

    /**
     * Performs an object operation without JSON conversion or implicit Promise unwrapping.
     * Supported operations are global, receiver, evaluate, get, set, has, hasOwn, call, stringify, keys,
     * parse, newObject, newArray, dup, free, compile, execute, gc, memory, memoryUsed,
     * memoryLimit, stackLimit and gcThreshold.
     * Compile and execute use this wrapper's versioned QuickJS-NG bytecode; execute accepts
     * only trusted bytecode produced by the matching wrapper version.
     * Has includes inherited properties; hasOwn checks an own descriptor. Neither reads
     * a normal accessor value; Proxy traps have their standard JavaScript behavior.
     * evaluateReference retains buffers too, for leaseBuffer; the existing evaluate
     * operation continues to copy ArrayBuffers to byte[] for legacy callers.
     */
    public CompletableFuture<Object> reference(String operation, Object... args) {
        Objects.requireNonNull(operation, "operation");
        Object[] values = Objects.requireNonNull(args, "args").clone();
        return call((handle, callId) -> reference(handle, callId, operation, values), null, null, "free".equals(operation));
    }

    public CompletableFuture<BufferLease> leaseBuffer(Reference value) {
        Objects.requireNonNull(value, "value");
        if (value.runtime != this) throw new IllegalArgumentException("Reference belongs to another runtime");
        CompletableFuture<BufferLease> result = new CompletableFuture<>();
        schedule(() -> {
            if (result.isDone()) return null;
            synchronized (value) {
                if (value.closed) throw new IllegalStateException("Reference is closed");
                long id = acquireBuffer(handle, value);
                BufferLease lease;
                try {
                    lease = new BufferLease(this, id);
                } catch (Throwable error) {
                    releaseBuffer(handle, id);
                    throw error;
                }
                synchronized (lifecycle) { bufferLeases++; }
                if (!result.complete(lease)) lease.close();
            }
            return null;
        }, true, false, result);
        return result;
    }

    private CompletableFuture<Void> useBuffer(BufferLease lease, Consumer<ByteBuffer> reader) {
        BufferCleanup cleanup = lease.cleanup;
        CompletableFuture<Void> result = new CompletableFuture<>();
        synchronized (lifecycle) {
            if (cleanup.closed) return failedFuture(new IllegalStateException("Buffer lease is closed"));
            Runnable task = admit(() -> {
                synchronized (lifecycle) {
                    if (result.isDone()) return;
                    if (cleanup.closed) {
                        result.completeExceptionally(new IllegalStateException("Buffer lease is closed"));
                        return;
                    }
                }
                try {
                    synchronized (lease) {
                        reader.accept(bufferView(handle, cleanup.id).asReadOnlyBuffer());
                    }
                    result.complete(null);
                } catch (Throwable error) {
                    result.completeExceptionally(error);
                }
            }, result);
            if (task != null) executor.execute(task);
        }
        return result;
    }

    private void closeBuffer(BufferCleanup cleanup) {
        synchronized (lifecycle) {
            if (cleanup.closed) return;
            cleanup.closed = true;
            executor.execute(() -> {
                releaseBuffer(handle, cleanup.id);
                synchronized (lifecycle) {
                    bufferLeases--;
                    destroyIfUnleased();
                }
            });
        }
        BufferReaper.LIVE.remove(cleanup);
        cleanup.clear();
    }

    private void destroyIfUnleased() {
        if (!closeCleaned || bufferLeases != 0) return;
        long current = handle;
        handle = 0;
        if (current != 0) destroy(current);
        executor.shutdown();
    }

    public CompletableFuture<Void> setModuleLoader(ModuleLoader loader) {
        return submit(() -> {
            boolean enabled = Objects.requireNonNull(loader, "loader").cacheBytecode();
            moduleLoader = loader;
            moduleCache(handle, enabled);
            return null;
        });
    }

    /**
     * Controls JavaScript eval and all Function constructors in this runtime.
     * Enabled by default. Host evaluation, imports and trusted bytecode are
     * unaffected; this is a code-generation policy, not a sandbox boundary.
     */
    public CompletableFuture<Void> setCodeGenerationAllowed(boolean allowed) {
        return submit(() -> { codeGeneration(handle, allowed); return null; });
    }

    /** Runs owned resource cleanup once on logical close, before native destruction. */
    public CompletableFuture<Void> onClose(Runnable cleanup) {
        Objects.requireNonNull(cleanup, "cleanup");
        return submit(() -> { closeHooks.add(cleanup); return null; });
    }

    /**
     * Reports unhandled Promise rejections on the owner after the native job drain.
     * Null disables reporting. Listeners may queue work or close the runtime;
     * synchronous JavaScript reentry is rejected while a notification is running.
     */
    public CompletableFuture<Void> setUnhandledRejectionListener(Consumer<ScriptException> listener) {
        return submit(() -> {
            unhandledRejectionListener = listener;
            rejections(handle, listener != null);
            return null;
        });
    }

    /** Sets limits in bytes; zero leaves that limit unchanged. */
    public CompletableFuture<Void> setLimits(long memory, long stack) {
        if (memory < 0 || stack < 0) throw new IllegalArgumentException("Limits must be nonnegative");
        return submit(() -> {
            limits(handle, memory, stack);
            return null;
        });
    }

    /** Executes immediately on the owner thread, or queues work from any other thread. */
    public <T> CompletableFuture<T> submit(Callable<T> work) {
        return schedule(work, true, false);
    }

    /** Queues work even when called from a host function on the owner thread. */
    public CompletableFuture<Void> execute(Runnable work) {
        Objects.requireNonNull(work, "work");
        return schedule(() -> {
            work.run();
            return null;
        }, false, false);
    }

    /** Delivery/cleanup for work already owned by this package, never a new admission. */
    CompletableFuture<Void> continueOnOwner(Runnable work, boolean inline) {
        return schedule(() -> { work.run(); return null; }, inline, true);
    }

    public boolean isOwnerThread() {
        return Thread.currentThread() == ownerThread;
    }

    /**
     * Cancels a snapshot of pending JavaScript calls, including queued calls, while
     * leaving the runtime open. Completion may race and win against cancellation.
     * Later calls, Java submissions and reference cleanup remain unaffected.
     */
    public void cancelPending() {
        ArrayList<CompletableFuture<Object>> outstanding = new ArrayList<>();
        synchronized (lifecycle) {
            // Interrupt native work before cancellation runs Java completion handlers.
            for (Map.Entry<Long, CompletableFuture<Object>> entry : pending.entrySet()) {
                if (entry.getValue().isDone() || cleanupCalls.contains(entry.getKey())) continue;
                outstanding.add(entry.getValue());
                if (handle != 0) interrupt(handle, entry.getKey());
            }
        }
        for (CompletableFuture<?> future : outstanding) future.cancel(false);
    }

    private <T> CompletableFuture<T> schedule(Callable<T> work, boolean inline, boolean cleanup) {
        return schedule(work, inline, cleanup, new CompletableFuture<>());
    }

    private <T> CompletableFuture<T> schedule(Callable<T> work, boolean inline, boolean cleanup, CompletableFuture<T> result) {
        Objects.requireNonNull(work, "work");
        if (inline && isOwnerThread() && (inModuleHook || moduleFailed)) {
            result.completeExceptionally(new IllegalStateException("Owner reentry during module initialization"));
            return result;
        }
        Runnable task = () -> {
            synchronized (lifecycle) {
                if (closed || result.isDone()) return;
            }
            try {
                T value = work.call();
                if (moduleFailed) result.completeExceptionally(new IllegalStateException("Module initialization failed; runtime is closing"));
                else result.complete(value);
            } catch (Throwable error) {
                result.completeExceptionally(error);
            } finally {
                if (moduleFailed) close();
            }
        };
        boolean runInline;
        synchronized (lifecycle) {
            if (closed) {
                result.completeExceptionally(new RuntimeClosedException());
                return result;
            }
            submissions.add(result);
            result.whenComplete((value, error) -> {
                synchronized (lifecycle) {
                    submissions.remove(result);
                }
            });
            runInline = inline && isOwnerThread();
            if (!runInline) {
                Runnable queued = cleanup ? task : admit(task, result);
                if (queued != null) executor.execute(queued);
            }
        }
        if (runInline) task.run();
        return result;
    }

    // Called under lifecycle. Completion releases admission and removes canceled queued work.
    private Runnable admit(Runnable task, CompletableFuture<?> result) {
        if (maxPendingOperations == 0) return task;
        if (admittedOperations.size() == maxPendingOperations) {
            // A user's completion handler can run before ours. Terminal futures
            // no longer occupy capacity regardless of callback execution order.
            for (Iterator<PendingOperation> iterator = admittedOperations.iterator(); iterator.hasNext(); ) {
                PendingOperation operation = iterator.next();
                if (!operation.result.isDone()) continue;
                iterator.remove();
                if (executor.remove(operation)) operation.task = null;
            }
        }
        if (admittedOperations.size() == maxPendingOperations) {
            result.completeExceptionally(new RejectedExecutionException("QuickJS pending operation limit reached: " + maxPendingOperations));
            return null;
        }
        PendingOperation operation = new PendingOperation(task, result);
        admittedOperations.add(operation);
        result.whenComplete((value, error) -> {
            synchronized (lifecycle) {
                if (admittedOperations.remove(operation) && executor.remove(operation)) operation.task = null;
            }
        });
        return operation;
    }

    private static final class PendingOperation implements Runnable {
        private Runnable task;
        private final CompletableFuture<?> result;

        private PendingOperation(Runnable task, CompletableFuture<?> result) {
            this.task = task;
            this.result = result;
        }

        @Override public void run() {
            try { task.run(); }
            finally { task = null; }
        }
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable error) {
        CompletableFuture<T> result = new CompletableFuture<>();
        result.completeExceptionally(error);
        return result;
    }

    private CompletableFuture<Object> call(Operation operation) {
        return call(operation, null, null, false);
    }

    private CompletableFuture<Object> call(Operation operation,
            Consumer<CompletableFuture<Object>> before, Runnable after, boolean cleanup) {
        return call(operation, before, after, cleanup, 0);
    }

    private CompletableFuture<Object> call(Operation operation,
            Consumer<CompletableFuture<Object>> before, Runnable after, boolean cleanup, long timeoutMillis) {
        if (timeoutMillis < 0) throw new IllegalArgumentException("Timeout must be nonnegative");
        if (isOwnerThread() && reportingRejection && !cleanup) return failedFuture(new IllegalStateException("JavaScript reentry from a rejection listener"));
        if (isOwnerThread() && (inModuleHook || moduleFailed) && !cleanup) return failedFuture(new IllegalStateException("JavaScript reentry during module initialization"));
        CompletableFuture<Object> result = new CompletableFuture<>();
        long callId;
        Runnable task;
        boolean runInline;
        synchronized (lifecycle) {
            if (closed) return failedFuture(new RuntimeClosedException());
            callId = ++nextCallId;
            pending.put(callId, result);
            if (cleanup) cleanupCalls.add(callId);
            result.whenComplete((value, error) -> {
                ArrayList<AsyncCall> canceled = null;
                boolean cancel = result.isCancelled() || error instanceof TimeoutException;
                synchronized (lifecycle) {
                    pending.remove(callId);
                    cleanupCalls.remove(callId);
                    timedOutCalls.remove(callId);
                    if (cancel && handle != 0) interrupt(handle, callId);
                    if (!asyncCalls.isEmpty()) for (Iterator<AsyncCall> iterator = asyncCalls.values().iterator(); iterator.hasNext(); ) {
                        AsyncCall asynchronous = iterator.next();
                        if (asynchronous.owners.remove(callId) == null || !cancel || asynchronous.settling) continue;
                        iterator.remove();
                        if (canceled == null) canceled = new ArrayList<>();
                        canceled.add(asynchronous);
                    }
                }
                if (canceled != null) cancelAsync(canceled);
            });
            task = () -> {
                synchronized (lifecycle) {
                    if (closed || result.isDone() || timedOutCalls.contains(callId)) return;
                }
                try {
                    try {
                        if (before != null) before.accept(result);
                        if (!result.isDone()) operation.run(handle, callId);
                    } finally {
                        try {
                            if (after != null) after.run();
                        } finally {
                            if (moduleFailed) close();
                        }
                    }
                } catch (Throwable error) {
                    complete(callId, null, error);
                }
            };
            runInline = isOwnerThread() && !reportingRejection;
            Runnable queued = runInline || cleanup ? task : admit(task, result);
            if (queued == null) return result;
            if (timeoutMillis > 0) {
                long id = callId;
                ScheduledFuture<?> deadline = Deadlines.TIMER.schedule(() -> {
                    synchronized (lifecycle) {
                        if (result.isDone()) return;
                        timedOutCalls.add(id);
                        if (handle != 0) interrupt(handle, id);
                    }
                    result.completeExceptionally(new TimeoutException("JavaScript call exceeded " + timeoutMillis + " ms"));
                }, timeoutMillis, TimeUnit.MILLISECONDS);
                result.whenComplete((value, error) -> deadline.cancel(false));
            }
            if (!runInline) executor.execute(queued);
        }
        if (runInline) task.run();
        return result;
    }

    private Object dispatch(int id, Object[] args) throws Exception {
        if (!isOwnerThread()) throw new IllegalStateException("Host function called outside owner thread");
        Function function = functions.get(id);
        if (function == null) {
            discard(args);
            throw new IllegalStateException("Unknown host function: " + id);
        }
        return function.call(args);
    }

    private final class AsyncCall {
        private final long id = ++nextAsyncId;
        private final Map<Long, CompletableFuture<Object>> owners;
        private final Callback resolve, reject;
        private CompletableFuture<?> source;
        private AsyncScope scope = () -> () -> {};
        private boolean settling;

        private AsyncCall(Map<Long, CompletableFuture<Object>> owners, Callback resolve, Callback reject) {
            this.owners = owners;
            this.resolve = resolve;
            this.reject = reject;
        }

        private void release() {
            resolve.close();
            reject.close();
        }
    }

    private void dispatchAsync(int id, Object[] args, Callback resolve, Callback reject) {
        if (!isOwnerThread()) throw new IllegalStateException("Async host called outside owner thread");
        AsyncCall operation;
        synchronized (lifecycle) {
            if (closed) throw new RuntimeClosedException();
            long[] nativeOwners = scope(handle);
            Map<Long, CompletableFuture<Object>> owners = new HashMap<>();
            for (CompletableFuture<?> owner : asyncOwners.values()) {
                if (owner.isCancelled()) throw new CancellationException("JavaScript call canceled");
            }
            for (long call : nativeOwners) {
                CompletableFuture<Object> owner = pending.get(call);
                if (owner != null && owner.isCancelled()) throw new CancellationException("JavaScript call canceled");
                if (owner != null && !owner.isDone()) owners.put(call, owner);
            }
            for (Map.Entry<Long, CompletableFuture<Object>> owner : asyncOwners.entrySet()) {
                if (pending.get(owner.getKey()) == owner.getValue() && !owner.getValue().isDone()) owners.put(owner.getKey(), owner.getValue());
            }
            operation = new AsyncCall(owners, resolve, reject);
            asyncCalls.put(operation.id, operation);
        }
        CompletableFuture<?> source;
        try {
            AsyncBinding binding = asyncFunctions.get(id);
            if (binding == null) {
                discard(args);
                throw new IllegalStateException("Unknown async host function: " + id);
            }
            try { operation.scope = Objects.requireNonNull(binding.capture.get(), "Async scope is null"); }
            catch (Throwable error) { discard(args); throw error; }
            source = Objects.requireNonNull(binding.function.call(args), "Async host returned a null future");
        } catch (Throwable error) {
            source = failedFuture(error);
        }
        boolean active;
        synchronized (lifecycle) {
            operation.source = source;
            active = asyncCalls.get(operation.id) == operation;
        }
        if (!active) source.cancel(true);
        long ticket = operation.id;
        source.whenComplete((value, error) -> continueOnOwner(() -> settleAsync(ticket, value, error), false));
    }

    private void cancelAsync(ArrayList<AsyncCall> operations) {
        for (AsyncCall operation : operations) {
            CompletableFuture<?> source;
            synchronized (lifecycle) { source = operation.source; }
            if (source != null) source.cancel(true);
            continueOnOwner(operation::release, false);
        }
    }

    private void settleAsync(long id, Object value, Throwable error) {
        AsyncCall operation;
        Map<Long, CompletableFuture<Object>> owners;
        boolean canceled;
        synchronized (lifecycle) {
            operation = asyncCalls.get(id);
            if (operation == null || closed || operation.settling) return;
            canceled = operation.owners.values().stream().anyMatch(CompletableFuture::isCancelled);
            if (canceled) asyncCalls.remove(id);
            else operation.settling = true;
            owners = new HashMap<>(operation.owners);
        }
        if (canceled) { operation.release(); return; }
        Map<Long, CompletableFuture<Object>> previous = asyncOwners;
        asyncOwners = owners;
        long[] calls = owners.keySet().stream().mapToLong(Long::longValue).toArray();
        Runnable restore = () -> {};
        try {
            try { restore = Objects.requireNonNull(operation.scope.enter(), "Async restore hook is null"); }
            catch (Throwable failure) { error = failure; }
            if (error == null) {
                try {
                    validateAsyncValue(value, 0);
                    call((handle, callId) -> settle(handle, callId, operation.resolve.id, value, calls, false)).join();
                } catch (Throwable failure) { error = failure; }
            }
            if (error != null) {
                Throwable failure = error;
                while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
                String message = failure.toString();
                call((handle, callId) -> settle(handle, callId, operation.reject.id, message, calls, true)).join();
            }
        } finally {
            try { restore.run(); }
            finally {
                asyncOwners = previous;
                synchronized (lifecycle) { asyncCalls.remove(id); }
                operation.release();
            }
        }
    }

    private static void validateAsyncValue(Object value, int depth) {
        if (depth > 64) throw new IllegalArgumentException("Async conversion exceeds 64 levels");
        if (value instanceof Reference || value instanceof Callback || value instanceof Transfer) throw new IllegalArgumentException("Async host results must be by value");
        if (value instanceof Object[] items) for (Object item : items) validateAsyncValue(item, depth + 1);
    }

    private static void discard(Object value) {
        if (value instanceof Reference reference) reference.close();
        else if (value instanceof Callback callback) callback.close();
        else if (value instanceof Object[] items) for (Object item : items) discard(item);
    }

    private boolean complete(long callId, Object value, String error) {
        if (moduleFailed && error == null) error = "Module initialization failed; runtime is closing";
        boolean accepted = complete(callId, value, error == null ? null : new ScriptException(error));
        return accepted && error == null;
    }

    private void unhandledRejection(String message) {
        Consumer<ScriptException> listener;
        synchronized (lifecycle) {
            if (closed) return;
            listener = unhandledRejectionListener;
        }
        if (listener == null) return;
        ScriptException rejection = new ScriptException(message);
        reportingRejection = true;
        try {
            listener.accept(rejection);
        } catch (Throwable failure) {
            rejection.printStackTrace(System.err);
            failure.printStackTrace(System.err);
        } finally {
            reportingRejection = false;
        }
    }

    private boolean complete(long callId, Object value, Throwable error) {
        CompletableFuture<Object> result;
        synchronized (lifecycle) {
            if (timedOutCalls.contains(callId)) return false;
            result = pending.get(callId);
        }
        if (result == null) return false;
        return error == null ? result.complete(value) : result.completeExceptionally(error);
    }

    private String normalize(String base, String name) {
        return moduleLoader == null ? name : Objects.requireNonNull(moduleLoader.normalize(base, name), "Normalized module name");
    }

    private String load(String name) {
        if (moduleLoader == null) throw new IllegalStateException("No module loader for " + name);
        return Objects.requireNonNull(moduleLoader.load(name), "Module source");
    }

    private String moduleUrl(String name) {
        inModuleHook = true;
        try {
            String url = moduleLoader == null ? null : moduleLoader.url(name);
            if (url != null && (!java.net.URI.create(url).isAbsolute() || url.indexOf('\0') >= 0))
                throw new IllegalArgumentException("Module URL must be absolute");
            return url;
        } finally { inModuleHook = false; }
    }

    private byte[] loadBytecode(String name) {
        return moduleLoader == null ? null : moduleLoader.loadBytecode(name);
    }

    private byte[] loadBytecode(String name, String source) {
        inModuleHook = true;
        try {
            return moduleLoader.loadBytecode(name, source);
        } catch (RuntimeException | Error error) {
            moduleFailed = true;
            throw error;
        } finally { inModuleHook = false; }
    }

    private void storeBytecode(String name, String source, byte[] bytes) {
        inModuleHook = true;
        try {
            moduleLoader.storeBytecode(name, source, bytes);
        } catch (RuntimeException | Error error) {
            moduleFailed = true;
            throw error;
        } finally { inModuleHook = false; }
    }

    private void failModule() { moduleFailed = true; }

    /**
     * Interrupts JavaScript and fails outstanding futures on its owner. Calls elsewhere
     * wait for logical close. Native destruction waits for any outstanding buffer leases.
     */
    @Override
    public void close() {
        ArrayList<CompletableFuture<?>> outstanding = new ArrayList<>();
        ArrayList<AsyncCall> asynchronous = new ArrayList<>();
        synchronized (lifecycle) {
            if (!closed) {
                closed = true;
                if (handle != 0) interrupt(handle, 0);
                outstanding.addAll(pending.values());
                outstanding.addAll(submissions);
                pending.clear();
                cleanupCalls.clear();
                asynchronous.addAll(asyncCalls.values());
                asyncCalls.clear();
                executor.execute(this::closeOwner);
            }
        }
        IllegalStateException error = new RuntimeClosedException();
        for (CompletableFuture<?> future : outstanding) future.completeExceptionally(error);
        cancelAsync(asynchronous);
        if (!isOwnerThread()) destroyed.join();
    }

    private void closeOwner() {
        Throwable failure = null;
        for (Runnable hook : closeHooks) {
            try { hook.run(); }
            catch (Throwable error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        closeHooks.clear();
        try {
            synchronized (lifecycle) {
                closeCleaned = true;
                destroyIfUnleased();
            }
        } catch (Throwable error) {
            if (failure == null) failure = error;
            else if (failure != error) failure.addSuppressed(error);
        } finally {
            functions.clear();
            asyncFunctions.clear();
            bindings.clear();
            moduleLoader = null;
            unhandledRejectionListener = null;
        }
        if (failure == null) destroyed.complete(null);
        else destroyed.completeExceptionally(failure);
    }

    static native void writeConsole(int priority, byte[] message);

    private native long create();

    private native long acquireBuffer(long handle, Reference value);

    private native ByteBuffer bufferView(long handle, long id);

    private native void releaseBuffer(long handle, long id);

    private native void moduleCache(long handle, boolean enabled);

    private native void codeGeneration(long handle, boolean allowed);

    private native void rejections(long handle, boolean enabled);

    private native void evaluate(long handle, long callId, String source, String name, boolean module);

    private native void invoke(long handle, long callId, String objectName, String method, Object[] args);

    private native void callback(long handle, long callId, long callbackId, Object[] args, boolean detached);

    private native void bind(long handle, String name, int functionId, boolean references);

    private native void bindAsync(long handle, String name, int functionId);

    private native long[] scope(long handle);

    private native void settle(long handle, long callId, long callbackId, Object value, long[] owners, boolean rejected);

    private native void reference(long handle, long callId, String operation, Object[] args);

    private native void release(long handle, long callbackId);

    private native void limits(long handle, long memory, long stack);

    private native void interrupt(long handle, long callId);

    private native void destroy(long handle);
}
