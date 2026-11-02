package com.fongmi.quickjs;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

final class WasmMemoryBudget {

  private static final int MAX_MEMORY = 64 * 1024 * 1024;
  private static final int MAX_TOTAL_MEMORY = 128 * 1024 * 1024;
  private final ReferenceQueue<Object> queue = new ReferenceQueue<>();
  private final Map<PhantomReference<?>, Account> allocations = new HashMap<>();
  private int bytes;

  private static final class Account {

    int bytes;

    Account(int bytes) {
      this.bytes = bytes;
    }
  }

  static final class Charge {

    private final WasmMemoryBudget budget;
    private final Account account;
    private final PhantomReference<Charge> reference;

    private Charge(WasmMemoryBudget budget, Account account) {
      this.budget = budget;
      this.account = account;
      reference = new PhantomReference<>(this, budget.queue);
    }

    boolean grow(int bytes) {
      return budget.grow(account, bytes);
    }

    void rollback(int bytes) {
      budget.release(account, bytes);
    }

    void cancel() {
      synchronized (budget) {
        budget.allocations.remove(reference);
        budget.release(account, account.bytes);
      }
    }
  }

  private void collect() {
    for (var reference = queue.poll(); reference != null; reference = queue.poll()) {
      Account account = allocations.remove(reference);
      if (account != null) release(account, account.bytes);
      reference.clear();
    }
  }

  private synchronized Account reserve(long size) {
    collect();
    if (size < 0 || size > MAX_MEMORY || size > MAX_TOTAL_MEMORY - bytes)
      throw new WasmLimitException("WASM memory budget exhausted");
    Account account = new Account((int) size);
    bytes += account.bytes;
    return account;
  }

  private synchronized boolean grow(Account account, int delta) {
    collect();
    if (delta < 0 || delta > MAX_MEMORY - account.bytes || delta > MAX_TOTAL_MEMORY - bytes)
      return false;
    account.bytes += delta;
    bytes += delta;
    return true;
  }

  private synchronized void release(Account account, int size) {
    account.bytes -= size;
    bytes -= size;
  }

  private synchronized void discard(Account account) {
    release(account, account.bytes);
  }

  ByteBuffer allocate(long size) {
    Account account = reserve(size);
    try {
      ByteBuffer buffer = ByteBuffer.allocateDirect((int) size);
      synchronized (this) {
        allocations.put(new PhantomReference<>(buffer, queue), account);
      }
      return buffer;
    } catch (OutOfMemoryError error) {
      discard(account);
      throw new WasmLimitException("WASM memory allocation failed");
    }
  }

  synchronized Charge charge(int size) {
    Account account = reserve(size);
    try {
      Charge charge = new Charge(this, account);
      allocations.put(charge.reference, account);
      return charge;
    } catch (OutOfMemoryError error) {
      discard(account);
      throw new WasmLimitException("WASM memory allocation failed");
    }
  }
}
