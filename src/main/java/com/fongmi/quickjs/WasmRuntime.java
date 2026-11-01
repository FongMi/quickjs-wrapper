package com.fongmi.quickjs;

import com.dylibso.chicory.runtime.ChicoryInterruptedException;
import com.dylibso.chicory.runtime.GlobalInstance;
import com.dylibso.chicory.runtime.ImportFunction;
import com.dylibso.chicory.runtime.ImportGlobal;
import com.dylibso.chicory.runtime.ImportMemory;
import com.dylibso.chicory.runtime.ImportTable;
import com.dylibso.chicory.runtime.ImportValue;
import com.dylibso.chicory.runtime.TableInstance;
import com.dylibso.chicory.runtime.WasmRuntimeException;
import com.dylibso.chicory.wasm.ChicoryException;
import com.dylibso.chicory.wasm.UninstantiableException;
import com.dylibso.chicory.wasm.types.ExternalType;
import com.dylibso.chicory.wasm.types.FunctionImport;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.GlobalImport;
import com.dylibso.chicory.wasm.types.MemoryImport;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import com.dylibso.chicory.wasm.types.MutabilityType;
import com.dylibso.chicory.wasm.types.TableImport;
import com.dylibso.chicory.wasm.types.TableLimits;
import com.dylibso.chicory.wasm.types.ValType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

public final class WasmRuntime implements AutoCloseable {

  public enum Type {
    I32("i32"),
    I64("i64"),
    F32("f32"),
    F64("f64"),
    EXTERNREF("externref"),
    FUNCREF("funcref");

    private final String name;

    Type(String name) {
      this.name = name;
    }
  }

  public enum Kind {
    FUNCTION,
    MEMORY,
    TABLE,
    GLOBAL
  }

  public record Signature(List<Type> params, List<Type> results) {
    public Signature {
      params = List.copyOf(params);
      results = List.copyOf(results);
    }
  }

  public record Import(String module, String name, Kind kind, Signature signature) {}

  public record Export(String name, Kind kind, Signature signature) {}

  @FunctionalInterface
  public interface HostFunction {
    Object[] call(Instance instance, Object[] arguments) throws Exception;
  }

  private interface Operation<T> {
    T run() throws Exception;
  }

  public static class CompileException extends RuntimeException {

    CompileException(Throwable cause) {
      super(cause.getMessage(), cause);
    }
  }

  public static class LinkException extends RuntimeException {

    LinkException(String message) {
      super(message);
    }

    LinkException(Throwable cause) {
      super(cause.getMessage(), cause);
    }
  }

  public static class TrapException extends RuntimeException {

    TrapException(Throwable cause) {
      super(cause.getMessage(), cause);
    }
  }

  public static class LimitException extends RuntimeException {

    LimitException(Throwable cause) {
      super(cause.getMessage(), cause);
    }
  }

  private static final class Signal extends RuntimeException {

    final Throwable failure;

    Signal(Throwable failure) {
      super(null, failure, false, false);
      this.failure = failure;
    }
  }

  private static final class Call {

    final long started = System.nanoTime();
    final AtomicBoolean canceled = new AtomicBoolean();
    final Map<Instance, Integer> participants = new java.util.concurrent.ConcurrentHashMap<>();
    long deadline = Long.MAX_VALUE;
  }

  private final ReentrantLock lock = new ReentrantLock();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final WasmStore store = new WasmStore(this::check, memory -> {});
  private final Set<Module> modules = new HashSet<>();
  private final Set<Resource> resources = new HashSet<>();
  private final Map<com.dylibso.chicory.runtime.Instance, Instance> instances =
      new IdentityHashMap<>();
  private final Map<Integer, Object> references = new HashMap<>();
  private final Map<Object, Integer> referenceIds = new IdentityHashMap<>();
  private final ConcurrentLinkedQueue<Runnable> closing = new ConcurrentLinkedQueue<>();
  private volatile Call active;
  private int nextReference;

  public Module compile(byte[] binary) throws Exception {
    return compileTimed(0, binary);
  }

  public Module compileTimed(long timeoutMillis, byte[] binary) throws Exception {
    Objects.requireNonNull(binary, "binary");
    return create(
        timeoutMillis,
        () -> {
          try {
            Module module = new Module(store.compile(binary));
            modules.add(module);
            return module;
          } catch (ChicoryException error) {
            throw new CompileException(error);
          }
        });
  }

  public Function function(Signature signature, HostFunction callback) {
    return new Function(
        Objects.requireNonNull(signature), Objects.requireNonNull(callback), null, -1);
  }

  public Instance instantiate(Module module, Map<String, ? extends Map<String, ?>> imports)
      throws Exception {
    return instantiateTimed(0, module, imports);
  }

  public Instance instantiate(Module module) throws Exception {
    return instantiate(module, Map.of());
  }

  public Instance instantiateTimed(
      long timeoutMillis, Module module, Map<String, ? extends Map<String, ?>> imports)
      throws Exception {
    Objects.requireNonNull(module, "module");
    Objects.requireNonNull(imports, "imports");
    return create(
        timeoutMillis,
        () -> {
          requireOwner(module.owner());
          if (module.closed.get()) throw new IllegalStateException("WASM module is closed");
          Instance instance = new Instance(module);
          try {
            List<ImportValue> values = instance.link(imports);
            instance.backend =
                store.instantiate(
                    module.info,
                    values,
                    owner -> instance.enter(),
                    owner -> instance.leave(),
                    instance::poll);
            instances.put(instance.backend, instance);
            instance.handle = store.register(instance);
            instance.backend.initialize(false);
            check();
            return instance;
          } catch (Exception | Error error) {
            instance.closed.set(true);
            instance.dispose();
            if (error instanceof UninstantiableException || error instanceof WasmRuntimeException)
              throw new TrapException(error);
            if (error instanceof ChicoryException) throw new LinkException(error);
            throw error;
          }
        });
  }

  public Memory memory(int initialPages, int maximumPages) throws Exception {
    return create(
        0, () -> new Memory(store.memory(new MemoryLimits(initialPages, maximumPages)), null));
  }

  public Global global(Type type, boolean mutable, Object value) throws Exception {
    if (type == Type.FUNCREF)
      throw new IllegalArgumentException("WASM function globals are not supported");
    return create(
        0,
        () ->
            new Global(
                new GlobalInstance(
                    encode(type, value),
                    0,
                    backendType(type),
                    mutable ? MutabilityType.Var : MutabilityType.Const),
                null));
  }

  public Table table(Type type, int initial, int maximum, Object value) throws Exception {
    if (type != Type.FUNCREF && type != Type.EXTERNREF)
      throw new IllegalArgumentException("WASM table requires a reference type");
    return create(
        0,
        () -> {
          Function function = functionValue(type, value);
          int ref =
              type == Type.EXTERNREF ? reference(value) : function == null ? -1 : function.index;
          var owner = function == null ? null : function.instance.backend;
          return new Table(
              store.table(
                  new com.dylibso.chicory.wasm.types.Table(
                      backendType(type), new TableLimits(initial, maximum)),
                  ref,
                  owner),
              null);
        });
  }

  public void cancel() {
    Call call = active;
    if (call != null) call.canceled.set(true);
  }

  public void checkCancellation() throws Exception {
    execute(0, () -> null);
  }

  private void check() {
    if (closed.get()) throw new IllegalStateException("WASM runtime is closed");
    Call call = active;
    if (Thread.currentThread().isInterrupted())
      throw new Signal(new InterruptedException("WASM execution interrupted"));
    if (call == null) return;
    if (call.canceled.get()) throw new CancellationException("WASM execution canceled");
    if (System.nanoTime() - call.started >= call.deadline)
      throw new Signal(new TimeoutException("WASM execution deadline exceeded"));
    for (Instance instance : call.participants.keySet())
      if (instance.closed.get()) throw new IllegalStateException("WASM instance is closed");
  }

  private <T> T execute(long timeoutMillis, Operation<T> operation) throws Exception {
    return execute(timeoutMillis, operation, false);
  }

  private <T extends AutoCloseable> T create(long timeoutMillis, Operation<T> operation)
      throws Exception {
    return execute(timeoutMillis, operation, true);
  }

  private <T> T execute(long timeoutMillis, Operation<T> operation, boolean owned)
      throws Exception {
    if (timeoutMillis < 0) throw new IllegalArgumentException("Negative WASM timeout");
    long requested = System.nanoTime();
    try {
      if (timeoutMillis == 0) lock.lockInterruptibly();
      else if (!lock.tryLock(timeoutMillis, TimeUnit.MILLISECONDS))
        throw new TimeoutException("WASM execution deadline exceeded");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw error;
    }
    boolean outer = active == null;
    if (outer) active = new Call();
    Call call = active;
    long previous = call.deadline;
    if (timeoutMillis > 0) {
      long elapsed = System.nanoTime() - call.started;
      long duration =
          timeoutMillis > Long.MAX_VALUE / 1_000_000 ? Long.MAX_VALUE : timeoutMillis * 1_000_000;
      long available = Math.max(0, duration - (System.nanoTime() - requested));
      long deadline = available > Long.MAX_VALUE - elapsed ? Long.MAX_VALUE : elapsed + available;
      call.deadline = Math.min(previous, deadline);
    }
    Throwable failure = null;
    T result = null;
    try {
      check();
      result = operation.run();
      check();
      return result;
    } catch (Signal signal) {
      failure = signal.failure;
      if (failure instanceof Exception error) throw error;
      if (failure instanceof Error error) throw error;
      throw signal;
    } catch (WasmLimitException error) {
      LimitException mapped = new LimitException(error);
      failure = mapped;
      throw mapped;
    } catch (ChicoryInterruptedException error) {
      InterruptedException mapped = new InterruptedException(error.getMessage());
      mapped.initCause(error);
      failure = mapped;
      throw mapped;
    } catch (ChicoryException error) {
      TrapException mapped = new TrapException(error);
      failure = mapped;
      throw mapped;
    } catch (Exception | Error error) {
      failure = error;
      throw error;
    } finally {
      call.deadline = previous;
      try {
        if (failure != null && owned && result != null) {
          try {
            ((AutoCloseable) result).close();
          } catch (Exception | Error closing) {
            if (failure != closing) failure.addSuppressed(closing);
          }
        }
        if (outer) {
          active = null;
          clean();
        }
      } catch (Exception | Error error) {
        if (failure == null) {
          failure = error;
          throw error;
        }
        if (failure != error) failure.addSuppressed(error);
      } finally {
        unlock(failure);
      }
    }
  }

  private void unlock(Throwable failure) {
    lock.unlock();
    // A close may enqueue cleanup after the final sweep, while the lock is still held.
    while (!lock.isHeldByCurrentThread() && !closing.isEmpty() && lock.tryLock()) {
      try {
        if (active == null) clean();
      } catch (Exception | Error error) {
        if (failure == null) throw error;
        if (failure != error) failure.addSuppressed(error);
      } finally {
        lock.unlock();
      }
    }
  }

  private void requireOwner(WasmRuntime owner) {
    if (owner != this) throw new LinkException("WASM values belong to another runtime");
  }

  private static Type publicType(ValType type) {
    for (Type value : Type.values()) if (WasmValues.type(value.name).equals(type)) return value;
    throw new IllegalArgumentException("Unsupported WASM type: " + type);
  }

  private static ValType backendType(Type type) {
    return WasmValues.type(Objects.requireNonNull(type, "type").name);
  }

  private static Signature signature(FunctionType type) {
    return new Signature(
        type.params().stream().map(WasmRuntime::publicType).toList(),
        type.returns().stream().map(WasmRuntime::publicType).toList());
  }

  private int reference(Object value) {
    if (value == null) return -1;
    Integer old = referenceIds.get(value);
    if (old != null) return old;
    if (nextReference == Integer.MAX_VALUE)
      throw new IllegalStateException("WASM reference budget exhausted");
    int id = ++nextReference;
    referenceIds.put(value, id);
    references.put(id, value);
    return id;
  }

  private long encode(Type type, Object value) {
    return switch (type) {
      case I32 -> {
        if (!(value instanceof Integer)) throw new IllegalArgumentException("i32 requires Integer");
        yield (Integer) value;
      }
      case I64 -> {
        if (!(value instanceof Long)) throw new IllegalArgumentException("i64 requires Long");
        yield (Long) value;
      }
      case F32 -> {
        if (!(value instanceof Float)) throw new IllegalArgumentException("f32 requires Float");
        yield Float.floatToRawIntBits((Float) value);
      }
      case F64 -> {
        if (!(value instanceof Double)) throw new IllegalArgumentException("f64 requires Double");
        yield Double.doubleToRawLongBits((Double) value);
      }
      case EXTERNREF -> reference(value);
      case FUNCREF -> throw new IllegalArgumentException("WASM function values require a table");
    };
  }

  private Object decode(Type type, long value) {
    return switch (type) {
      case I32 -> (int) value;
      case I64 -> value;
      case F32 -> Float.intBitsToFloat((int) value);
      case F64 -> Double.longBitsToDouble(value);
      case EXTERNREF -> referenceValue((int) value);
      case FUNCREF -> throw new IllegalArgumentException("WASM function values require a table");
    };
  }

  private long[] encode(List<Type> types, Object[] values) {
    if (types.size() != values.length)
      throw new IllegalArgumentException("Invalid WASM value count");
    long[] result = new long[types.size()];
    for (int i = 0; i < result.length; i++) result[i] = encode(types.get(i), values[i]);
    return result;
  }

  private Object[] decode(List<Type> types, long[] values) {
    Object[] result = new Object[types.size()];
    for (int i = 0; i < result.length; i++) result[i] = decode(types.get(i), values[i]);
    return result;
  }

  private Function functionValue(Type type, Object value) {
    if (type != Type.FUNCREF || value == null) return null;
    if (!(value instanceof Function function) || function.instance == null)
      throw new IllegalArgumentException("WASM table requires an exported function");
    requireOwner(function.owner());
    function.instance.requireOpen();
    return function;
  }

  private Object referenceValue(int id) {
    if (id == -1) return null;
    if (!references.containsKey(id))
      throw new IllegalStateException("WASM reference is no longer available");
    return references.get(id);
  }

  private void retain(Set<Integer> roots, GlobalInstance global) {
    if (global.getType().equals(ValType.ExternRef)) roots.add((int) global.getValueLow());
  }

  private void retain(Set<Integer> roots, TableInstance table) {
    if (table.elementType().equals(ValType.ExternRef))
      for (int i = 0; i < table.size(); i++) roots.add(table.ref(i));
  }

  private void clean() {
    for (Runnable cleanup; (cleanup = closing.poll()) != null; ) cleanup.run();
    for (Instance instance : new ArrayList<>(instances.values()))
      if (closed.get() || instance.closed.get()) instance.dispose();
    if (closed.get()) {
      for (Module module : new ArrayList<>(modules)) module.dispose();
      for (Resource resource : new ArrayList<>(resources)) resource.dispose();
      store.clear();
      references.clear();
      referenceIds.clear();
      return;
    }
    Set<Integer> roots = new HashSet<>();
    for (Instance instance : instances.values()) {
      for (int i = 0; i < instance.backend.globalCount(); i++)
        retain(roots, instance.backend.global(i));
      for (int i = 0; i < instance.backend.tableCount(); i++)
        retain(roots, instance.backend.table(i));
    }
    for (Resource resource : resources) {
      if (resource.value instanceof GlobalInstance global) retain(roots, global);
      else if (resource.value instanceof TableInstance table) retain(roots, table);
    }
    references
        .entrySet()
        .removeIf(
            entry -> {
              if (roots.contains(entry.getKey())) return false;
              referenceIds.remove(entry.getValue());
              return true;
            });
  }

  private void closeResource(Runnable cleanup) {
    closing.add(cleanup);
    if (!lock.tryLock()) return;
    try {
      if (active == null) clean();
    } finally {
      unlock(null);
    }
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) closeResource(() -> {});
  }

  public final class Module implements AutoCloseable {

    private WasmModuleInfo info;
    private final int handle;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final List<Import> imports;
    private final List<Export> exports;
    private int users = 1;

    private Module(WasmModuleInfo info) {
      this.info = info;
      imports =
          info.module.importSection().stream()
              .map(
                  value ->
                      new Import(
                          value.module(),
                          value.name(),
                          Kind.valueOf(value.importType().name()),
                          value instanceof FunctionImport function
                              ? WasmRuntime.signature(
                                  info.module.typeSection().getType(function.typeIndex()))
                              : null))
              .toList();
      List<Export> values = new ArrayList<>();
      for (int i = 0; i < info.module.exportSection().exportCount(); i++) {
        var value = info.module.exportSection().getExport(i);
        values.add(
            new Export(
                value.name(),
                Kind.valueOf(value.exportType().name()),
                value.exportType() == ExternalType.FUNCTION
                    ? WasmRuntime.signature(info.functionType(value.index()))
                    : null));
      }
      exports = List.copyOf(values);
      handle = store.register(info);
    }

    private WasmRuntime owner() {
      return WasmRuntime.this;
    }

    public List<Import> imports() {
      return imports;
    }

    public List<Export> exports() {
      return exports;
    }

    public Signature signature(String name) {
      return exports.stream()
          .filter(value -> value.name().equals(name) && value.kind() == Kind.FUNCTION)
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException("Unknown WASM function: " + name))
          .signature();
    }

    public List<byte[]> customSections(String name) throws Exception {
      return execute(
          0,
          () -> {
            if (closed.get()) throw new IllegalStateException("WASM module is closed");
            List<byte[]> result = new ArrayList<>();
            for (Object value :
                info.customSections(Objects.requireNonNull(name), WasmRuntime.this::check))
              result.add((byte[]) value);
            return List.copyOf(result);
          });
    }

    private void release() {
      if (--users == 0) dispose();
    }

    private void dispose() {
      modules.remove(this);
      store.release(handle);
      info = null;
      closed.set(true);
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) closeResource(this::release);
    }
  }

  public final class Function {

    private final Signature signature;
    private final HostFunction callback;
    private final Instance instance;
    private final int index;

    private Function(Signature signature, HostFunction callback, Instance instance, int index) {
      this.signature = signature;
      this.callback = callback;
      this.instance = instance;
      this.index = index;
    }

    private WasmRuntime owner() {
      return WasmRuntime.this;
    }

    public Signature signature() {
      return signature;
    }

    public Object[] call(Object... arguments) throws Exception {
      if (instance == null)
        throw new IllegalStateException("Host functions are called by a WASM instance");
      return execute(0, () -> instance.invoke(index, signature, arguments));
    }
  }

  public final class Instance implements AutoCloseable {

    private Module module;
    private com.dylibso.chicory.runtime.Instance backend;
    private final Map<String, Resource> exports = new LinkedHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private int handle;
    private int instructions;

    private Instance(Module module) {
      this.module = module;
      module.users++;
    }

    private void requireOpen() {
      if (closed.get() || backend == null)
        throw new IllegalStateException("WASM instance is closed");
    }

    private void enter() {
      if (closed.get()) throw new IllegalStateException("WASM instance is closed");
      check();
      active.participants.merge(this, 1, Integer::sum);
    }

    private void leave() {
      active.participants.compute(this, (instance, count) -> count == 1 ? null : count - 1);
    }

    private void poll() {
      if ((++instructions & 1023) == 0) check();
    }

    private List<ImportValue> link(Map<String, ? extends Map<String, ?>> imports) {
      List<ImportValue> values = new ArrayList<>();
      for (var spec : module.info.module.importSection().stream().toList()) {
        Map<String, ?> namespace = imports.get(spec.module());
        Object value = namespace == null ? null : namespace.get(spec.name());
        if (spec instanceof FunctionImport function && value instanceof Function target) {
          requireOwner(target.owner());
          FunctionType expected = module.info.module.typeSection().getType(function.typeIndex());
          if (!signature(expected).equals(target.signature))
            throw new LinkException(
                "Incompatible WASM import: " + spec.module() + "." + spec.name());
          if (target.instance != null) {
            target.instance.requireOpen();
            values.add(
                new ImportFunction(
                    spec.module(),
                    spec.name(),
                    expected,
                    (owner, args) -> {
                      target.instance.requireOpen();
                      return target.instance.backend.getMachine().call(target.index, args);
                    },
                    target.instance.backend,
                    target.index));
          } else
            values.add(
                new ImportFunction(
                    spec.module(),
                    spec.name(),
                    expected,
                    (owner, args) -> {
                      try {
                        Object[] result =
                            target.callback.call(this, decode(target.signature.params(), args));
                        check();
                        return encode(
                            target.signature.results(),
                            Objects.requireNonNull(result, "WASM host results"));
                      } catch (Signal signal) {
                        throw signal;
                      } catch (Exception | Error error) {
                        throw new Signal(error);
                      }
                    }));
        } else if (value instanceof Resource resource) {
          requireOwner(resource.owner());
          Object raw = resource.requireOpen();
          if (spec instanceof MemoryImport && raw instanceof WasmMemory memory)
            values.add(new ImportMemory(spec.module(), spec.name(), memory));
          else if (spec instanceof TableImport && raw instanceof TableInstance table)
            values.add(new ImportTable(spec.module(), spec.name(), table));
          else if (spec instanceof GlobalImport && raw instanceof GlobalInstance global)
            values.add(new ImportGlobal(spec.module(), spec.name(), global));
          else throw new LinkException("Invalid WASM import: " + spec.module() + "." + spec.name());
        } else throw new LinkException("Missing WASM import: " + spec.module() + "." + spec.name());
      }
      return values;
    }

    private Object[] invoke(int index, Signature signature, Object[] arguments) {
      requireOpen();
      long[] values = backend.getMachine().call(index, encode(signature.params(), arguments));
      requireOpen();
      return decode(signature.results(), values);
    }

    public Object[] call(String name, Object... arguments) throws Exception {
      return callTimed(0, name, arguments);
    }

    public Object[] callTimed(long timeoutMillis, String name, Object... arguments)
        throws Exception {
      return execute(
          timeoutMillis,
          () -> {
            requireOpen();
            enter();
            try {
              var function = backend.functionReference(export(name, ExternalType.FUNCTION));
              Instance owner = instances.get(function.instance());
              if (owner == null) throw new IllegalStateException("WASM function owner is closed");
              return owner.invoke(function.index(), module.signature(name), arguments);
            } finally {
              leave();
            }
          });
    }

    public Function function(String name) throws Exception {
      return execute(
          0,
          () -> {
            requireOpen();
            var ref = backend.functionReference(export(name, ExternalType.FUNCTION));
            Instance owner = instances.get(ref.instance());
            if (owner == null) throw new IllegalStateException("WASM function owner is closed");
            return new Function(module.signature(name), null, owner, ref.index());
          });
    }

    private int export(String name, ExternalType kind) {
      for (int i = 0; i < module.info.module.exportSection().exportCount(); i++) {
        var value = module.info.module.exportSection().getExport(i);
        if (value.name().equals(name) && value.exportType() == kind) return value.index();
      }
      throw new IllegalArgumentException("Unknown WASM export: " + name);
    }

    public Memory memory() throws Exception {
      return memory(null);
    }

    public Memory memory(String name) throws Exception {
      return execute(
          0,
          () -> {
            requireOpen();
            String key = "memory:" + name;
            Resource old = exports.get(key);
            if (old != null && !old.closed.get()) return (Memory) old;
            var raw =
                name == null ? backend.memory() : backend.memory(export(name, ExternalType.MEMORY));
            if (!(raw instanceof WasmMemory memory))
              throw new IllegalArgumentException("Missing WASM memory");
            Memory result = new Memory(memory, this);
            exports.put(key, result);
            return result;
          });
    }

    public Global global(String name) throws Exception {
      return execute(
          0,
          () -> {
            requireOpen();
            String key = "global:" + name;
            Resource old = exports.get(key);
            if (old != null && !old.closed.get()) return (Global) old;
            Global result = new Global(backend.global(export(name, ExternalType.GLOBAL)), this);
            exports.put(key, result);
            return result;
          });
    }

    public Table table(String name) throws Exception {
      return execute(
          0,
          () -> {
            requireOpen();
            String key = "table:" + name;
            Resource old = exports.get(key);
            if (old != null && !old.closed.get()) return (Table) old;
            Table result = new Table(backend.table(export(name, ExternalType.TABLE)), this);
            exports.put(key, result);
            return result;
          });
    }

    public void cancel() {
      Call call = active;
      if (call != null && call.participants.containsKey(this)) call.canceled.set(true);
    }

    private void dispose() {
      if (module == null) return;
      instances.remove(backend);
      store.release(handle);
      for (Resource resource : exports.values()) resource.dispose();
      exports.clear();
      backend = null;
      module.release();
      module = null;
      closed.set(true);
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) closeResource(this::dispose);
    }
  }

  public abstract class Resource implements AutoCloseable {

    private Object value;
    private final Instance instance;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final int handle;

    private Resource(Object value, Instance instance) {
      this.value = value;
      this.instance = instance;
      handle = instance == null ? store.register(value) : 0;
      if (instance == null) resources.add(this);
    }

    private WasmRuntime owner() {
      return WasmRuntime.this;
    }

    final Object requireOpen() {
      if (closed.get() || value == null) throw new IllegalStateException("WASM resource is closed");
      if (instance != null) instance.requireOpen();
      return value;
    }

    private void dispose() {
      resources.remove(this);
      if (handle != 0) store.release(handle);
      value = null;
      closed.set(true);
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) closeResource(this::dispose);
    }
  }

  public final class Memory extends Resource {

    private Memory(WasmMemory memory, Instance instance) {
      super(memory, instance);
    }

    public int pages() throws Exception {
      return execute(0, () -> ((WasmMemory) requireOpen()).pages());
    }

    public byte[] read(int offset, int length) throws Exception {
      return execute(0, () -> ((WasmMemory) requireOpen()).readBytes(offset, length));
    }

    public void write(int offset, byte[] bytes) throws Exception {
      execute(
          0,
          () -> {
            ((WasmMemory) requireOpen()).write(offset, Objects.requireNonNull(bytes));
            return null;
          });
    }

    public int grow(int pages) throws Exception {
      return execute(0, () -> ((WasmMemory) requireOpen()).grow(pages));
    }
  }

  public final class Global extends Resource {

    private Global(GlobalInstance global, Instance instance) {
      super(global, instance);
    }

    public Object get() throws Exception {
      return execute(
          0,
          () -> {
            GlobalInstance global = (GlobalInstance) requireOpen();
            return decode(publicType(global.getType()), global.getValueLow());
          });
    }

    public void set(Object value) throws Exception {
      execute(
          0,
          () -> {
            GlobalInstance global = (GlobalInstance) requireOpen();
            if (global.getMutabilityType() != MutabilityType.Var)
              throw new IllegalStateException("Immutable WASM global");
            global.setValueLow(encode(publicType(global.getType()), value));
            return null;
          });
    }
  }

  public final class Table extends Resource {

    private Table(TableInstance table, Instance instance) {
      super(table, instance);
    }

    public int size() throws Exception {
      return execute(0, () -> ((TableInstance) requireOpen()).size());
    }

    public Object get(int index) throws Exception {
      return execute(
          0,
          () -> {
            TableInstance table = (TableInstance) requireOpen();
            if (table.elementType().equals(ValType.ExternRef))
              return decode(Type.EXTERNREF, table.ref(index));
            if (table.ref(index) == -1) return null;
            Instance owner = instances.get(table.instance(index));
            if (owner == null) throw new IllegalStateException("WASM function owner is closed");
            return new Function(
                signature(owner.module.info.functionType(table.ref(index))),
                null,
                owner,
                table.ref(index));
          });
    }

    public void set(int index, Object value) throws Exception {
      execute(
          0,
          () -> {
            TableInstance table = (TableInstance) requireOpen();
            Type type = publicType(table.elementType());
            Function function = functionValue(type, value);
            table.setRef(
                index,
                type == Type.EXTERNREF ? reference(value) : function == null ? -1 : function.index,
                function == null ? null : function.instance.backend);
            return null;
          });
    }

    public int grow(int count, Object value) throws Exception {
      return execute(
          0,
          () -> {
            TableInstance table = (TableInstance) requireOpen();
            Type type = publicType(table.elementType());
            Function function = functionValue(type, value);
            return table.grow(
                count,
                type == Type.EXTERNREF ? reference(value) : function == null ? -1 : function.index,
                function == null ? null : function.instance.backend);
          });
    }
  }
}
