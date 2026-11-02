package com.fongmi.quickjs;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class SharedMemoryContract {

  private static byte[] binary;

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private static Object js(QuickJS runtime, String source) throws Exception {
    return runtime.evaluate(source).get(5, TimeUnit.SECONDS);
  }

  private static void bind(QuickJS runtime, String name, Object value) throws Exception {
    try (var global = (QuickJS.Reference) runtime.reference("global").get(5, TimeUnit.SECONDS)) {
      runtime.reference("set", global, name, value).get(5, TimeUnit.SECONDS);
    }
  }

  private static CompletableFuture<Object[]> call(
      WasmRuntime.Instance instance, String name, Object... args) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return instance.call(name, args);
          } catch (Exception error) {
            throw new CompletionException(error);
          }
        });
  }

  private static void notifyUntil(WasmRuntime.Instance instance, CompletableFuture<?> waiting)
      throws Exception {
    notifyUntil(instance, "notify", waiting);
  }

  private static CompletableFuture<Integer> waitQueued(
      SharedMemory memory, java.util.concurrent.CountDownLatch queued) {
    return waitQueued(memory, queued, () -> {});
  }

  private static CompletableFuture<Integer> waitQueued(
      SharedMemory memory, java.util.concurrent.CountDownLatch queued, Runnable check) {
    var checks = new java.util.concurrent.atomic.AtomicInteger();
    return CompletableFuture.supplyAsync(
        () ->
            memory.waitOn(
                0,
                4,
                0,
                -1,
                () -> {
                  check.run();
                  // The first check is preflight; native checks run after enqueueing.
                  if (checks.incrementAndGet() == 2) queued.countDown();
                }));
  }

  private static void notifyUntil(
      WasmRuntime.Instance instance, String name, CompletableFuture<?> waiting) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (((Number) instance.call(name, 1)[0]).intValue() == 1) return;
      if (waiting.isDone()) throw new AssertionError("Wait completed before notification");
      Thread.sleep(1);
    }
    throw new AssertionError("Waiter never entered the shared native queue");
  }

  private static long resource(QuickJS observer, String name) throws Exception {
    String json = ((QuickJS.Json) observer.reference("resources").get(5, TimeUnit.SECONDS)).text();
    var match = java.util.regex.Pattern.compile("\\\"" + name + "\\\":(\\d+)").matcher(json);
    if (!match.find()) throw new AssertionError(json);
    return Long.parseLong(match.group(1));
  }

  public static void main(String[] args) throws Exception {
    binary = Files.readAllBytes(Path.of(args[0]));
    try (QuickJS observer = new QuickJS()) {
      long roots = resource(observer, "jniGlobalRefs");
      SharedMemory shared = SharedMemory.allocate(1, 4);
      try (WasmRuntime java = new WasmRuntime();
          var module = java.compile(binary);
          var memory = java.memory(shared);
          var instance = java.instantiate(module, Map.of("env", Map.of("memory", memory)));
          QuickJS js = new QuickJS();
          Wasm wasm = new Wasm(js)) {
        js.bind("fixture", values -> binary).get(5, TimeUnit.SECONDS);
        try (var imported = wasm.memory(shared).get(5, TimeUnit.SECONDS)) {
          bind(js, "memory", imported);
        }
        js(
            js,
            "globalThis.api = new WebAssembly.Instance(new"
                + " WebAssembly.Module(fixture()),{env:{memory}}).exports; globalThis.oldBuffer ="
                + " memory.buffer; true");
        check(
            Boolean.TRUE.equals(
                js(
                    js,
                    "oldBuffer instanceof SharedArrayBuffer && Object.isFrozen(oldBuffer) &&"
                        + " memory.buffer === oldBuffer")),
            "SharedArrayBuffer type, identity and integrity");
        instance.call("store", 42);
        check(
            ((Number) js(js, "Atomics.load(new Int32Array(oldBuffer),0)")).intValue() == 42,
            "WASM writes alias JS atomics");
        js(js, "Atomics.store(new Int32Array(oldBuffer),0,0)");
        CompletableFuture<Object> additions =
            js.evaluate(
                "for(let i=0;i<20000;i++) Atomics.add(new Int32Array(oldBuffer),0,1); true");
        for (int i = 0; i < 20000; i++) instance.call("add", 1);
        additions.get(10, TimeUnit.SECONDS);
        check(instance.call("load")[0].equals(40000), "Concurrent JS/WASM RMW loses no updates");
        check(
            instance.call("cmp", 40000, 42)[0].equals(40000) && instance.call("load")[0].equals(42),
            "Compare exchange");
        check(instance.call("widths")[0].equals(0), "Atomic byte/short/u32 wraparound");
        instance.call("store64", Long.MIN_VALUE);
        check(
            Boolean.TRUE.equals(
                js(js, "Atomics.load(new BigInt64Array(oldBuffer),1) === -(1n<<63n)")),
            "i64 atomic bits");
        check(instance.call("wait", 0, 0L)[0].equals(1), "Wait compares before timing out");
        instance.call("store", 0);
        check(instance.call("wait", 0, 0L)[0].equals(2), "Zero timeout");
        CompletableFuture<Object[]> wasmWait = call(instance, "wait", 0, -1L);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int notified = 0;
        while (notified == 0 && System.nanoTime() < deadline) {
          notified = ((Number) js(js, "Atomics.notify(new Int32Array(oldBuffer),0,1)")).intValue();
          if (notified == 0) Thread.sleep(1);
        }
        check(
            notified == 1 && wasmWait.get(5, TimeUnit.SECONDS)[0].equals(0),
            "JS notify wakes WASM wait");
        CompletableFuture<Object> jsWait =
            js.evaluate("Atomics.wait(new Int32Array(oldBuffer),0,0)");
        notifyUntil(instance, jsWait);
        check("ok".equals(jsWait.get(5, TimeUnit.SECONDS)), "WASM notify wakes JS wait");
        CompletableFuture<Object> wait64 = js.evaluate("api.wait64(-(1n<<63n),-1n)");
        notifyUntil(instance, "notify64", wait64);
        check(((Number) wait64.get(5, TimeUnit.SECONDS)).intValue() == 0, "64-bit WASM wait");
        var queued = new java.util.concurrent.CountDownLatch(1);
        CompletableFuture<Integer> growingWait = waitQueued(shared, queued);
        check(queued.await(5, TimeUnit.SECONDS), "Waiter queued before shared growth");
        check(instance.call("grow", 1)[0].equals(1), "WASM shared growth");
        notifyUntil(instance, growingWait);
        check(growingWait.get(5, TimeUnit.SECONDS) == 0, "Shared growth preserves the wait queue");
        var cancelQueued = new java.util.concurrent.CountDownLatch(1);
        var cancelRequested = new java.util.concurrent.atomic.AtomicBoolean();
        var cancellation = new java.util.concurrent.CancellationException("Queued wait canceled");
        CompletableFuture<Integer> nativeCanceled =
            waitQueued(
                shared,
                cancelQueued,
                () -> {
                  if (cancelRequested.get()) throw cancellation;
                });
        check(cancelQueued.await(5, TimeUnit.SECONDS), "Cancelable native waiter queued");
        cancelRequested.set(true);
        try {
          nativeCanceled.get(5, TimeUnit.SECONDS);
          throw new AssertionError("Queued native wait ignored cancellation");
        } catch (ExecutionException error) {
          check(error.getCause() == cancellation, "Queued wait preserves the original exception");
        }
        check(instance.call("notify", 1)[0].equals(0), "Queued cancellation removes the waiter");
        instance.call("fence");
        js(js, "api.fence()");
        check(
            Boolean.TRUE.equals(
                js(
                    js,
                    "oldBuffer.byteLength===65536 && memory.buffer.byteLength===131072 &&"
                        + " memory.buffer!==oldBuffer && Atomics.load(new"
                        + " Int32Array(memory.buffer),0)===0")),
            "Old views remain live after remote growth");
        try {
          java.instantiate(module, Map.of("env", Map.of("memory", java.memory(1, 4))));
          throw new AssertionError("Unshared import accepted");
        } catch (WasmRuntime.LinkException expected) {
        }
        for (String name : new String[] {"misaligned", "bounds"}) {
          try {
            instance.call(name);
            throw new AssertionError("Missing atomic trap " + name);
          } catch (WasmRuntime.TrapException expected) {
          }
        }
        try {
          instance.callTimed(40, "wait", 0, -1L);
          throw new AssertionError("WASM wait missed deadline");
        } catch (TimeoutException expected) {
        }
        System.gc();
        check(instance.call("add", 1)[0].equals(0), "WASM recovery after deadline and GC");
        try {
          js.evaluateTimed(40, "Atomics.wait(new Int32Array(memory.buffer),0,1)", "<shared-wait>")
              .get(5, TimeUnit.SECONDS);
          throw new AssertionError("JS wait missed deadline");
        } catch (ExecutionException error) {
          check(error.getCause() instanceof TimeoutException, "JS timeout retains type");
        }
        js.reference("gc").get(5, TimeUnit.SECONDS);
        check(
            ((Number) js(js, "Atomics.add(new Int32Array(memory.buffer),0,1)")).intValue() == 1,
            "JS recovery after deadline and GC");
        try {
          js.evaluateTimed(40, "api.wait(2,-1n)", "<wasm-shared-wait>").get(5, TimeUnit.SECONDS);
          throw new AssertionError("QuickJS WASM wait missed deadline");
        } catch (ExecutionException error) {
          check(error.getCause() instanceof TimeoutException, "WASM bridge timeout retains type");
        }
        js.reference("gc").get(5, TimeUnit.SECONDS);
        check(
            ((Number) js(js, "api.add(0)")).intValue() == 2,
            "QuickJS WASM recovery after deadline and GC");
        try (var reference =
                (QuickJS.Reference)
                    js.reference("evaluateReference", "memory.buffer", "<shared-lease>", false)
                        .get(5, TimeUnit.SECONDS);
            var lease = js.leaseBuffer(reference).get(5, TimeUnit.SECONDS)) {
          js.reference("gc").get(5, TimeUnit.SECONDS);
          lease
              .use(
                  buffer ->
                      check(
                          buffer.order(ByteOrder.LITTLE_ENDIAN).getInt(0) == 2,
                          "Shared buffer lease survives GC"))
              .get(5, TimeUnit.SECONDS);
        }
        var entered = new java.util.concurrent.CountDownLatch(1);
        js.bind(
                "entered",
                values -> {
                  entered.countDown();
                  return null;
                })
            .get(5, TimeUnit.SECONDS);
        CompletableFuture<Object> canceled =
            js.evaluate("entered(); Atomics.wait(new Int32Array(memory.buffer),0,2)");
        check(entered.await(5, TimeUnit.SECONDS), "Cancelable JS waiter entered");
        check(canceled.cancel(true), "Cancel JS atomic wait");
        js(js, "void 0");
        js.reference("gc").get(5, TimeUnit.SECONDS);
        check(instance.call("notify", 1)[0].equals(0), "Canceled waiter leaves no queue entry");
        var wasmEntered = new java.util.concurrent.CountDownLatch(1);
        js.bind(
                "wasmEntered",
                values -> {
                  wasmEntered.countDown();
                  return null;
                })
            .get(5, TimeUnit.SECONDS);
        CompletableFuture<Object> wasmCanceled = js.evaluate("wasmEntered(); api.wait(2,-1n)");
        check(wasmEntered.await(5, TimeUnit.SECONDS), "Cancelable WASM waiter entered");
        check(wasmCanceled.cancel(true), "Cancel QuickJS WASM wait");
        js(js, "void 0");
        js.reference("gc").get(5, TimeUnit.SECONDS);
        check(
            instance.call("notify", 1)[0].equals(0), "Canceled WASM waiter leaves no queue entry");
        ByteBuffer bad = ByteBuffer.allocateDirect(16);
        bad.position(1);
        try {
          bind(js, "bad", new QuickJS.SharedBuffer(bad));
          throw new AssertionError("Unaligned shared base accepted");
        } catch (ExecutionException expected) {
        }
        js(
            js,
            "globalThis.lastView=memory.buffer; delete globalThis.oldBuffer; delete globalThis.api;"
                + " delete globalThis.memory; true");
        instance.close();
        memory.close();
        module.close();
        java.close();
        wasm.close();
        shared = null;
        System.gc();
        js.reference("gc").get(5, TimeUnit.SECONDS);
        check(
            ((Number) js(js, "Atomics.load(new Int32Array(lastView),0)")).intValue() == 2,
            "A retained SAB owns storage after source runtimes and WASM host close");
      }
      check(
          resource(observer, "jniGlobalRefs") == roots,
          "Shared JNI roots released after runtime close");
      check(
          resource(observer, "sharedAllocations") == 0,
          "Shared allocations released after the last owner");
    }
    System.out.println(
        "PASS: shared backing, concurrent atomics, cross-language wait/notify, growth, deadlines,"
            + " GC and ABI");
  }
}
