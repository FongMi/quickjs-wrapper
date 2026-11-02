package com.fongmi.quickjs;

import com.dylibso.chicory.runtime.FunctionReferenceStore;
import com.dylibso.chicory.runtime.ImportValue;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.InterpreterMachine;
import com.dylibso.chicory.runtime.TableInstance;
import com.dylibso.chicory.runtime.internal.GcRefStore;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import com.dylibso.chicory.wasm.types.Table;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.LongUnaryOperator;

final class WasmStore implements WasmMemory.Allocator {

  static final int MAX_MODULE = 16 * 1024 * 1024;
  static final int MAX_MODULES = 32 * 1024 * 1024;
  private static final int MAX_TABLE = 32768;
  private static final int MAX_TOTAL_TABLE = 65536;
  private static final int MAX_CACHED_MODULES = 8;
  private static final int MAX_CACHED_BYTES = 2 * 1024 * 1024;
  private final Map<ByteBuffer, WasmModuleInfo> compiled = new LinkedHashMap<>(16, .75f, true);
  private final Map<Integer, Object> handles = new HashMap<>();
  private final IdentityHashMap<Object, Integer> identities = new IdentityHashMap<>();
  private final WasmMemoryBudget memoryBudget = new WasmMemoryBudget();
  private final ReferenceQueue<TableInstance> tableQueue = new ReferenceQueue<>();
  private final Map<PhantomReference<TableInstance>, Integer> tableAllocations = new HashMap<>();
  private final Runnable interruptionCheck;
  private final Consumer<WasmMemory> memoryChanged;
  private final Deque<InvocationScope> calls = new ArrayDeque<>();
  final FunctionReferenceStore functions = new FunctionReferenceStore();
  final GcRefStore heap;
  private final LongUnaryOperator internalize;
  private int nextId;
  private int moduleBytes;
  private int tableElements;
  private int cachedBytes;

  WasmStore(Runnable interruptionCheck, Consumer<WasmMemory> memoryChanged) {
    this(interruptionCheck, memoryChanged, value -> value, value -> null);
  }

  WasmStore(
      Runnable interruptionCheck,
      Consumer<WasmMemory> memoryChanged,
      LongUnaryOperator internalize,
      LongFunction<Object> externalRoot) {
    this.interruptionCheck = interruptionCheck;
    this.memoryChanged = memoryChanged;
    this.internalize = internalize;
    heap =
        new GcRefStore(
            bytes -> {
              if (bytes > 64L * 1024 * 1024)
                throw new WasmLimitException("WASM GC heap budget exhausted");
            },
            externalRoot,
            value -> functions.get((int) value));
  }

  WasmModuleInfo compile(byte[] bytes) {
    if (bytes.length > MAX_MODULE || bytes.length > MAX_MODULES - moduleBytes)
      throw new WasmLimitException("WASM module budget exhausted");
    check();
    WasmModuleInfo cached =
        bytes.length > MAX_CACHED_BYTES ? null : compiled.get(ByteBuffer.wrap(bytes));
    check();
    if (cached != null) return cached.copy();
    WasmModuleInfo module = WasmModuleInfo.parse(bytes.clone(), interruptionCheck);
    check();
    cache(module);
    return module;
  }

  private void cache(WasmModuleInfo module) {
    if (module.bytes > MAX_CACHED_BYTES) return;
    var oldest = compiled.entrySet().iterator();
    while (compiled.size() >= MAX_CACHED_MODULES || module.bytes > MAX_CACHED_BYTES - cachedBytes) {
      cachedBytes -= oldest.next().getValue().bytes;
      oldest.remove();
    }
    compiled.put(module.binaryKey(), module);
    cachedBytes += module.bytes;
  }

  WasmMemory memory(MemoryLimits limits) {
    if (!limits.shared()) return new WasmMemory(limits, this);
    check();
    return memory(SharedMemory.allocate(limits, memoryBudget));
  }

  WasmMemory memory(SharedMemory memory) {
    return new WasmSharedMemory(memory, this);
  }

  Instance instantiate(
      WasmModuleInfo module,
      List<ImportValue> imports,
      Consumer<Instance> enter,
      Consumer<Instance> leave,
      Runnable poll) {
    Invocation invocation = new Invocation(enter, leave);
    return Instance.builder(module.module)
        .withFunctionReferenceStore(functions)
        .withGcRefStore(heap)
        .withReferenceInternalizer(internalize)
        .withMachineFactory(
            owner -> {
              invocation.owner = owner;
              InterpreterMachine machine = new InterpreterMachine(owner);
              return (index, arguments) -> {
                try (InvocationScope scope = new InvocationScope()) {
                  invocation.enter();
                  return machine.call(index, arguments);
                }
              };
            })
        .withImportValuesByIndex(imports)
        .withMemoryFactory(this::memory)
        .withTableFactory(this::table)
        .withUnsafeExecutionListener(
            (instruction, stack) -> {
              invocation.enter();
              poll.run();
            })
        .withInitialize(false)
        .withStart(false)
        .build();
  }

  private final class Invocation {

    final Consumer<Instance> enter;
    final Consumer<Instance> leave;
    Instance owner;

    Invocation(Consumer<Instance> enter, Consumer<Instance> leave) {
      this.enter = enter;
      this.leave = leave;
    }

    void enter() {
      InvocationScope scope = calls.element();
      if (!scope.entered.add(this)) return;
      try {
        enter.accept(owner);
      } catch (RuntimeException | Error error) {
        scope.entered.remove(this);
        throw error;
      }
    }
  }

  private final class InvocationScope implements AutoCloseable {

    final Set<Invocation> entered = new LinkedHashSet<>();

    InvocationScope() {
      heap.enter();
      calls.push(this);
    }

    @Override
    public void close() {
      calls.pop();
      heap.leave();
      List<Invocation> values = new ArrayList<>(entered);
      Throwable failure = null;
      for (int i = values.size() - 1; i >= 0; i--) {
        Invocation value = values.get(i);
        try {
          value.leave.accept(value.owner);
        } catch (RuntimeException | Error error) {
          if (failure == null) failure = error;
          else failure.addSuppressed(error);
        }
      }
      if (failure instanceof RuntimeException error) throw error;
      if (failure instanceof Error error) throw error;
    }
  }

  Integer id(Object object) {
    return identities.get(object);
  }

  Object lookup(int id) {
    return handles.get(id);
  }

  int register(Object object) {
    Integer previous = identities.get(object);
    if (previous != null) return previous;
    if (handles.size() >= 4096 || nextId == Integer.MAX_VALUE)
      throw new WasmLimitException("WASM handle budget exhausted");
    int id = ++nextId;
    handles.put(id, object);
    identities.put(object, id);
    if (object instanceof WasmModuleInfo module) moduleBytes += module.bytes;
    return id;
  }

  <T> T get(Object id, Class<T> type) {
    Object value = handles.get(((Number) id).intValue());
    if (!type.isInstance(value)) throw new IllegalStateException("WASM resource is closed");
    return type.cast(value);
  }

  Object release(int id) {
    Object value = handles.remove(id);
    if (value == null) return null;
    identities.remove(value);
    if (value instanceof WasmModuleInfo module) moduleBytes -= module.bytes;
    return value;
  }

  void collectHeap() {
    heap.collect(new long[0]);
  }

  @Override
  public void check() {
    interruptionCheck.run();
  }

  @Override
  public ByteBuffer allocate(long size) {
    check();
    return memoryBudget.allocate(size);
  }

  @Override
  public void changed(WasmMemory memory) {
    memoryChanged.accept(memory);
  }

  private void collectTables() {
    for (var ref = tableQueue.poll(); ref != null; ref = tableQueue.poll())
      tableElements -= tableAllocations.remove(ref);
  }

  TableInstance table(Table table, int initial) {
    return table(table, initial, null);
  }

  TableInstance table(Table table, int initial, Instance owner) {
    collectTables();
    if (table.limits().min() > MAX_TABLE || table.limits().min() > MAX_TOTAL_TABLE - tableElements)
      throw new WasmLimitException("WASM table budget exhausted");
    return new TrackedTable(table, initial, owner);
  }

  final class TrackedTable extends TableInstance {

    private final PhantomReference<TableInstance> allocation;
    private long revision;

    TrackedTable(Table table, int initial, Instance owner) {
      super(table, initial, owner);
      bindReferences(heap);
      allocation = new PhantomReference<>(this, tableQueue);
      tableAllocations.put(allocation, size());
      tableElements += size();
    }

    long revision() {
      return revision;
    }

    @Override
    public void setRef(int index, int value, Instance instance) {
      super.setRef(index, value, instance);
      revision++;
    }

    @Override
    public void reset() {
      super.reset();
      revision++;
    }

    @Override
    public int grow(int delta, int value, Instance instance) {
      collectTables();
      if (delta < 0 || delta > MAX_TABLE - size() || delta > MAX_TOTAL_TABLE - tableElements)
        return -1;
      int previous = super.grow(delta, value, instance);
      if (previous >= 0) {
        tableElements += delta;
        tableAllocations.put(allocation, size());
        if (delta != 0) revision++;
      }
      return previous;
    }
  }

  void clear() {
    heap.clear();
    functions.clear();
    compiled.clear();
    handles.clear();
    identities.clear();
    tableAllocations.clear();
    moduleBytes = tableElements = cachedBytes = 0;
  }
}
