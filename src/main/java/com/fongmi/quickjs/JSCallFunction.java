package com.fongmi.quickjs;

@FunctionalInterface
public interface JSCallFunction {

  Object call(Object... args);
}
