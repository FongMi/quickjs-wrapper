package com.fongmi.quickjs;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class MemoryBudgetContract {

  private static final int MIB = 1024 * 1024;

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private static void limited(Runnable action) {
    try {
      action.run();
      throw new AssertionError("Expected memory quota exhaustion");
    } catch (WasmLimitException expected) {
    }
  }

  public static void main(String[] args) throws Exception {
    var budget = new WasmMemoryBudget();
    var buffer = budget.allocate(MIB);
    var first = budget.charge(64 * MIB);
    var second = budget.charge(63 * MIB);
    check(!second.grow(1), "Shared and unshared allocations use the same quota");
    second.rollback(MIB);
    check(second.grow(MIB), "Failed native commitment can return its reserved charge");
    first.cancel();
    first.cancel();
    var replacement = budget.charge(64 * MIB);
    limited(() -> budget.charge(1));
    check(buffer.get(0) == 0, "Unshared backing remains live while charged");
    replacement.cancel();
    second.cancel();

    var concurrent = new WasmMemoryBudget();
    var charges = new ArrayList<WasmMemoryBudget.Charge>();
    var results = new ArrayList<Future<Boolean>>();
    var ready = new CountDownLatch(4);
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(4);
    try {
      for (int i = 0; i < 4; i++) {
        var charge = concurrent.charge(0);
        charges.add(charge);
        results.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return charge.grow(64 * MIB);
                }));
      }
      check(ready.await(5, TimeUnit.SECONDS), "Concurrent growth workers ready");
      start.countDown();
      int accepted = 0;
      for (var result : results) if (result.get(5, TimeUnit.SECONDS)) accepted++;
      check(accepted == 2, "Concurrent growth cannot overbook the aggregate quota");
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
    for (var charge : charges) charge.cancel();
    var full = concurrent.charge(64 * MIB);
    var other = concurrent.charge(64 * MIB);
    limited(() -> concurrent.charge(1));
    full.cancel();
    other.cancel();
    System.out.println(
        "PASS: mixed memory quotas, commitment rollback and concurrent growth admission");
  }
}
