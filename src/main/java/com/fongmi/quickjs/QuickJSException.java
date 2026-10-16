package com.fongmi.quickjs;

public class QuickJSException extends RuntimeException {

    private final boolean jsError;

    public QuickJSException(String message) {
        this(message, false);
    }

    public QuickJSException(String message, boolean jsError) {
        super(message);
        this.jsError = jsError;
    }

    public boolean isJSError() {
        return jsError;
    }
}
