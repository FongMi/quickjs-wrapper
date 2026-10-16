package com.fongmi.quickjs;

@FunctionalInterface
public interface MapFilter {

  boolean shouldSkipKey(String key, long pointer, Object extra);
}
