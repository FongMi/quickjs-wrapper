package com.fongmi.quickjs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class QuickJSArray extends QuickJSObject implements JSArray {

    public QuickJSArray(QuickJSContext context, long pointer) {
        super(context, pointer);
    }

    @Override
    public int length() {
        return getContext().length(this);
    }

    @Override
    public Object get(int index) {
        return getContext().get(this, index);
    }

    @Override
    public void set(Object value, int index) {
        getContext().set(this, value, index);
    }

    @Override
    public Map<String, Object> toMap(MapFilter filter, Object extra, MapCreator creator) {
        throw new UnsupportedOperationException("Use toArray for a JavaScript array");
    }

    @Override
    public ArrayList<Object> toArray(MapFilter filter, Object extra, MapCreator creator) {
        return getContext().run(() -> {
            checkRefCountIsZero();
            ArrayList<Object> result = new ArrayList<>(length());
            convertToMap(this, result, new HashMap<>(), filter, extra, creator);
            return result;
        });
    }
}
