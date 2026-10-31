package com.fongmi.quickjs;

import static com.fongmi.quickjs.WasmValues.fromWasm;
import static com.fongmi.quickjs.WasmValues.integer;
import static com.fongmi.quickjs.WasmValues.name;
import static com.fongmi.quickjs.WasmValues.names;
import static com.fongmi.quickjs.WasmValues.toWasm;
import static com.fongmi.quickjs.WasmValues.type;

import com.dylibso.chicory.runtime.GlobalInstance;
import com.dylibso.chicory.runtime.ImportFunction;
import com.dylibso.chicory.runtime.ImportGlobal;
import com.dylibso.chicory.runtime.ImportMemory;
import com.dylibso.chicory.runtime.ImportTable;
import com.dylibso.chicory.runtime.ImportValue;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.InterpreterMachine;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.runtime.TableInstance;
import com.dylibso.chicory.runtime.WasmRuntimeException;
import com.dylibso.chicory.wasm.ChicoryException;
import com.dylibso.chicory.wasm.InvalidException;
import com.dylibso.chicory.wasm.UninstantiableException;
import com.dylibso.chicory.wasm.types.Export;
import com.dylibso.chicory.wasm.types.ExternalType;
import com.dylibso.chicory.wasm.types.FunctionImport;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.GlobalImport;
import com.dylibso.chicory.wasm.types.MemoryImport;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import com.dylibso.chicory.wasm.types.MutabilityType;
import com.dylibso.chicory.wasm.types.Table;
import com.dylibso.chicory.wasm.types.TableImport;
import com.dylibso.chicory.wasm.types.TableLimits;
import com.dylibso.chicory.wasm.types.ValType;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class Wasm implements AutoCloseable {

  private static final AtomicInteger IDS = new AtomicInteger();
  private static final String SOURCE = RuntimeScript.read("wasm.js");
  private final QuickJS runtime;
  private final String name = "__quickjsWasm" + IDS.incrementAndGet();
  private final Deque<Runnable> executionChecks = new ArrayDeque<>();
  private final Deque<Execution> executions = new ArrayDeque<>();
  private final Map<Integer, Execution> pendingExecutions = new LinkedHashMap<>();
  private final Runnable settleReferences = this::settleReferences;
  private final WasmStore store = new WasmStore(this::checkInterruption, this::memoryChanged);
  private final IdentityHashMap<Instance, Session> instances = new IdentityHashMap<>();
  private final AtomicBoolean closed = new AtomicBoolean();
  private QuickJS.Reference api;
  private QuickJS.Callback dispatcher;
  private int nextExecution;

  public Wasm(QuickJS runtime) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    runtime
        .submit(
            () -> {
              try {
                install();
                runtime.onClose(() -> dispose(false)).join();
              } catch (RuntimeException | Error error) {
                try {
                  close();
                } catch (RuntimeException | Error failure) {
                  error.addSuppressed(failure);
                }
                throw error;
              }
              return null;
            })
        .join();
  }

  public QuickJS.Reference exports() {
    if (closed.get()) throw new IllegalStateException("WASM runtime is closed");
    return api;
  }

  private void install() {
    runtime.bind(name + "Host", this::command).join();
    runtime.bindReference(name + "Begin", this::begin).join();
    runtime
        .bind(
            name + "Connect",
            args -> {
              dispatcher = (QuickJS.Callback) args[0];
              return null;
            })
        .join();
    runtime
        .bindReference(
            name + "Detach",
            args -> {
              QuickJS.Reference holder = (QuickJS.Reference) args[0];
              try {
                runtime.reference("detachBuffer", holder).join();
              } finally {
                holder.close();
              }
              return null;
            })
        .join();
    String script =
        "try { ("
            + SOURCE
            + ")({host:globalThis."
            + name
            + "Host,connect:globalThis."
            + name
            + "Connect,begin:globalThis."
            + name
            + "Begin,detach:globalThis."
            + name
            + "Detach}); } finally { delete globalThis."
            + name
            + "Host; delete globalThis."
            + name
            + "Connect; delete globalThis."
            + name
            + "Begin; delete globalThis."
            + name
            + "Detach; }";
    api = (QuickJS.Reference) runtime.reference("evaluate", script, "<wasm>", false).join();
  }

  private Object command(Object[] args) {
    String operation = (String) args[0];
    if (closed.get()) throw new IllegalStateException("WASM runtime is closed");
    Runnable check = runtime.interruptionCheck();
    check.run();
    executionChecks.push(check);
    try {
      return new Object[] {null, execute(operation, Arrays.copyOfRange(args, 1, args.length))};
    } catch (HostFailure error) {
      return new Object[] {"host", null};
    } catch (WasmLimitException error) {
      return new Object[] {"RangeError", error.getMessage()};
    } catch (ChicoryException error) {
      String kind =
          operation.equals("compile")
              ? "CompileError"
              : operation.equals("prepare") || operation.equals("start") || operation.equals("link")
                  ? "LinkError"
                  : "RuntimeError";
      if (error instanceof UninstantiableException || error instanceof WasmRuntimeException)
        kind = "RuntimeError";
      return new Object[] {kind, error.getMessage()};
    } catch (StackOverflowError error) {
      return new Object[] {"RangeError", "WASM call stack exhausted"};
    } finally {
      executionChecks.pop();
    }
  }

  private Object execute(String op, Object[] a) {
    switch (op) {
      case "end":
        return execution(a[0]).close();
      case "compile":
        return compile((byte[]) a[0]);
      case "custom":
        return store
            .get(a[0], WasmModuleInfo.class)
            .customSections((String) a[1], this::checkInterruption);
      case "instance":
        return store.register(new Session(store.get(a[0], WasmModuleInfo.class)));
      case "sessionMemory":
        {
          var memory = store.get(a[0], Session.class).instance.memory();
          if (memory == null) throw new WasmRuntimeException("Missing WASM memory");
          return describe(memory);
        }
      case "abort":
        {
          Session session = store.get(a[0], Session.class);
          for (int id : session.createdResources) release(id);
          release(integer(a[0]));
          return null;
        }
      case "functions":
        {
          Session session = store.get(a[0], Session.class);
          List<Object> result = new ArrayList<>();
          for (int i = 0; i < session.compiled.module.exportSection().exportCount(); i++) {
            Export value = session.compiled.module.exportSection().getExport(i);
            if (value.exportType() == ExternalType.FUNCTION)
              result.add(new Object[] {value.name(), session.function(value.index())});
          }
          return result.toArray();
        }
      case "link":
        store.get(a[0], Session.class).link(integer(a[1]), (Object[]) a[2]);
        return null;
      case "prepare":
        return store.get(a[0], Session.class).prepare();
      case "resources":
        return store.get(a[0], Session.class).resources();
      case "references":
        return snapshots((Object[]) a[0]);
      case "start":
        return execution(a[0]).start(store.get(a[1], Session.class));
      case "call":
        return execution(a[0]).call(store.get(a[1], Session.class), integer(a[2]), (Object[]) a[3]);
      case "release":
        return release(integer(a[0]));
      case "memory":
        return describe(store.memory(new MemoryLimits(integer(a[0]), integer(a[1]))));
      case "buffer":
        return new QuickJS.DirectBuffer(store.get(a[0], WasmMemory.class).buffer(), false);
      case "growMemory":
        return store.get(a[0], WasmMemory.class).grow(integer(a[1]));
      case "read":
        return store.get(a[0], WasmMemory.class).readBytes(integer(a[1]), integer(a[2]));
      case "write":
        store.get(a[0], WasmMemory.class).write(integer(a[1]), (byte[]) a[2]);
        return null;
      case "size":
        return store.get(a[0], WasmMemory.class).pages() * Memory.PAGE_SIZE;
      case "table":
        {
          Object[] value = (Object[]) a[3];
          Instance owner =
              integer(value[0]) == 0 ? null : store.get(value[0], Session.class).instance;
          return describe(
              store.table(
                  new Table(
                      type((String) a[0]),
                      new TableLimits(((Number) a[1]).longValue(), ((Number) a[2]).longValue())),
                  integer(value[1]),
                  owner));
        }
      case "tableSize":
        return store.get(a[0], TableInstance.class).size();
      case "tableGet":
        return tableValue(store.get(a[0], TableInstance.class), integer(a[1]));
      case "tableSet":
        {
          TableInstance table = store.get(a[0], TableInstance.class);
          Object[] value = (Object[]) a[2];
          table.setRef(
              integer(a[1]),
              integer(value[1]),
              integer(value[0]) == 0 ? null : store.get(value[0], Session.class).instance);
          return null;
        }
      case "growTable":
        {
          TableInstance table = store.get(a[0], TableInstance.class);
          Object[] value = (Object[]) a[2];
          return table.grow(
              integer(a[1]),
              integer(value[1]),
              integer(value[0]) == 0 ? null : store.get(value[0], Session.class).instance);
        }
      case "global":
        {
          ValType type = type((String) a[0]);
          return describe(
              new GlobalInstance(
                  toWasm(type, a[2]),
                  0,
                  type,
                  Boolean.TRUE.equals(a[1]) ? MutabilityType.Var : MutabilityType.Const));
        }
      case "globalGet":
        {
          GlobalInstance global = store.get(a[0], GlobalInstance.class);
          return fromWasm(global.getType(), global.getValueLow());
        }
      case "globalSet":
        {
          GlobalInstance global = store.get(a[0], GlobalInstance.class);
          if (global.getMutabilityType() != MutabilityType.Var)
            throw new IllegalArgumentException("Immutable WASM global");
          global.setValueLow(toWasm(global.getType(), a[1]));
          return null;
        }
      case "function":
        return store.get(a[0], Session.class).function(integer(a[1]));
      default:
        throw new IllegalArgumentException("Unknown WASM operation");
    }
  }

  private void checkInterruption() {
    executionChecks.element().run();
  }

  private Execution execution(Object id) {
    Execution value = pendingExecutions.get(integer(id));
    if (value == null) throw new IllegalStateException("WASM execution is closed");
    return value;
  }

  private Object begin(Object[] args) {
    QuickJS.Reference frame = (QuickJS.Reference) args[0];
    try {
      if (closed.get()) throw new IllegalStateException("WASM runtime is closed");
      runtime.interruptionCheck().run();
      if (nextExecution == Integer.MAX_VALUE || pendingExecutions.size() >= 4096)
        throw new WasmLimitException("WASM execution budget exhausted");
      Session session = store.get(args[1], Session.class);
      if (session.disposed) throw new IllegalStateException("WASM instance is closed");
      Execution execution = new Execution(++nextExecution, frame);
      execution.pin(session);
      pendingExecutions.put(execution.id, execution);
      runtime.onExecutionExit(settleReferences);
      return execution.id;
    } catch (RuntimeException | Error error) {
      frame.close();
      throw error;
    }
  }

  private void settleReferences() {
    if (pendingExecutions.isEmpty()) return;
    List<Execution> pending = new ArrayList<>(pendingExecutions.values());
    dispatcher
        .callCleanup(
            closed.get() ? "discard" : "settle",
            pending.stream().map(value -> value.frame).toArray())
        .join();
    for (Execution execution : pending) execution.close();
  }

  private final class Execution {

    final int id;
    final QuickJS.Reference frame;
    final Set<Session> participants = new LinkedHashSet<>();

    Execution(int id, QuickJS.Reference frame) {
      this.id = id;
      this.frame = frame;
    }

    boolean pin(Session session) {
      if (participants.contains(session)) return false;
      if (session.disposed) throw new IllegalStateException("WASM instance is closed");
      participants.add(session);
      session.activeExecutions++;
      return true;
    }

    void enter(Session session) {
      if (!pin(session)) return;
      dispatcher.callDetached("enter", frame, store.register(session), session.resources()).join();
    }

    Object[] start(Session session) {
      executions.push(this);
      try {
        enter(session);
        return session.start();
      } finally {
        executions.pop();
      }
    }

    Object[] call(Session session, int index, Object[] arguments) {
      executions.push(this);
      try {
        return session.call(index, arguments);
      } finally {
        executions.pop();
      }
    }

    Object[] close() {
      if (pendingExecutions.remove(id) == null) return new Object[0];
      List<Integer> released = new ArrayList<>();
      for (Session session : participants) {
        if (--session.activeExecutions == 0 && session.disposed) {
          int handle = store.id(session);
          release(handle);
          released.add(handle);
        }
      }
      frame.close();
      return released.toArray();
    }
  }

  private Object[] compile(byte[] bytes) {
    WasmModuleInfo module = store.compile(bytes);
    return new Object[] {store.register(module), module.imports(), module.exports()};
  }

  private boolean release(int id) {
    if (store.lookup(id) instanceof Session session) {
      session.disposed = true;
      if (session.activeExecutions != 0) return false;
      instances.remove(session.instance);
    }
    store.release(id);
    return true;
  }

  private void memoryChanged(WasmMemory memory) {
    Integer id = store.id(memory);
    if (dispatcher != null && id != null) dispatcher.callDetached("grow", id).join();
  }

  private Object[] snapshots(Object[] requests) {
    List<Object> updates = new ArrayList<>();
    for (Object request : requests) {
      Object[] entry = (Object[]) request;
      Object value = store.get(entry[0], Object.class);
      if (value instanceof WasmStore.TrackedTable table) {
        if (((Number) entry[1]).longValue() == table.revision()) continue;
        Object[] refs = new Object[table.size()];
        for (int index = 0; index < refs.length; index++) {
          if ((index & 1023) == 0) checkInterruption();
          refs[index] = tableValue(table, index);
        }
        updates.add(new Object[] {entry[0], BigInteger.valueOf(table.revision()), refs});
      } else {
        GlobalInstance global = (GlobalInstance) value;
        updates.add(
            new Object[] {entry[0], null, fromWasm(global.getType(), global.getValueLow())});
      }
    }
    return updates.toArray();
  }

  private Object[] describe(Object object) {
    int id = store.register(object);
    if (object instanceof WasmMemory memory) return new Object[] {"memory", id};
    if (object instanceof TableInstance table)
      return new Object[] {"table", id, name(table.elementType())};
    GlobalInstance global = (GlobalInstance) object;
    return new Object[] {
      "global", id, name(global.getType()), global.getMutabilityType() == MutabilityType.Var
    };
  }

  private Object[] tableValue(TableInstance table, int index) {
    int ref = table.ref(index);
    Instance instance = table.instance(index);
    Session session = instance == null ? null : instances.get(instance);
    return new Object[] {session == null ? 0 : store.register(session), ref};
  }

  private final class Session {

    final WasmModuleInfo compiled;
    final List<ImportValue> imports = new ArrayList<>();
    final List<Integer> createdResources = new ArrayList<>();
    Instance instance;
    int instructions;
    int activeExecutions;
    boolean disposed;

    Session(WasmModuleInfo compiled) {
      this.compiled = compiled;
    }

    void link(int index, Object[] value) {
      var spec = compiled.module.importSection().getImport(index);
      if (spec instanceof FunctionImport function) {
        FunctionType type = compiled.module.typeSection().getType(function.typeIndex());
        if (value.length > 0) {
          Session source = store.get(value[0], Session.class);
          int target = integer(value[1]);
          imports.add(
              new ImportFunction(
                  spec.module(),
                  spec.name(),
                  source.compiled.functionType(target),
                  (owner, args) -> source.instance.getMachine().call(target, args),
                  source.instance,
                  target));
        } else
          imports.add(
              new ImportFunction(
                  spec.module(),
                  spec.name(),
                  type,
                  (owner, args) -> {
                    instance = owner;
                    Object result =
                        dispatcher
                            .callDetached(
                                "import",
                                store.register(this),
                                index,
                                fromWasm(type.params(), args))
                            .join();
                    if (result == null) throw new HostFailure();
                    return toWasm(type.returns(), (Object[]) result);
                  }));
      } else if (spec instanceof MemoryImport)
        imports.add(
            new ImportMemory(spec.module(), spec.name(), store.get(value[0], WasmMemory.class)));
      else if (spec instanceof TableImport)
        imports.add(
            new ImportTable(spec.module(), spec.name(), store.get(value[0], TableInstance.class)));
      else if (spec instanceof GlobalImport)
        imports.add(
            new ImportGlobal(
                spec.module(), spec.name(), store.get(value[0], GlobalInstance.class)));
    }

    Object[] prepare() {
      instance =
          Instance.builder(compiled.module)
              .withMachineFactory(
                  owner -> {
                    InterpreterMachine machine = new InterpreterMachine(owner);
                    return (index, arguments) -> {
                      executions.element().enter(this);
                      return machine.call(index, arguments);
                    };
                  })
              .withImportValuesByIndex(imports)
              .withMemoryFactory(
                  limits -> {
                    if (limits.shared())
                      throw new InvalidException("Shared WASM memory is disabled");
                    return store.memory(limits);
                  })
              .withTableFactory(store::table)
              .withUnsafeExecutionListener((instruction, stack) -> poll())
              .withInitialize(false)
              .withStart(false)
              .build();
      instances.put(instance, this);
      return resources();
    }

    Object[] resources() {
      List<Object> resources = new ArrayList<>();
      for (int i = 0; i < instance.tableCount(); i++)
        resources.add(describeCreated(instance.table(i)));
      for (int i = 0; i < instance.globalCount(); i++) {
        GlobalInstance global = instance.global(i);
        if (global != null) resources.add(describeCreated(global));
      }
      if (instance.memory() != null) resources.add(describeCreated(instance.memory()));
      return resources.toArray();
    }

    Object[] start() {
      instance.initialize(false);
      List<Object> exports = new ArrayList<>();
      java.util.stream.IntStream.range(0, compiled.module.exportSection().exportCount())
          .mapToObj(compiled.module.exportSection()::getExport)
          .forEach(
              value -> {
                Object descriptor =
                    switch (value.exportType()) {
                      case FUNCTION -> function(value.index());
                      case MEMORY -> describeCreated(instance.memory(value.index()));
                      case TABLE -> describeCreated(instance.table(value.index()));
                      case GLOBAL -> describeCreated(instance.global(value.index()));
                      default -> throw new InvalidException("Unsupported WASM export");
                    };
                exports.add(new Object[] {value.name(), descriptor});
              });
      return new Object[] {exports.toArray(), resources()};
    }

    Object[] describeCreated(Object value) {
      if (store.id(value) == null) createdResources.add(store.register(value));
      return describe(value);
    }

    Object[] function(int index) {
      var reference = instance.functionReference(index);
      Session owner = instances.get(reference.instance());
      int target = reference.index();
      FunctionType type = owner.compiled.functionType(target);
      return new Object[] {
        "function", store.register(owner), target, names(type.params()), names(type.returns())
      };
    }

    Object[] call(int index, Object[] arguments) {
      if (disposed || instance == null) throw new IllegalStateException("WASM instance is closed");
      FunctionType type = compiled.functionType(index);
      return fromWasm(
          type.returns(), instance.getMachine().call(index, toWasm(type.params(), arguments)));
    }

    void poll() {
      if ((++instructions & 1023) != 0) return;
      if (closed.get() || disposed) throw new IllegalStateException("WASM instance is closed");
      checkInterruption();
    }
  }

  private static final class HostFailure extends RuntimeException {

  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    try {
      runtime.continueOnOwner(() -> dispose(true), true).join();
    } catch (CompletionException error) {
      if (!(error.getCause() instanceof QuickJS.RuntimeClosedException)) throw error;
    }
  }

  private void dispose(boolean bindings) {
    closed.set(true);
    for (Execution execution : new ArrayList<>(pendingExecutions.values())) execution.close();
    store.clear();
    instances.clear();
    if (dispatcher != null) dispatcher.close();
    if (api != null) api.close();
    if (bindings)
      for (String suffix : new String[] {"Host", "Connect", "Begin", "Detach"})
        runtime.releaseBinding(name + suffix).join();
  }
}
