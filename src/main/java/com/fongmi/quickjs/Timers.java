package com.fongmi.quickjs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Installs JavaScript timers with callbacks delivered on the runtime owner. */
public final class Timers implements AutoCloseable {

  private static final AtomicInteger NEXT_HELPER = new AtomicInteger();
  private static final String SOURCE = RuntimeScript.read("timers.js");

  private final QuickJS runtime;
  private final Consumer<Throwable> errors;
  private final Timer scheduler;
  private final Map<Integer, Timeout> timeouts = new HashMap<>();
  private final Map<CompletableFuture<Void>, TimerTask> delays = new HashMap<>();
  private final String name = "__quickjsTimers" + NEXT_HELPER.incrementAndGet();
  private int nextId;
  private int nesting;
  private boolean closed;

  public Timers(QuickJS runtime, Consumer<Throwable> errors) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.errors = Objects.requireNonNull(errors, "errors");
    scheduler = new Timer("QuickJS-timers", true);
    try {
      runtime
          .submit(
              () -> {
                install();
                return null;
              })
          .join();
    } catch (RuntimeException | Error error) {
      try {
        close();
      } catch (RuntimeException | Error failure) {
        error.addSuppressed(failure);
      }
      throw error;
    }
  }

  private void install() {
    try {
      runtime.bindReference(name + "Set", this::setTimeout).join();
      runtime.bindReference(name + "Clear", this::clearTimeout).join();
      runtime.evaluate(SOURCE.replace("$name", name), "<timers>").join();
      runtime.onClose(() -> close(false)).join();
    } catch (RuntimeException | Error error) {
      close();
      throw error;
    }
  }

  public CompletableFuture<Void> delay(long milliseconds) {
    if (milliseconds < 0) throw new IllegalArgumentException("Invalid delay duration");
    CompletableFuture<Void> result = new CompletableFuture<>();
    TimerTask task =
        new TimerTask() {

          @Override
          public void run() {
            dispatchDelay(result);
          }
        };
    synchronized (this) {
      if (closed) {
        result.cancel(false);
        return result;
      }
      delays.put(result, task);
      try {
        scheduler.schedule(task, milliseconds);
      } catch (RuntimeException | Error error) {
        delays.remove(result);
        task.cancel();
        throw error;
      }
    }
    result.whenComplete((value, error) -> releaseDelay(result));
    return result;
  }

  private void dispatchDelay(CompletableFuture<Void> result) {
    runtime
        .continueOnOwner(() -> result.complete(null), false)
        .whenComplete(
            (value, error) -> {
              if (error != null) result.completeExceptionally(error);
            });
  }

  private synchronized void releaseDelay(CompletableFuture<Void> result) {
    TimerTask task = delays.remove(result);
    if (task != null && task.cancel()) scheduler.purge();
  }

  private Object setTimeout(Object... args) {
    QuickJS.Reference retained = null;
    try {
      QuickJS.Reference callback = (QuickJS.Reference) args[0];
      int delay = ((Number) args[1]).intValue();
      boolean repeat = (Boolean) args[2];
      retained = callback;
      return schedule(callback, delay, repeat);
    } finally {
      releaseArguments(args, retained);
    }
  }

  private Object clearTimeout(Object... args) {
    try {
      cancel(((Number) args[0]).intValue());
      return null;
    } finally {
      releaseArguments(args, null);
    }
  }

  private static void releaseArguments(Object[] args, QuickJS.Reference retained) {
    for (Object value : args)
      if (value instanceof QuickJS.Reference reference && reference != retained) reference.close();
  }

  private int schedule(QuickJS.Reference callback, int delay, boolean repeat) {
    Timeout timeout = null;
    Throwable failure = null;
    synchronized (this) {
      if (!closed) {
        do {
          nextId = nextId == Integer.MAX_VALUE ? 1 : nextId + 1;
        } while (timeouts.containsKey(nextId));
        timeout = new Timeout(nextId, callback, delay, repeat, nesting + 1);
        timeouts.put(timeout.id, timeout);
        try {
          arm(timeout);
        } catch (RuntimeException | Error error) {
          timeouts.remove(timeout.id);
          failure = error;
        }
      }
    }
    if (timeout == null) {
      callback.close();
      return 0;
    }
    if (failure != null) {
      timeout.release();
      errors.accept(failure);
      return 0;
    }
    return timeout.id;
  }

  private void arm(Timeout timeout) {
    timeout.task =
        new TimerTask() {

          @Override
          public void run() {
            timeout.dispatch();
          }
        };
    scheduler.schedule(
        timeout.task, timeout.nesting > 6 ? Math.max(4, timeout.delay) : timeout.delay);
  }

  private void cancel(int id) {
    Timeout timeout;
    synchronized (this) {
      timeout = timeouts.remove(id);
    }
    if (timeout != null) timeout.release();
  }

  private synchronized boolean remove(Timeout timeout) {
    return timeouts.remove(timeout.id, timeout);
  }

  @Override
  public void close() {
    close(true);
  }

  private void close(boolean bindings) {
    ArrayList<Timeout> outstanding;
    ArrayList<CompletableFuture<Void>> waiting;
    synchronized (this) {
      if (closed) return;
      closed = true;
      scheduler.cancel();
      outstanding = new ArrayList<>(timeouts.values());
      timeouts.clear();
      waiting = new ArrayList<>(delays.keySet());
      delays.clear();
    }
    for (Timeout timeout : outstanding) timeout.release();
    for (CompletableFuture<Void> result : waiting) result.cancel(false);
    if (!bindings) return;
    runtime
        .continueOnOwner(this::releaseBindings, true)
        .whenComplete(
            (value, error) -> {
              if (error != null) report(error);
            });
  }

  private void releaseBindings() {
    try {
      runtime
          .evaluate(
              "'use strict'; { const timers = globalThis."
                  + name
                  + "; if (timers) { timers.close(); for (const method of"
                  + " ['setTimeout','clearTimeout','setInterval','clearInterval']) if"
                  + " (Object.getOwnPropertyDescriptor(globalThis, method)?.value ==="
                  + " timers[method]) delete globalThis[method]; } delete globalThis."
                  + name
                  + "; delete globalThis."
                  + name
                  + "Set; delete globalThis."
                  + name
                  + "Clear; }")
          .join();
    } finally {
      runtime.releaseBinding(name + "Set").join();
      runtime.releaseBinding(name + "Clear").join();
    }
  }

  private void report(Throwable error) {
    Throwable cause = error;
    while (cause instanceof CompletionException && cause.getCause() != null)
      cause = cause.getCause();
    if (!(cause instanceof QuickJS.RuntimeClosedException)) errors.accept(error);
  }

  private final class Timeout {

    private final int id;
    private final QuickJS.Reference callback;
    private final int delay;
    private final boolean repeat;
    private int nesting;
    private TimerTask task;

    private Timeout(int id, QuickJS.Reference callback, int delay, boolean repeat, int nesting) {
      this.id = id;
      this.callback = callback;
      this.delay = delay;
      this.repeat = repeat;
      this.nesting = nesting;
    }

    private void dispatch() {
      runtime
          .continueOnOwner(this::fire, false)
          .whenComplete(
              (value, error) -> {
                if (error != null && remove(this)) {
                  release();
                  report(error);
                }
              });
    }

    private void fire() {
      synchronized (Timers.this) {
        if (timeouts.get(id) != this) return;
        if (!repeat) timeouts.remove(id);
      }
      int previous = Timers.this.nesting;
      Timers.this.nesting = nesting;
      try {
        runtime
            .invoke(name, "fire", callback)
            .whenComplete(
                (value, error) -> {
                  if (error != null) report(error);
                });
      } catch (RuntimeException | Error error) {
        report(error);
      } finally {
        Timers.this.nesting = previous;
        if (repeat) rearm();
        else release();
      }
    }

    private void rearm() {
      Throwable failure = null;
      synchronized (Timers.this) {
        if (timeouts.get(id) != this) return;
        try {
          nesting++;
          arm(this);
        } catch (RuntimeException | Error error) {
          timeouts.remove(id);
          failure = error;
        }
      }
      if (failure != null) {
        release();
        report(failure);
      }
    }

    private void release() {
      synchronized (Timers.this) {
        if (task != null && task.cancel()) scheduler.purge();
        task = null;
      }
      runtime
          .continueOnOwner(callback::close, true)
          .whenComplete(
              (value, error) -> {
                if (error != null) report(error);
              });
    }
  }
}
