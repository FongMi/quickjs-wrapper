package com.fongmi.quickjs;

import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.TableInstance;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import com.dylibso.chicory.wasm.types.Table;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;

final class WasmStore implements WasmMemory.Allocator {

  private static final int MAX_MODULE = 16 * 1024 * 1024;
  private static final int MAX_MODULES = 32 * 1024 * 1024;
  private static final int MAX_MEMORY = 64 * 1024 * 1024;
  private static final int MAX_TOTAL_MEMORY = 128 * 1024 * 1024;
  private static final int MAX_TABLE = 32768;
  private static final int MAX_TOTAL_TABLE = 65536;
  private final Map<Integer, Object> handles = new HashMap<>();
  private final IdentityHashMap<Object, Integer> identities = new IdentityHashMap<>();
  private final ReferenceQueue<ByteBuffer> memoryQueue = new ReferenceQueue<>();
  private final Map<PhantomReference<ByteBuffer>, Integer> allocations = new HashMap<>();
  private final ReferenceQueue<TableInstance> tableQueue = new ReferenceQueue<>();
  private final Map<PhantomReference<TableInstance>, Integer> tableAllocations = new HashMap<>();
  private final Runnable interruptionCheck;
  private final Consumer<WasmMemory> memoryChanged;
  private int nextId;
  private int moduleBytes;
  private int memoryBytes;
  private int tableElements;

  WasmStore(Runnable interruptionCheck, Consumer<WasmMemory> memoryChanged) {
    this.interruptionCheck = interruptionCheck;
    this.memoryChanged = memoryChanged;
  }

  WasmModuleInfo compile(byte[] bytes) {
    if (bytes.length > MAX_MODULE || bytes.length > MAX_MODULES - moduleBytes)
      throw new WasmLimitException("WASM module budget exhausted");
    return WasmModuleInfo.parse(bytes, interruptionCheck);
  }

  WasmMemory memory(MemoryLimits limits) {
    return new WasmMemory(limits, this);
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

  @Override
  public void check() {
    interruptionCheck.run();
  }

  @Override
  public ByteBuffer allocate(long size) {
    check();
    for (var ref = memoryQueue.poll(); ref != null; ref = memoryQueue.poll())
      memoryBytes -= allocations.remove(ref);
    if (size < 0 || size > MAX_MEMORY || size > MAX_TOTAL_MEMORY - memoryBytes)
      throw new WasmLimitException("WASM memory budget exhausted");
    ByteBuffer buffer;
    try {
      buffer = ByteBuffer.allocateDirect((int) size);
    } catch (OutOfMemoryError error) {
      throw new WasmLimitException("WASM memory allocation failed");
    }
    allocations.put(new PhantomReference<>(buffer, memoryQueue), (int) size);
    memoryBytes += (int) size;
    return buffer;
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
    handles.clear();
    identities.clear();
    allocations.clear();
    tableAllocations.clear();
    moduleBytes = memoryBytes = tableElements = 0;
  }
}
