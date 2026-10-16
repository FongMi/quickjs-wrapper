package com.fongmi.quickjs;

public interface JSObjectCreator {

    JSObject newObject(QuickJSContext context, long pointer);
    JSArray newArray(QuickJSContext context, long pointer);
    JSFunction newFunction(QuickJSContext context, long pointer);
}
