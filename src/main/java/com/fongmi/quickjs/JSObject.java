package com.fongmi.quickjs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Java object API backed by the FongMi QuickJS runtime. */
public interface JSObject {

    void setStackTrace(Throwable trace);
    Throwable getStackTrace();
    void setProperty(String name, String value);
    void setProperty(String name, int value);
    void setProperty(String name, long value);
    void setProperty(String name, JSObject value);
    void setProperty(String name, boolean value);
    void setProperty(String name, double value);
    void setProperty(String name, byte[] value);
    void setProperty(String name, JSCallFunction value);
    void setProperty(String name, Class<?> clazz);
    long getPointer();
    QuickJSContext getContext();
    Object getProperty(String name);
    String getString(String name);
    Integer getInteger(String name);
    Boolean getBoolean(String name);
    Double getDouble(String name);
    Long getLong(String name);
    byte[] getBytes(String name);
    JSObject getJSObject(String name);
    JSFunction getJSFunction(String name);
    JSArray getJSArray(String name);
    JSArray getNames();
    String stringify();
    boolean isAlive();
    void release();
    void hold();
    int getRefCount();
    boolean isRefCountZero();
    void decrementRefCount();
    HashMap<String, Object> toMap();
    ArrayList<Object> toArray();
    HashMap<String, Object> toMap(MapFilter filter);
    ArrayList<Object> toArray(MapFilter filter);
    Map<String, Object> toMap(MapFilter filter, Object extra, MapCreator mapCreator);
    ArrayList<Object> toArray(MapFilter filter, Object extra, MapCreator mapCreator);
}
