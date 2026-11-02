package com.fongmi.quickjs;

import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.runtime.WasmRuntimeException;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;

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
  private final AtomicInteger pages;

  private static final class Native {

    static {
      try {
        if (abi0() != 1) throw new LinkageError("Unsupported shared-memory native ABI");
      } catch (UnsatisfiedLinkError missing) {
        System.loadLibrary("quickjs-wrapper");
        if (abi0() != 1) throw new LinkageError("Unsupported shared-memory native ABI");
      }
    }

    static void ensure() {}
  }

  SharedMemory(MemoryLimits limits, ByteBuffer storage) {
    Native.ensure();
    this.limits = limits;
    this.storage = storage.order(ByteOrder.LITTLE_ENDIAN);
    pages = new AtomicInteger(limits.initialPages());
  }

  /** Allocates bounded, stable storage. Runtime allocations use the same per-store budgets. */
  public static SharedMemory allocate(int initialPages, int maximumPages) {
    MemoryLimits limits = new MemoryLimits(initialPages, maximumPages, true);
    int capacity = capacity(limits);
    Native.ensure();
    return new SharedMemory(limits, ByteBuffer.allocateDirect(capacity));
  }

  static int capacity(MemoryLimits limits) {
    if (limits.initialPages() > MAX_BYTES / Memory.PAGE_SIZE)
      throw new WasmLimitException("WASM memory budget exhausted");
    return Math.max(
        1, Math.min(limits.maximumPages(), MAX_BYTES / Memory.PAGE_SIZE) * Memory.PAGE_SIZE);
  }

  static void requireNative() {
    Native.ensure();
  }

  MemoryLimits limits() {
    return limits;
  }

  public int pages() {
    return pages.get();
  }

  public int maximumPages() {
    return limits.maximumPages();
  }

  public int grow(int delta) {
    while (true) {
      int previous = pages.get();
      if (delta < 0
          || delta > Math.min(maximumPages(), storage.capacity() / Memory.PAGE_SIZE) - previous)
        return -1;
      if (pages.compareAndSet(previous, previous + delta)) return previous;
    }
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
    return new QuickJS.SharedBuffer(view());
  }

  long atomic(int address, int width, int operation, long value, long replacement) {
    check(address, width);
    return atomic0(storage, address, width, operation, value, replacement);
  }

  int waitOn(int address, int width, long expected, long timeoutNanos, Runnable check) {
    check(address, width);
    check.run();
    return wait0(storage, address, width, expected, timeoutNanos, check);
  }

  int notifyWaiters(int address, int count) {
    check(address, 4);
    return notify0(storage, address, count);
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

  private static native long atomic0(
      ByteBuffer storage, int address, int width, int operation, long value, long replacement);

  private static native int wait0(
      ByteBuffer storage, int address, int width, long expected, long timeoutNanos, Runnable check);

  private static native int notify0(ByteBuffer storage, int address, int count);

  private static native void fence0();
}
