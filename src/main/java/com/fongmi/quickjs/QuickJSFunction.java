package com.fongmi.quickjs;

import java.util.ArrayList;
import java.util.Map;

public class QuickJSFunction extends QuickJSObject implements JSFunction {

  public QuickJSFunction(QuickJSContext context, long pointer) {
    super(context, pointer);
  }

  @Override
  public Object call(Object... args) {
    return getContext().call(this, args);
  }

  @Override
  public void callVoid(Object... args) {
    Object value = call(args);
    if (value instanceof JSObject object) object.release();
  }

  @Override
  public Map<String, Object> toMap(MapFilter filter, Object extra, MapCreator creator) {
    throw new UnsupportedOperationException("JavaScript functions cannot be converted to a map");
  }

  @Override
  public ArrayList<Object> toArray(MapFilter filter, Object extra, MapCreator creator) {
    throw new UnsupportedOperationException("JavaScript functions cannot be converted to an array");
  }
}
