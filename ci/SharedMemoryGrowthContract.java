package com.fongmi.quickjs;

import com.dylibso.chicory.wasm.types.MemoryLimits;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class SharedMemoryGrowthContract {

  private static final int PAGE = 65536;
  private static final int MAX = 64 * 1024 * 1024;

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static long resource(QuickJS observer, String name) throws Exception {
    String json = ((QuickJS.Json) observer.reference("resources").get(5, TimeUnit.SECONDS)).text();
    var match = java.util.regex.Pattern.compile("\\\"" + name + "\\\":(\\d+)").matcher(json);
    if (!match.find()) throw new AssertionError(json);
    return Long.parseLong(match.group(1));
  }

  private static void released(QuickJS observer, long roots) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (resource(observer, "sharedAllocations") != 0
        || resource(observer, "jniGlobalRefs") != roots) {
      check(System.nanoTime() < deadline, "Shared allocation or JNI root leaked");
      System.gc();
      Thread.sleep(10);
    }
    check(resource(observer, "sharedReservedBytes") == 0, "Reserved address space released");
    check(resource(observer, "sharedCommittedBytes") == 0, "Committed pages released");
  }

  private static void bind(QuickJS runtime, String name, Object value) throws Exception {
    try (var global = (QuickJS.Reference) runtime.reference("global").get(5, TimeUnit.SECONDS)) {
      runtime.reference("set", global, name, value).get(5, TimeUnit.SECONDS);
    }
  }

  private static void sparseQuota(QuickJS observer) throws Exception {
    try (WasmRuntime runtime = new WasmRuntime();
        var first = runtime.sharedMemory(1, 1024);
        var second = runtime.sharedMemory(1, 1024);
        var third = runtime.sharedMemory(1, 1024)) {
      check(resource(observer, "sharedReservedBytes") == 3L * MAX, "Three maximum reservations");
      check(
          resource(observer, "sharedCommittedBytes") == 3L * PAGE, "Only initial pages committed");
      var old = first.sharedMemory().buffer();
      old.use(bytes -> bytes.putInt(0, 42));
      check(
          first.grow(1023) == 1 && second.grow(1022) == 1, "Growth fills the shared store budget");
      long committed = resource(observer, "sharedCommittedBytes");
      check(committed == 2L * MAX, "Growth charges actual pages");
      check(third.grow(1) == -1 && second.grow(1) == -1, "Quota rejects further growth");
      check(third.pages() == 1 && second.pages() == 1023, "Rejected growth preserves page counts");
      check(
          resource(observer, "sharedCommittedBytes") == committed,
          "Rejected growth commits nothing");
      old.use(
          bytes ->
              check(bytes.remaining() == PAGE && bytes.getInt(0) == 42, "Old view remains stable"));
      first
          .sharedMemory()
          .buffer()
          .use(
              bytes -> {
                check(bytes.order() == ByteOrder.LITTLE_ENDIAN, "WASM byte order");
                check(
                    bytes.capacity() == MAX && bytes.getInt(0) == 42,
                    "Full grown range aliases old view");
                for (int offset : new int[] {PAGE, 2 * PAGE - 1, MAX - 1})
                  check(bytes.get(offset) == 0, "New committed pages are zeroed");
              });
      try {
        runtime.memory(1, 1);
        throw new AssertionError("Unshared allocation bypassed shared store quota");
      } catch (WasmRuntime.LimitException expected) {
      }
    }
  }

  private static void mixedQuota() throws Exception {
    try (WasmRuntime runtime = new WasmRuntime();
        var ordinary = runtime.memory(1, 1);
        var first = runtime.sharedMemory(1, 1024);
        var second = runtime.sharedMemory(1, 1024)) {
      ordinary.write(0, new byte[] {42});
      check(first.grow(1023) == 1, "Mixed budget accepts shared growth");
      check(second.grow(1023) == -1, "Unshared allocation remains charged");
      check(second.grow(1022) == 1, "Rejected growth rolls back its charge");
      check(second.grow(1) == -1, "Mixed budget remains bounded");
      check(ordinary.read(0, 1)[0] == 42, "Unshared allocation stays live");
    }
  }

  private static void concurrentGrowth() throws Exception {
    SharedMemory shared = SharedMemory.allocate(1, 128);
    var old = shared.buffer();
    old.use(bytes -> bytes.putInt(0, 42));
    var start = new CountDownLatch(1);
    var ready = new CountDownLatch(3);
    var executor = Executors.newFixedThreadPool(2);
    try (WasmRuntime first = new WasmRuntime();
        WasmRuntime second = new WasmRuntime();
        var a = first.memory(shared);
        var b = second.memory(shared);
        QuickJS js = new QuickJS();
        Wasm wasm = new Wasm(js);
        var memory = wasm.memory(shared).get(5, TimeUnit.SECONDS)) {
      bind(js, "memory", memory);
      js.bind(
              "begin",
              args -> {
                ready.countDown();
                check(start.await(5, TimeUnit.SECONDS), "JS growth start gate");
                return null;
              })
          .get(5, TimeUnit.SECONDS);
      CompletableFuture<Object> jsGrowth =
          js.evaluate("begin(); Array.from({length:20},()=>memory.grow(1))");
      var futures = new java.util.ArrayList<java.util.concurrent.Future<int[]>>();
      for (var target : new WasmRuntime.Memory[] {a, b}) {
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  check(start.await(5, TimeUnit.SECONDS), "Java growth start gate");
                  int[] previous = new int[20];
                  for (int i = 0; i < previous.length; i++) previous[i] = target.grow(1);
                  return previous;
                }));
      }
      check(ready.await(5, TimeUnit.SECONDS), "All three runtimes ready");
      start.countDown();
      Set<Integer> previous = new HashSet<>();
      for (var future : futures)
        for (int page : future.get(5, TimeUnit.SECONDS))
          check(previous.add(page), "Unique Java growth result");
      for (Object page : (Object[]) jsGrowth.get(5, TimeUnit.SECONDS))
        check(previous.add(((Number) page).intValue()), "Unique JS growth result");
      check(previous.size() == 60 && shared.pages() == 61, "No lost concurrent growth");
      for (int page = 1; page <= 60; page++)
        check(previous.contains(page), "All old page counts returned");
      check(a.read(0, 4)[0] == 42 && b.read(PAGE, 1)[0] == 0, "Java reads stable grown backing");
      b.write(PAGE, new byte[] {7});
      shared.buffer().use(bytes -> check(bytes.get(PAGE) == 7, "Java writes alias grown backing"));
      old.use(
          bytes ->
              check(
                  bytes.remaining() == PAGE && bytes.getInt(0) == 42, "Old Java view stays fixed"));
    } finally {
      start.countDown();
      executor.shutdownNow();
      check(executor.awaitTermination(5, TimeUnit.SECONDS), "Growth threads terminated");
    }
  }

  private static WeakReference<SharedMemory> retainedBuffer(WasmMemoryBudget budget, QuickJS js)
      throws Exception {
    SharedMemory memory = SharedMemory.allocate(new MemoryLimits(1024, 1024, true), budget);
    memory.buffer().use(bytes -> bytes.putInt(0, 42));
    bind(js, "kept", memory.buffer());
    return new WeakReference<>(memory);
  }

  private static void charged(WasmMemoryBudget budget) {
    try {
      budget.charge(1);
      throw new AssertionError("Live native backing refunded its charge");
    } catch (WasmLimitException expected) {
    }
  }

  private static void retainedQuota(QuickJS observer, long roots) throws Exception {
    WasmMemoryBudget budget = new WasmMemoryBudget();
    var other = budget.charge(MAX);
    try {
      QuickJS.BufferLease lease;
      try (QuickJS js = new QuickJS()) {
        WeakReference<SharedMemory> owner = retainedBuffer(budget, js);
        try (var reference =
            (QuickJS.Reference)
                js.reference("evaluateReference", "kept", "<retained>", false)
                    .get(5, TimeUnit.SECONDS)) {
          lease = js.leaseBuffer(reference).get(5, TimeUnit.SECONDS);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (owner.get() != null) {
          check(System.nanoTime() < deadline, "Java shared owner became collectable");
          System.gc();
          Thread.sleep(10);
        }
        charged(budget);
        check(
            ((Number) js.evaluate("Atomics.load(new Int32Array(kept),0)").get(5, TimeUnit.SECONDS))
                    .intValue()
                == 42,
            "SAB survives Java owner GC");
      }
      try (lease) {
        charged(budget);
        lease
            .use(
                bytes ->
                    check(
                        bytes.order(ByteOrder.LITTLE_ENDIAN).getInt(0) == 42,
                        "Lease outlives source runtime"))
            .get(5, TimeUnit.SECONDS);
      }
      released(observer, roots);
      // The charge token may reach its reference queue after native release.
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (true) {
        try {
          var replacement = budget.charge(MAX);
          replacement.cancel();
          break;
        } catch (WasmLimitException pendingGc) {
          check(System.nanoTime() < deadline, "Last native owner refunds committed quota");
          System.gc();
          Thread.sleep(10);
        }
      }
    } finally {
      other.cancel();
    }
  }

  private static void borrowedRange() throws Exception {
    var view = SharedMemory.allocate(1, 4).buffer();
    var failure = new IllegalStateException("borrow failed");
    try {
      view.use(
          bytes -> {
            System.gc();
            bytes.putInt(0, 42);
            throw failure;
          });
      throw new AssertionError("Borrow swallowed callback failure");
    } catch (IllegalStateException error) {
      check(error == failure, "Borrow retains original exception");
    }
    view.use(
        bytes -> check(bytes.getInt(0) == 42 && bytes.position() == 0, "Borrow remains reusable"));
    SharedMemory empty = SharedMemory.allocate(0, 0);
    empty.buffer().use(bytes -> check(bytes.remaining() == 0, "Zero-page borrow"));
    check(empty.grow(0) == 0 && empty.grow(1) == -1, "Zero-page growth bounds");
    SharedMemory memory = SharedMemory.allocate(1, 4);
    var atomic =
        SharedMemory.class.getDeclaredMethod(
            "atomic0",
            SharedMemory.class,
            ByteBuffer.class,
            int.class,
            int.class,
            int.class,
            long.class,
            long.class);
    atomic.setAccessible(true);
    for (int address : new int[] {1, PAGE}) {
      try {
        atomic.invoke(null, memory, memory.storage(), address, 4, SharedMemory.ATOMIC_LOAD, 0L, 0L);
        throw new AssertionError("Native atomic accepted unaligned or uncommitted range");
      } catch (InvocationTargetException error) {
        check(error.getCause() instanceof IllegalArgumentException, "Native range rejection");
      }
    }
  }

  public static void main(String[] args) throws Exception {
    try (QuickJS observer = new QuickJS()) {
      long roots = resource(observer, "jniGlobalRefs");
      sparseQuota(observer);
      released(observer, roots);
      mixedQuota();
      released(observer, roots);
      concurrentGrowth();
      released(observer, roots);
      borrowedRange();
      released(observer, roots);
      retainedQuota(observer, roots);
      released(observer, roots);
    }
    System.out.println(
        "PASS: sparse shared commitment, mixed quotas, concurrent growth, scoped borrowing and"
            + " last-owner GC");
  }
}
