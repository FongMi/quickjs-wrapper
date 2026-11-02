package com.fongmi.quickjs;

import com.dylibso.chicory.runtime.ConstantEvaluators;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.runtime.WasmRuntimeException;
import com.dylibso.chicory.wasm.types.ActiveDataSegment;
import com.dylibso.chicory.wasm.types.DataSegment;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import com.dylibso.chicory.wasm.types.PassiveDataSegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@SuppressWarnings("removal")
class WasmMemory implements Memory {

  interface Allocator {
    ByteBuffer allocate(long size);

    void changed(WasmMemory memory);

    void check();
  }

  private final MemoryLimits limits;
  private final Allocator allocator;
  private final SharedMemory shared;
  private ByteBuffer buffer;
  private DataSegment[] segments = new DataSegment[0];

  WasmMemory(MemoryLimits limits, Allocator allocator) {
    this(limits, allocator, null);
  }

  WasmMemory(MemoryLimits limits, Allocator allocator, SharedMemory shared) {
    this.limits = limits;
    this.allocator = allocator;
    this.shared = shared;
    buffer =
        shared == null
            ? allocator
                .allocate((long) limits.initialPages() * PAGE_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
            : shared.storage();
  }

  SharedMemory backing() {
    return shared;
  }

  private int byteSize() {
    return pages() * PAGE_SIZE;
  }

  ByteBuffer buffer() {
    return shared == null ? buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN) : shared.view();
  }

  public int pages() {
    return shared == null ? buffer.capacity() / PAGE_SIZE : shared.pages();
  }

  public int initialPages() {
    return limits.initialPages();
  }

  public int maximumPages() {
    return limits.maximumPages();
  }

  public boolean shared() {
    return shared != null;
  }

  public int grow(int delta) {
    if (shared != null) {
      int previous = shared.grow(delta);
      if (previous >= 0) allocator.changed(this);
      return previous;
    }
    int previous = pages();
    if (delta < 0 || delta > maximumPages() - previous) return -1;
    if (delta == 0) {
      allocator.changed(this);
      return previous;
    }
    ByteBuffer next;
    try {
      next =
          allocator.allocate((long) (previous + delta) * PAGE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
    } catch (WasmLimitException error) {
      return -1;
    }
    next.put(buffer.duplicate()).clear();
    buffer = next;
    allocator.changed(this);
    return previous;
  }

  private static void bounds(int offset, int length, int size) {
    if (offset < 0 || length < 0 || offset > size || length > size - offset)
      throw new WasmRuntimeException("out of bounds memory access");
  }

  private void bounds(int offset, int length) {
    bounds(offset, length, byteSize());
  }

  public void initialize(Instance instance, DataSegment[] values) {
    initialize(instance, values, 0);
  }

  public void initialize(Instance instance, DataSegment[] values, int memoryIndex) {
    segments = values == null ? new DataSegment[0] : values.clone();
    for (int i = 0; i < segments.length; i++) {
      DataSegment segment = segments[i];
      if (segment instanceof ActiveDataSegment active && active.index() == memoryIndex) {
        int offset =
            (int) ConstantEvaluators.computeConstantValue(instance, active.offsetInstructions())[0];
        write(offset, active.data());
        drop(i);
      }
    }
  }

  public void initPassiveSegment(int id, int dest, int offset, int size) {
    write(dest, segments[id].data(), offset, size);
  }

  public void drop(int id) {
    segments[id] = new PassiveDataSegment(new byte[0]);
  }

  public void write(int addr, byte[] data, int offset, int size) {
    bounds(addr, size);
    bounds(offset, size, data.length);
    ByteBuffer view = buffer.duplicate();
    view.position(addr);
    view.put(data, offset, size);
  }

  public byte[] readBytes(int addr, int length) {
    bounds(addr, length);
    byte[] result = new byte[length];
    ByteBuffer view = buffer.duplicate();
    view.position(addr);
    view.get(result);
    return result;
  }

  public void copy(int dest, int src, int size) {
    bounds(dest, size);
    bounds(src, size);
    if (dest > src && dest - src < size)
      for (int i = size - 1; i >= 0; i--) {
        if ((i & 16383) == 0) allocator.check();
        buffer.put(dest + i, buffer.get(src + i));
      }
    else
      for (int i = 0; i < size; i++) {
        if ((i & 16383) == 0) allocator.check();
        buffer.put(dest + i, buffer.get(src + i));
      }
  }

  public void zero() {
    fill((byte) 0, 0, byteSize());
  }

  public void fill(byte value, int start, int end) {
    bounds(start, end - start);
    for (int i = start; i < end; i++) {
      if ((i & 16383) == 0) allocator.check();
      buffer.put(i, value);
    }
  }

  public Object lock(int address) {
    return this;
  }

  public int waitOn(int address, int expected, long timeout) {
    bounds(address, 4);
    throw new WasmRuntimeException("atomic wait requires shared memory");
  }

  public int waitOn(int address, long expected, long timeout) {
    bounds(address, 8);
    throw new WasmRuntimeException("atomic wait requires shared memory");
  }

  public int notify(int address, int count) {
    bounds(address, 4);
    if ((address & 3) != 0) throw new WasmRuntimeException("unaligned atomic");
    return 0;
  }

  public void writeByte(int addr, byte value) {
    bounds(addr, 1);
    buffer.put(addr, value);
  }

  public void writeShort(int addr, short value) {
    bounds(addr, 2);
    buffer.putShort(addr, value);
  }

  public void writeI32(int addr, int value) {
    bounds(addr, 4);
    buffer.putInt(addr, value);
  }

  public void writeLong(int addr, long value) {
    bounds(addr, 8);
    buffer.putLong(addr, value);
  }

  public void writeF32(int addr, float value) {
    bounds(addr, 4);
    buffer.putFloat(addr, value);
  }

  public void writeF64(int addr, double value) {
    bounds(addr, 8);
    buffer.putDouble(addr, value);
  }

  public byte read(int addr) {
    bounds(addr, 1);
    return buffer.get(addr);
  }

  public short readShort(int addr) {
    bounds(addr, 2);
    return buffer.getShort(addr);
  }

  public int readInt(int addr) {
    bounds(addr, 4);
    return buffer.getInt(addr);
  }

  public long readLong(int addr) {
    bounds(addr, 8);
    return buffer.getLong(addr);
  }

  public float readFloat(int addr) {
    bounds(addr, 4);
    return buffer.getFloat(addr);
  }

  public double readDouble(int addr) {
    bounds(addr, 8);
    return buffer.getDouble(addr);
  }

  public long readU16(int addr) {
    return readShort(addr) & 0xffff;
  }

  public long readF32(int addr) {
    return Float.floatToRawIntBits(readFloat(addr));
  }

  public long readF64(int addr) {
    return Double.doubleToRawLongBits(readDouble(addr));
  }
}
