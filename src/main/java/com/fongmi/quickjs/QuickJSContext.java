package com.fongmi.quickjs;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Legacy synchronous Java API over the same owner-thread runtime as {@link QuickJS}.
 * Values retain their JavaScript identity; no source or JSON round trip is used for objects.
 * A context constructed with an existing runtime releases only its own references on close.
 */
public class QuickJSContext implements Closeable {

    public interface Console {

        void log(String info);
        void info(String info);
        void warn(String info);
        void error(String info);
    }

    public interface LeakDetectionListener {

        void notifyLeakDetected(JSObject leak, String stringValue);
    }

    public static abstract class DefaultModuleLoader extends ModuleLoader {

        @Override
        public boolean isBytecodeMode() {
            return false;
        }

        @Override
        public byte[] getModuleBytecode(String name) {
            return null;
        }
    }

    public static abstract class BytecodeModuleLoader extends ModuleLoader {

        @Override
        public boolean isBytecodeMode() {
            return true;
        }

        @Override
        public String getModuleStringCode(String name) {
            return null;
        }
    }

    private static final class Handle {

        final QuickJS.Reference reference;
        int objects;

        Handle(QuickJS.Reference reference) {
            this.reference = reference;
        }
    }

    private static final AtomicInteger NEXT_CONTEXT = new AtomicInteger();
    private static final JSObjectCreator DEFAULT_CREATOR = new JSObjectCreator() {

        @Override
        public JSObject newObject(QuickJSContext context, long pointer) {
            return new QuickJSObject(context, pointer);
        }

        @Override
        public JSArray newArray(QuickJSContext context, long pointer) {
            return new QuickJSArray(context, pointer);
        }

        @Override
        public JSFunction newFunction(QuickJSContext context, long pointer) {
            return new QuickJSFunction(context, pointer);
        }
    };

    private final QuickJS runtime;
    private final boolean ownsRuntime;
    private final JSObjectCreator creator;
    private final long currentThreadId;
    private final String bindingPrefix = "__legacyQuickJS" + NEXT_CONTEXT.incrementAndGet() + "_";
    private final Map<Long, Handle> handles = new HashMap<>();
    private final Map<JSCallFunction, QuickJS.Reference> functions = new IdentityHashMap<>();
    private final Map<Integer, JSCallFunction> callFunctions = new HashMap<>();
    private final Set<String> bindingNames = new HashSet<>();
    private final Map<JSObject, Object> records = new IdentityHashMap<>();
    private int nextFunction;
    private ModuleLoader moduleLoader;
    private JSObject global;
    private LeakDetectionListener leakListener;
    private boolean stackTrace;
    private volatile boolean destroyed;

    public static QuickJSContext create() {
        return create(DEFAULT_CREATOR);
    }

    public static QuickJSContext create(JSObjectCreator creator) {
        Objects.requireNonNull(creator, "creator");
        // The legacy loader initializes the native library, including Android absolute paths.
        QuickJS runtime = new QuickJS(() -> {});
        try {
            return new QuickJSContext(runtime, creator, true);
        } catch (Throwable error) {
            runtime.close();
            throw error;
        }
    }

    /** Wraps this runtime without creating another engine or taking ownership of it. */
    public QuickJSContext(QuickJS runtime) {
        this(runtime, DEFAULT_CREATOR, false);
    }

    private QuickJSContext(QuickJS runtime, JSObjectCreator creator, boolean ownsRuntime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.creator = Objects.requireNonNull(creator, "creator");
        this.ownsRuntime = ownsRuntime;
        currentThreadId = await(runtime.submit(() -> Thread.currentThread().getId()));
        await(com.fongmi.quickjs.Console.initialize(runtime));
    }

    <T> T run(Callable<T> operation) {
        if (destroyed) throw new QuickJSException("QuickJSContext has been destroyed");
        try {
            return runtime.runOnOwner(() -> {
                if (destroyed) throw new QuickJSException("QuickJSContext has been destroyed");
                return operation.call();
            });
        } catch (CompletionException error) {
            throw translate(error.getCause());
        }
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException error) {
            throw translate(error.getCause());
        }
    }

    private static QuickJSException translate(Throwable cause) {
        if (cause instanceof QuickJSException error) return error;
        if (cause instanceof Error error) throw error;
        QuickJSException error = new QuickJSException(cause.getMessage(), cause instanceof QuickJS.ScriptException);
        error.initCause(cause);
        return error;
    }

    private Object operation(String name, Object... args) {
        return await(runtime.reference(name, args));
    }

    private QuickJS.Reference reference(JSObject object) {
        Objects.requireNonNull(object, "object");
        if (object.getContext() != this) throw new QuickJSException("Object belongs to another context");
        if (object.isRefCountZero()) throw new QuickJSException("JavaScript object has been released");
        Handle handle = handles.get(object.getPointer());
        if (handle == null) throw new QuickJSException("Unknown JavaScript object");
        return handle.reference;
    }

    private Object wrap(Object value, Object receiver) {
        if (value instanceof BigInteger integer) return integer.longValue();
        if (value instanceof Double number && Double.isFinite(number) && number == Math.rint(number) && number >= Long.MIN_VALUE && number < 0x1p63) return number.longValue();
        if (!(value instanceof QuickJS.Reference reference)) return value;
        Handle handle = handles.computeIfAbsent(reference.id(), id -> new Handle(reference));
        handle.objects++;
        boolean heldReceiver = false;
        try {
            JSObject object;
            switch (reference.kind()) {
                case "array" -> object = creator.newArray(this, reference.id());
                case "function" -> {
                    if (receiver instanceof QuickJS.Reference) {
                        operation("dup", receiver);
                        heldReceiver = true;
                    }
                    object = creator.newFunction(this, reference.id());
                }

                default -> object = creator.newObject(this, reference.id());
            }
            Objects.requireNonNull(object, "Created JavaScript object");
            if (records.containsKey(object)) throw new QuickJSException("Creator reused a live JavaScript wrapper");
            if (stackTrace) object.setStackTrace(new Throwable("JavaScript reference acquired here"));
            records.put(object, reference.kind().equals("function") ? receiver : null);
            return object;
        } catch (Throwable error) {
            if (heldReceiver) operation("free", receiver);
            operation("free", reference);
            if (--handle.objects == 0) handles.remove(reference.id());
            throw error;
        }
    }

    private Object[] wrapArguments(Object[] args) {
        Object[] wrapped = new Object[args.length];
        Object receiver = null;
        int index = -1;
        try {
            for (Object arg : args) {
                if (arg instanceof QuickJS.Reference ref && ref.kind().equals("function")) {
                    receiver = operation("receiver");
                    break;
                }
            }
            for (index = 0; index < args.length; index++) wrapped[index] = wrap(args[index], receiver);
            return wrapped;
        } catch (Throwable error) {
            for (int i = 0; i < index; i++) if (wrapped[i] instanceof JSObject object) object.release();
            // wrap already releases the argument whose construction failed.
            for (int i = index + 1; i < args.length; i++) if (args[i] instanceof QuickJS.Reference) operation("free", args[i]);
            throw error;
        } finally {
            if (receiver instanceof QuickJS.Reference) operation("free", receiver);
        }
    }

    private Object unwrap(Object value) {
        if (value instanceof JSObject object) return reference(object);
        if (value instanceof JSCallFunction function) {
            QuickJS.Reference existing = functions.get(function);
            if (existing != null) return existing;
            int id = ++nextFunction;
            String name = bindingPrefix + id;
            callFunctions.put(id, function);
            await(runtime.bindReference(name, args -> callBoundFunction(id, args)));
            bindingNames.add(name);
            QuickJS.Reference bound = retainBinding(name);
            functions.put(function, bound);
            return bound;
        }
        if (value instanceof Object[] values) {
            Object[] result = new Object[values.length];
            for (int index = 0; index < values.length; index++) result[index] = unwrap(values[index]);
            return result;
        }
        return value;
    }

    private Object callBoundFunction(int id, Object[] args) {
        Object result = callFunctionBack(id, wrapArguments(args));
        Object converted = unwrap(result);
        if (result instanceof JSObject object) {
            object.decrementRefCount();
            forgetReleased(object);
            return new QuickJS.Transfer((QuickJS.Reference) converted);
        }
        return converted;
    }

    private QuickJS.Reference retainBinding(String name) {
        QuickJS.Reference globalReference = (QuickJS.Reference) operation("global");
        try {
            QuickJS.Reference bound = (QuickJS.Reference) operation("get", globalReference, name);
            Object deleted = operation("evaluate", "delete globalThis['" + name + "']", "<legacy-binding>", false);
            if (deleted instanceof QuickJS.Reference temporary) operation("free", temporary);
            return bound;
        } finally {
            operation("free", globalReference);
        }
    }

    public JSObjectCreator getCreator() {
        return creator;
    }

    public long getCurrentThreadId() {
        return currentThreadId;
    }

    public void setEnableStackTrace(boolean enabled) {
        run(() -> {
            stackTrace = enabled;
            return null;
        });
    }

    public void setLeakDetectionListener(LeakDetectionListener listener) {
        run(() -> {
            leakListener = listener;
            return null;
        });
    }

    public List<JSObject> getObjectRecords() {
        return run(() -> new ArrayList<>(records.keySet()));
    }

    public boolean isLiveObject(JSObject object) {
        if (destroyed || object == null || object.getContext() != this || object.isRefCountZero()) return false;
        return run(() -> handles.containsKey(object.getPointer()));
    }

    public Object evaluate(String script) {
        return evaluate(script, "unknown.js");
    }

    public Object evaluate(String script, String fileName) {
        Objects.requireNonNull(script, "script");
        return run(() -> wrap(operation("evaluate", script, fileName, false), null));
    }

    public Object evaluateModule(String script) {
        return evaluateModule(script, "unknown.js");
    }

    public Object evaluateModule(String script, String name) {
        Objects.requireNonNull(script, "script");
        return run(() -> wrap(operation("evaluate", script, name, true), null));
    }

    public JSObject getGlobalObject() {
        return run(() -> {
            if (global == null || global.isRefCountZero()) global = (JSObject) wrap(operation("global"), null);
            return global;
        });
    }

    public Object getProperty(JSObject object, String name) {
        return run(() -> {
            QuickJS.Reference receiver = reference(object);
            return wrap(operation("get", receiver, Objects.requireNonNull(name, "name")), receiver);
        });
    }

    public void setProperty(JSObject object, String name, Object value) {
        run(() -> operation("set", reference(object), Objects.requireNonNull(name, "name"), unwrap(value)));
    }

    public int length(JSArray array) {
        return ((Number) getProperty(array, "length")).intValue();
    }

    public Object get(JSArray array, int index) {
        return getProperty(array, Integer.toString(index));
    }

    public void set(JSArray array, Object value, int index) {
        setProperty(array, Integer.toString(index), value);
    }

    Object call(JSObject function, Object... args) {
        return run(() -> wrap(await(runtime.callFunction(reference(function), records.get(function), (Object[]) unwrap(args))), null));
    }

    public String stringify(JSObject object) {
        return run(() -> (String) operation("stringify", reference(object)));
    }

    public JSObject createNewJSObject() {
        return run(() -> (JSObject) wrap(operation("newObject"), null));
    }

    public JSArray createNewJSArray(Object... values) {
        Objects.requireNonNull(values, "values");
        Object[] snapshot = values.clone();
        return run(() -> (JSArray) wrap(operation("newArray", unwrap(snapshot)), null));
    }

    public Object parse(String json) {
        return run(() -> wrap(operation("parse", Objects.requireNonNull(json, "json")), null));
    }

    public Object getOwnPropertyNames(JSObject object) {
        return run(() -> createNewJSArray(propertyNames(object)));
    }

    Object[] propertyNames(JSObject object) {
        return (Object[]) operation("keys", reference(object));
    }

    public void hold(JSObject object) {
        run(() -> operation("dup", reference(object)));
    }

    public void freeValue(JSObject object) {
        run(() -> {
            if (object.getContext() != this) throw new QuickJSException("Object belongs to another context");
            Handle handle = handles.get(object.getPointer());
            if (handle == null) throw new QuickJSException("Unknown JavaScript object");
            operation("free", handle.reference);
            forgetReleased(object);
            return null;
        });
    }

    private void forgetReleased(JSObject object) {
        if (!object.isRefCountZero()) return;
        if (!records.containsKey(object)) return;
        Object receiver = records.remove(object);
        if (receiver instanceof QuickJS.Reference) operation("free", receiver);
        Handle handle = handles.get(object.getPointer());
        if (--handle.objects == 0) handles.remove(object.getPointer());
    }

    public Object callFunctionBack(int id, Object... args) {
        return run(() -> {
            JSCallFunction function = callFunctions.get(id);
            if (function == null) throw new QuickJSException("Unknown Java function " + id);
            return function.call(args);
        });
    }

    public void removeCallFunction(int id) {
        run(() -> {
            callFunctions.remove(id);
            return null;
        });
    }

    int getCallFunctionMapSize() {
        return run(callFunctions::size);
    }

    public void releaseObjectRecords() {
        releaseObjectRecords(true);
    }

    public void releaseObjectRecords(boolean release) {
        run(() -> {
            releaseRecords(release, false);
            return null;
        });
    }

    private void releaseRecords(boolean release, boolean includeGlobal) {
        try {
            notifyLeaks();
        } finally {
            if (release) {
                for (JSObject object : new ArrayList<>(records.keySet())) {
                    if (includeGlobal || object != global) {
                        while (!object.isRefCountZero()) object.release();
                    }
                }
            }
        }
    }

    private void notifyLeaks() {
        if (leakListener == null) return;
        List<JSObject> objects = new ArrayList<>(records.keySet());
        JSFunction format = getGlobalObject().getJSFunction("format");
        try {
            for (JSObject object : objects) {
                if (object == global || object.isRefCountZero()) continue;
                if (leakListener != null) leakListener.notifyLeakDetected(object, (String) format.call(object));
            }
        } finally {
            if (format != null) format.release();
        }
    }

    public void setModuleLoader(ModuleLoader loader) {
        Objects.requireNonNull(loader, "loader");
        run(() -> {
            await(runtime.setModuleLoader(new QuickJS.ModuleLoader() {

                @Override
                public String normalize(String base, String name) {
                    return loader.moduleNormalizeName(base, name);
                }

                @Override
                public String load(String name) {
                    return loader.getModuleStringCode(name);
                }

                @Override
                public byte[] loadBytecode(String name) {
                    return loader.isBytecodeMode() ? Objects.requireNonNull(loader.getModuleBytecode(name), "Module bytecode") : null;
                }
            }));
            moduleLoader = loader;
            return null;
        });
    }

    public ModuleLoader getModuleLoader() {
        return run(() -> moduleLoader);
    }

    public byte[] compile(String script) {
        return compile(script, "unknown.js");
    }

    public byte[] compile(String script, String name) {
        return run(() -> (byte[]) operation("compile", script, name, false));
    }

    public byte[] compileModule(String script) {
        return compileModule(script, "unknown.js");
    }

    public byte[] compileModule(String script, String name) {
        return run(() -> (byte[]) operation("compile", script, name, true));
    }

    public Object execute(byte[] code) {
        return run(() -> wrap(operation("execute", Objects.requireNonNull(code, "code")), null));
    }

    public void throwJSException(String error) {
        throw new QuickJSException(error, true);
    }

    public void setConsole(Console console) {
        if (console == null) return;
        run(() -> {
            await(com.fongmi.quickjs.Console.reset(runtime));
            JSObject binding = getGlobalObject().getJSObject("console");
            try {
                binding.setProperty("stdout", args -> writeConsole(console, args));
            } finally {
                binding.release();
            }
            return null;
        });
    }

    private Object writeConsole(Console console, Object[] args) {
        if (args.length != 2) throw new QuickJSException("console.stdout requires a level and a message");
        String message = String.valueOf(args[1]);
        switch (String.valueOf(args[0])) {
            case "info" -> console.info(message);
            case "warn" -> console.warn(message);
            case "error" -> console.error(message);
            default -> console.log(message);
        }
        return null;
    }

    public void setMaxStackSize(int size) {
        run(() -> operation("stackLimit", size));
    }

    public void setMemoryLimit(int size) {
        run(() -> operation("memoryLimit", size));
    }

    public void setGCThreshold(int size) {
        run(() -> operation("gcThreshold", size));
    }

    public void runGC() {
        run(() -> operation("gc"));
    }

    public long getMemoryUsedSize() {
        return run(() -> ((Number) operation("memoryUsed")).longValue());
    }

    public void dumpMemoryUsage() {
        dumpMemoryUsage(null);
    }

    public void dumpMemoryUsage(File target) {
        writeDiagnostic(target, run(() -> ((QuickJS.Json) operation("memory")).text()));
    }

    public void dumpObjects() {
        dumpObjects(null);
    }

    public void dumpObjects(File target) {
        String text = run(() -> {
            StringBuilder result = new StringBuilder("Java compatibility references:\n");
            for (JSObject object : records.keySet()) result.append(object.getPointer()).append(" refs=").append(object.getRefCount()).append('\n');
            return result.toString();
        });
        writeDiagnostic(target, text);
    }

    private void writeDiagnostic(File target, String text) {
        if (target == null) System.out.println(text);
        else {
            try {
                Files.write(target.toPath(), text.getBytes(StandardCharsets.UTF_8));
            } catch (IOException error) {
                throw new QuickJSException("Cannot write diagnostics: " + error.getMessage());
            }
        }
    }

    public void destroy() {
        if (destroyed) return;
        try {
            run(() -> {
                try {
                    releaseRecords(true, true);
                } finally {
                    for (QuickJS.Reference function : functions.values()) operation("free", function);
                    for (String name : bindingNames) await(runtime.releaseBinding(name));
                    bindingNames.clear();
                    functions.clear();
                    callFunctions.clear();
                    handles.clear();
                    global = null;
                    destroyed = true;
                }
                return null;
            });
        } finally {
            if (ownsRuntime) runtime.close();
        }
    }

    @Override
    public void close() throws QuickJSException {
        destroy();
    }
}
