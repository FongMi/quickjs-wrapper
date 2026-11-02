package com.fongmi.quickjs;

final class WasmSharedMemory extends WasmMemory {

  private final SharedMemory memory;
  private final Runnable check;

  WasmSharedMemory(SharedMemory memory, Allocator allocator) {
    super(memory.limits(), allocator, memory);
    this.memory = memory;
    check = allocator::check;
  }

  @Override
  public Object lock(int address) {
    throw new UnsupportedOperationException("Shared memory requires native atomics");
  }

  @Override
  public int waitOn(int address, int expected, long timeout) {
    return memory.waitOn(address, 4, expected, timeout, check);
  }

  @Override
  public int waitOn(int address, long expected, long timeout) {
    return memory.waitOn(address, 8, expected, timeout, check);
  }

  @Override
  public int notify(int address, int count) {
    return memory.notifyWaiters(address, count);
  }

  @Override
  public void atomicFence() {
    memory.fence();
  }

  @Override
  public int atomicReadInt(int addr) {
    return (int) memory.atomic(addr, 4, SharedMemory.ATOMIC_LOAD, 0, 0);
  }

  @Override
  public long atomicReadLong(int addr) {
    return memory.atomic(addr, 8, SharedMemory.ATOMIC_LOAD, 0, 0);
  }

  @Override
  public short atomicReadShort(int addr) {
    return (short) memory.atomic(addr, 2, SharedMemory.ATOMIC_LOAD, 0, 0);
  }

  @Override
  public byte atomicReadByte(int addr) {
    return (byte) memory.atomic(addr, 1, SharedMemory.ATOMIC_LOAD, 0, 0);
  }

  @Override
  public void atomicWriteInt(int addr, int value) {
    memory.atomic(addr, 4, SharedMemory.ATOMIC_STORE, value, 0);
  }

  @Override
  public void atomicWriteLong(int addr, long value) {
    memory.atomic(addr, 8, SharedMemory.ATOMIC_STORE, value, 0);
  }

  @Override
  public void atomicWriteShort(int addr, short value) {
    memory.atomic(addr, 2, SharedMemory.ATOMIC_STORE, value, 0);
  }

  @Override
  public void atomicWriteByte(int addr, byte value) {
    memory.atomic(addr, 1, SharedMemory.ATOMIC_STORE, value, 0);
  }

  @Override
  public int atomicAddInt(int addr, int delta) {
    return (int) memory.atomic(addr, 4, SharedMemory.ATOMIC_ADD, delta, 0);
  }

  @Override
  public int atomicAndInt(int addr, int mask) {
    return (int) memory.atomic(addr, 4, SharedMemory.ATOMIC_AND, mask, 0);
  }

  @Override
  public int atomicOrInt(int addr, int mask) {
    return (int) memory.atomic(addr, 4, SharedMemory.ATOMIC_OR, mask, 0);
  }

  @Override
  public int atomicXorInt(int addr, int mask) {
    return (int) memory.atomic(addr, 4, SharedMemory.ATOMIC_XOR, mask, 0);
  }

  @Override
  public int atomicXchgInt(int addr, int value) {
    return (int) memory.atomic(addr, 4, SharedMemory.ATOMIC_EXCHANGE, value, 0);
  }

  @Override
  public int atomicCmpxchgInt(int addr, int expected, int replacement) {
    return (int)
        memory.atomic(addr, 4, SharedMemory.ATOMIC_COMPARE_EXCHANGE, expected, replacement);
  }

  @Override
  public long atomicAddLong(int addr, long delta) {
    return memory.atomic(addr, 8, SharedMemory.ATOMIC_ADD, delta, 0);
  }

  @Override
  public long atomicAndLong(int addr, long mask) {
    return memory.atomic(addr, 8, SharedMemory.ATOMIC_AND, mask, 0);
  }

  @Override
  public long atomicOrLong(int addr, long mask) {
    return memory.atomic(addr, 8, SharedMemory.ATOMIC_OR, mask, 0);
  }

  @Override
  public long atomicXorLong(int addr, long mask) {
    return memory.atomic(addr, 8, SharedMemory.ATOMIC_XOR, mask, 0);
  }

  @Override
  public long atomicXchgLong(int addr, long value) {
    return memory.atomic(addr, 8, SharedMemory.ATOMIC_EXCHANGE, value, 0);
  }

  @Override
  public long atomicCmpxchgLong(int addr, long expected, long replacement) {
    return memory.atomic(addr, 8, SharedMemory.ATOMIC_COMPARE_EXCHANGE, expected, replacement);
  }

  @Override
  public short atomicAddShort(int addr, short delta) {
    return (short) memory.atomic(addr, 2, SharedMemory.ATOMIC_ADD, delta, 0);
  }

  @Override
  public short atomicAndShort(int addr, short mask) {
    return (short) memory.atomic(addr, 2, SharedMemory.ATOMIC_AND, mask, 0);
  }

  @Override
  public short atomicOrShort(int addr, short mask) {
    return (short) memory.atomic(addr, 2, SharedMemory.ATOMIC_OR, mask, 0);
  }

  @Override
  public short atomicXorShort(int addr, short mask) {
    return (short) memory.atomic(addr, 2, SharedMemory.ATOMIC_XOR, mask, 0);
  }

  @Override
  public short atomicXchgShort(int addr, short value) {
    return (short) memory.atomic(addr, 2, SharedMemory.ATOMIC_EXCHANGE, value, 0);
  }

  @Override
  public short atomicCmpxchgShort(int addr, short expected, short replacement) {
    return (short)
        memory.atomic(addr, 2, SharedMemory.ATOMIC_COMPARE_EXCHANGE, expected, replacement);
  }

  @Override
  public byte atomicAddByte(int addr, byte delta) {
    return (byte) memory.atomic(addr, 1, SharedMemory.ATOMIC_ADD, delta, 0);
  }

  @Override
  public byte atomicAndByte(int addr, byte mask) {
    return (byte) memory.atomic(addr, 1, SharedMemory.ATOMIC_AND, mask, 0);
  }

  @Override
  public byte atomicOrByte(int addr, byte mask) {
    return (byte) memory.atomic(addr, 1, SharedMemory.ATOMIC_OR, mask, 0);
  }

  @Override
  public byte atomicXorByte(int addr, byte mask) {
    return (byte) memory.atomic(addr, 1, SharedMemory.ATOMIC_XOR, mask, 0);
  }

  @Override
  public byte atomicXchgByte(int addr, byte value) {
    return (byte) memory.atomic(addr, 1, SharedMemory.ATOMIC_EXCHANGE, value, 0);
  }

  @Override
  public byte atomicCmpxchgByte(int addr, byte expected, byte replacement) {
    return (byte)
        memory.atomic(addr, 1, SharedMemory.ATOMIC_COMPARE_EXCHANGE, expected, replacement);
  }
}
