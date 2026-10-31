package com.fongmi.quickjs;

final class WasmLimitException extends RuntimeException {

  WasmLimitException(String message) {
    super(message);
  }
}
