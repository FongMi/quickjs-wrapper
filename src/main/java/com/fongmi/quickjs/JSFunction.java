package com.fongmi.quickjs;

public interface JSFunction extends JSObject {

  Object call(Object... args);

  void callVoid(Object... args);
}
