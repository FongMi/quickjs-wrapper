package com.fongmi.quickjs;

import java.util.Map;

@FunctionalInterface
public interface MapCreator {

    Map<String, Object> get();
}
