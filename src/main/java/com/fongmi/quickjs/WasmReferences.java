package com.fongmi.quickjs;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Java identities are weak here; WASM globals, tables and heap edges own their values. */
final class WasmReferences {

  private final Map<Key, Integer> identities = new HashMap<>();
  private final Map<Integer, Key> values = new HashMap<>();
  private final ReferenceQueue<Object> queue = new ReferenceQueue<>();
  private int next;

  int id(Object value) {
    if (value == null) return -1;
    drain();
    Integer previous = identities.get(new Key(value, null, 0));
    if (previous != null) return previous;
    if (next == Integer.MAX_VALUE)
      throw new IllegalStateException("WASM reference addresses exhausted");
    int id = ++next;
    Key key = new Key(value, queue, id);
    identities.put(key, id);
    values.put(id, key);
    return id;
  }

  Object get(int id) {
    if (id == -1) return null;
    Key key = values.get(id);
    Object value = key == null ? null : key.get();
    if (value == null) throw new IllegalStateException("WASM reference is no longer available");
    return value;
  }

  void retain(Set<Integer> roots) {
    drain();
    values
        .entrySet()
        .removeIf(
            entry -> {
              if (roots.contains(entry.getKey())) return false;
              identities.remove(entry.getValue());
              return true;
            });
  }

  void clear() {
    identities.clear();
    values.clear();
  }

  private void drain() {
    for (var value = queue.poll(); value != null; value = queue.poll()) {
      Key key = (Key) value;
      identities.remove(key);
      values.remove(key.id, key);
    }
  }

  private static final class Key extends WeakReference<Object> {

    final int id;
    final int hash;

    Key(Object value, ReferenceQueue<Object> queue, int id) {
      super(value, queue);
      this.id = id;
      this.hash = System.identityHashCode(value);
    }

    @Override
    public int hashCode() {
      return hash;
    }

    @Override
    public boolean equals(Object other) {
      return this == other || other instanceof Key key && get() != null && get() == key.get();
    }
  }
}
