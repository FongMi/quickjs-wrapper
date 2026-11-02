package com.fongmi.quickjs;

import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.runtime.WasmRuntimeException;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;

/** Shared WASM storage. Bind it separately in each runtime; views keep the allocation alive. */
public final class SharedMemory {

  static final int MAX_BYTES = 64 * 1024 * 1024;
  static final int ATOMIC_ADD = 0;
  static final int ATOMIC_AND = 1;
  static final int ATOMIC_OR = 2;
  static final int ATOMIC_SUB = 3;
  static final int ATOMIC_XOR = 4;
  static final int ATOMIC_EXCHANGE = 5;
  static final int ATOMIC_COMPARE_EXCHANGE = 6;
  static final int ATOMIC_LOAD = 7;
  static final int ATOMIC_STORE = 8;
  private final MemoryLimits limits;
  private final ByteBuffer storage;
  private final WasmMemoryBudget.Charge charge;
  private volatile int pages;

  private static final class Cleanup extends PhantomReference<SharedMemory> {

    private static final ReferenceQueue<SharedMemory> QUEUE = new ReferenceQueue<>();
    private static final Set<Cleanup> LIVE = new HashSet<>();
    private static Thread thread;
    private final ByteBuffer storage;

    Cleanup(SharedMemory memory, ByteBuffer storage) {
      super(memory, QUEUE);
      this.storage = storage;
      synchronized (LIVE) {
        try {
          LIVE.add(this);
          if (thread == null) {
            thread = new Thread(Cleanup::reap, "QuickJS-shared-memory");
            thread.setDaemon(true);
            thread.start();
          }
        } catch (RuntimeException | Error error) {
          LIVE.remove(this);
          clear();
          if (thread != null && !thread.isAlive()) thread = null;
          throw error;
        }
      }
    }

    private static void reap() {
      while (true) {
        synchronized (LIVE) {
          if (LIVE.isEmpty()) {
            thread = null;
            return;
          }
        }
        try {
          Cleanup cleanup = (Cleanup) QUEUE.remove();
          release0(cleanup.storage);
          synchronized (LIVE) {
            LIVE.remove(cleanup);
          }
          cleanup.clear();
        } catch (InterruptedException ignored) {
          // The reaper stops after releasing the last registered allocation.
        }
      }
    }
  }

  static final class Pin implements AutoCloseable {

    private final ByteBuffer storage;

    private Pin(ByteBuffer storage) {
      this.storage = storage;
    }

    @Override
    public void close() {
      release0(storage);
    }
  }

  private static final class Native {

    static {
      try {
        if (abi0() != 2) throw new LinkageError("Unsupported shared-memory native ABI");
      } catch (UnsatisfiedLinkError missing) {
        System.loadLibrary("quickjs-wrapper");
        if (abi0() != 2) throw new LinkageError("Unsupported shared-memory native ABI");
      }
    }

    static void ensure() {}
  }

  private SharedMemory(MemoryLimits limits, ByteBuffer storage, WasmMemoryBudget.Charge charge) {
    this.limits = limits;
    this.storage = storage.order(ByteOrder.LITTLE_ENDIAN);
    this.charge = charge;
    pages = limits.initialPages();
    new Cleanup(this, storage);
  }

  /** Allocates bounded, stable storage. Runtime allocations use the same per-store budgets. */
  public static SharedMemory allocate(int initialPages, int maximumPages) {
    return allocate(new MemoryLimits(initialPages, maximumPages, true), new WasmMemoryBudget());
  }

  static SharedMemory allocate(MemoryLimits limits, WasmMemoryBudget budget) {
    int capacity = capacity(limits);
    Native.ensure();
    int size = limits.initialPages() * Memory.PAGE_SIZE;
    WasmMemoryBudget.Charge charge = budget.charge(size);
    ByteBuffer storage = null;
    try {
      storage = allocate0(capacity, size, charge);
      return new SharedMemory(limits, storage, charge);
    } catch (RuntimeException | Error error) {
      if (storage != null) release0(storage);
      charge.cancel();
      throw error;
    }
  }

  static int capacity(MemoryLimits limits) {
    if (limits.initialPages() > MAX_BYTES / Memory.PAGE_SIZE)
      throw new WasmLimitException("WASM memory budget exhausted");
    return Math.max(
        1, Math.min(limits.maximumPages(), MAX_BYTES / Memory.PAGE_SIZE) * Memory.PAGE_SIZE);
  }

  MemoryLimits limits() {
    return limits;
  }

  public int pages() {
    return pages;
  }

  public int maximumPages() {
    return limits.maximumPages();
  }

  public synchronized int grow(int delta) {
    int previous = pages;
    if (delta < 0
        || delta > Math.min(maximumPages(), storage.capacity() / Memory.PAGE_SIZE) - previous)
      return -1;
    if (delta == 0) return previous;
    int bytes = delta * Memory.PAGE_SIZE;
    if (!charge.grow(bytes)) return -1;
    boolean committed = false;
    try {
      committed = grow0(this, storage, (previous + delta) * Memory.PAGE_SIZE);
      if (!committed) return -1;
      pages = previous + delta;
      return previous;
    } finally {
      if (!committed) charge.rollback(bytes);
    }
  }

  Pin pin() {
    Pin pin = new Pin(storage);
    retain0(this, storage);
    return pin;
  }

  ByteBuffer storage() {
    return storage;
  }

  ByteBuffer view() {
    ByteBuffer result = storage.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    result.limit(pages() * Memory.PAGE_SIZE);
    return result.slice().order(ByteOrder.LITTLE_ENDIAN);
  }

  /** A fixed-length SharedArrayBuffer view for a QuickJS host binding. */
  public QuickJS.SharedBuffer buffer() {
    return new QuickJS.SharedBuffer(this);
  }

  long atomic(int address, int width, int operation, long value, long replacement) {
    check(address, width);
    return atomic0(this, storage, address, width, operation, value, replacement);
  }

  int waitOn(int address, int width, long expected, long timeoutNanos, Runnable check) {
    check(address, width);
    check.run();
    return wait0(this, storage, address, width, expected, timeoutNanos, check);
  }

  int notifyWaiters(int address, int count) {
    check(address, 4);
    return notify0(this, storage, address, count);
  }

  void fence() {
    fence0();
  }

  private void check(int address, int width) {
    int size = pages() * Memory.PAGE_SIZE;
    if (address < 0 || address > size || width > size - address)
      throw new WasmRuntimeException("out of bounds memory access");
    if ((address & (width - 1)) != 0) throw new WasmRuntimeException("unaligned atomic");
  }

  private static native int abi0();

  private static native ByteBuffer allocate0(
      int capacity, int size, WasmMemoryBudget.Charge charge);

  private static native boolean grow0(SharedMemory owner, ByteBuffer storage, int size);

  private static native void retain0(SharedMemory owner, ByteBuffer storage);

  private static native void release0(ByteBuffer storage);

  private static native long atomic0(
      SharedMemory owner,
      ByteBuffer storage,
      int address,
      int width,
      int operation,
      long value,
      long replacement);

  private static native int wait0(
      SharedMemory owner,
      ByteBuffer storage,
      int address,
      int width,
      long expected,
      long timeoutNanos,
      Runnable check);

  private static native int notify0(SharedMemory owner, ByteBuffer storage, int address, int count);

  private static native void fence0();
}
