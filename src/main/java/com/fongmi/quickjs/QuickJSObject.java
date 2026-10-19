package com.fongmi.quickjs;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** An owned reference to a value in a {@link QuickJSContext}. */
public class QuickJSObject implements JSObject {

  private final QuickJSContext context;
  private final long pointer;
  private volatile int references = 1;
  private Throwable stackTrace;

  public QuickJSObject(QuickJSContext context, long pointer) {
    this.context = Objects.requireNonNull(context, "context");
    this.pointer = pointer;
  }

  @Override
  public long getPointer() {
    return pointer;
  }

  @Override
  public QuickJSContext getContext() {
    return context;
  }

  @Override
  public void setStackTrace(Throwable trace) {
    stackTrace = trace;
  }

  @Override
  public Throwable getStackTrace() {
    return stackTrace;
  }

  @Override
  public Object getProperty(String name) {
    return context.getProperty(this, name);
  }

  @Override
  public void setProperty(String name, String value) {
    context.setProperty(this, name, value);
  }

  @Override
  public void setProperty(String name, int value) {
    context.setProperty(this, name, value);
  }

  @Override
  public void setProperty(String name, long value) {
    context.setProperty(this, name, value);
  }

  @Override
  public void setProperty(String name, JSObject value) {
    context.setProperty(this, name, value);
  }

  @Override
  public void setProperty(String name, boolean value) {
    context.setProperty(this, name, value);
  }

  @Override
  public void setProperty(String name, double value) {
    context.setProperty(this, name, value);
  }

  @Override
  public void setProperty(String name, byte[] value) {
    context.setProperty(this, name, value);
  }

  @Override
  public void setProperty(String name, JSCallFunction value) {
    context.setProperty(this, name, value);
  }

  @Override
  public void setProperty(String name, Class<?> type) {
    final Object instance;
    try {
      instance = type.getDeclaredConstructor().newInstance();
    } catch (ReflectiveOperationException error) {
      throw new QuickJSException("Cannot construct Java binding " + type.getName() + ": " + error);
    }
    JSObject binding = context.createNewJSObject();
    try {
      for (Method method : type.getMethods()) {
        if (!method.isAnnotationPresent(JSMethod.class)) continue;
        binding.setProperty(method.getName(), args -> invokeBinding(method, instance, args));
      }
      setProperty(name, binding);
    } finally {
      binding.release();
    }
  }

  private static Object invokeBinding(Method method, Object instance, Object[] args) {
    try {
      return method.invoke(instance, args);
    } catch (InvocationTargetException error) {
      Throwable cause = error.getCause();
      if (cause instanceof RuntimeException runtime) throw runtime;
      if (cause instanceof Error fatal) throw fatal;
      throw new QuickJSException(cause.toString());
    } catch (IllegalAccessException error) {
      throw new QuickJSException(error.toString());
    }
  }

  private <T> T property(String name, Class<T> type) {
    Object value = getProperty(name);
    if (type.isInstance(value)) return type.cast(value);
    if (value instanceof JSObject object) object.release();
    return null;
  }

  @Override
  public String getString(String name) {
    return property(name, String.class);
  }

  @Override
  public Integer getInteger(String name) {
    return property(name, Integer.class);
  }

  @Override
  public Boolean getBoolean(String name) {
    return property(name, Boolean.class);
  }

  @Override
  public Double getDouble(String name) {
    return property(name, Double.class);
  }

  @Override
  public Long getLong(String name) {
    return property(name, Long.class);
  }

  @Override
  public byte[] getBytes(String name) {
    return property(name, byte[].class);
  }

  @Override
  public JSObject getJSObject(String name) {
    return property(name, JSObject.class);
  }

  @Override
  public JSFunction getJSFunction(String name) {
    return property(name, JSFunction.class);
  }

  @Override
  public JSArray getJSArray(String name) {
    return property(name, JSArray.class);
  }

  @Override
  public JSArray getNames() {
    return (JSArray) context.getOwnPropertyNames(this);
  }

  @Override
  public String stringify() {
    return context.stringify(this);
  }

  @Override
  public int getRefCount() {
    return references;
  }

  @Override
  public boolean isRefCountZero() {
    return references == 0;
  }

  @Override
  public boolean isAlive() {
    return references != 0 && context.isLiveObject(this);
  }

  @Override
  public void hold() {
    context.run(
        () -> {
          checkRefCountIsZero();
          context.hold(this);
          references++;
          return null;
        });
  }

  @Override
  public void release() {
    if (references == 0) return;
    context.run(
        () -> {
          if (references != 0) {
            references--;
            context.freeValue(this);
          }
          return null;
        });
  }

  @Override
  public void decrementRefCount() {
    checkRefCountIsZero();
    references--;
  }

  final void checkRefCountIsZero() {
    if (references == 0) throw new QuickJSException("JavaScript object has been released");
  }

  @Override
  public HashMap<String, Object> toMap() {
    return toMap(null);
  }

  @Override
  public HashMap<String, Object> toMap(MapFilter filter) {
    return (HashMap<String, Object>) toMap(filter, null, HashMap::new);
  }

  @Override
  public Map<String, Object> toMap(MapFilter filter, Object extra, MapCreator creator) {
    return context.run(
        () -> {
          checkRefCountIsZero();
          Map<String, Object> result = Objects.requireNonNull(creator, "creator").get();
          convertToMap(this, result, new HashMap<>(), filter, extra, creator);
          return result;
        });
  }

  @Override
  public ArrayList<Object> toArray() {
    return toArray(null);
  }

  @Override
  public ArrayList<Object> toArray(MapFilter filter) {
    return toArray(filter, null, HashMap::new);
  }

  @Override
  public ArrayList<Object> toArray(MapFilter filter, Object extra, MapCreator creator) {
    throw new UnsupportedOperationException("Use toMap for a JavaScript object");
  }

  /** Keeps visited values alive until the graph has been converted, including later aliases. */
  protected void convertToMap(
      Object target,
      Object result,
      HashMap<Long, Object> seen,
      MapFilter filter,
      Object extra,
      MapCreator creator) {
    ArrayList<JSObject> retained = new ArrayList<>();
    try {
      convert((JSObject) target, result, seen, filter, extra, creator, retained);
    } finally {
      for (JSObject object : retained) object.release();
    }
  }

  @SuppressWarnings("unchecked")
  private void convert(
      JSObject object,
      Object result,
      HashMap<Long, Object> seen,
      MapFilter filter,
      Object extra,
      MapCreator creator,
      ArrayList<JSObject> retained) {
    seen.put(object.getPointer(), result);
    Object[] names = object instanceof JSArray ? null : context.propertyNames(object);
    int size = names == null ? ((JSArray) object).length() : names.length;
    for (int index = 0; index < size; index++) {
      String key = names == null ? null : (String) names[index];
      if (key != null && filter != null && filter.shouldSkipKey(key, object.getPointer(), extra))
        continue;
      Object value = names == null ? ((JSArray) object).get(index) : object.getProperty(key);
      JSObject child = value instanceof JSObject ? (JSObject) value : null;
      if (child != null) retained.add(child);
      if (value instanceof JSFunction) continue;
      if (child != null) {
        value = seen.get(child.getPointer());
        if (value == null) {
          value = child instanceof JSArray ? new ArrayList<>() : creator.get();
          convert(child, value, seen, filter, extra, creator, retained);
        }
      }
      if (result instanceof Map) ((Map<String, Object>) result).put(key, value);
      else ((ArrayList<Object>) result).add(value);
    }
  }

  @Override
  public String toString() {
    JSFunction function = getJSFunction("toString");
    if (function == null) throw new QuickJSException("Object has no toString function");
    try {
      return String.valueOf(function.call());
    } finally {
      function.release();
    }
  }

  @Override
  public int hashCode() {
    return Long.hashCode(pointer);
  }
}
