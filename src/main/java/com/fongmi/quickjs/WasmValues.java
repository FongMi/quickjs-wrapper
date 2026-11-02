package com.fongmi.quickjs;

import com.dylibso.chicory.wasm.InvalidException;
import com.dylibso.chicory.wasm.types.TypeSection;
import com.dylibso.chicory.wasm.types.ValType;
import java.math.BigInteger;
import java.util.List;

final class WasmValues {

  private WasmValues() {}

  static int integer(Object value) {
    return ((Number) value).intValue();
  }

  static ValType type(String value) {
    return switch (value) {
      case "i32" -> ValType.I32;
      case "i64" -> ValType.I64;
      case "f32" -> ValType.F32;
      case "f64" -> ValType.F64;
      case "externref" -> ValType.ExternRef;
      case "gcref" -> ValType.AnyRef;
      case "exnref" -> ValType.ExnRef;
      case "anyfunc", "funcref" -> ValType.FuncRef;
      default -> throw new InvalidException("Unsupported WASM value type: " + value);
    };
  }

  static String name(ValType type) {
    for (String value : new String[] {"i32", "i64", "f32", "f64", "externref", "funcref"})
      if (type(value).equals(type)) return value;
    throw new InvalidException("Unsupported WASM value type: " + type);
  }

  static String name(ValType type, TypeSection types) {
    if (!type.isReference()) return name(type);
    int heap = type.typeIdx();
    if (heap == ValType.ExternRef.typeIdx() || heap == ValType.NoExternRef.typeIdx())
      return "externref";
    if (heap == ValType.FuncRef.typeIdx()
        || heap == ValType.NoFuncRef.typeIdx()
        || heap >= 0 && types != null && types.getType(heap) != null) return "funcref";
    if (heap == ValType.ExnRef.typeIdx()) return "exnref";
    return "gcref";
  }

  static String[] names(List<ValType> values) {
    return values.stream().map(WasmValues::name).toArray(String[]::new);
  }

  static Object fromWasm(ValType type, long value) {
    if (type.equals(ValType.I64)) return BigInteger.valueOf(value);
    if (type.equals(ValType.F32)) return Float.intBitsToFloat((int) value);
    if (type.equals(ValType.F64)) return Double.longBitsToDouble(value);
    return (int) value;
  }

  static long toWasm(ValType type, Object value) {
    Number number = (Number) value;
    if (type.equals(ValType.I64)) return ((BigInteger) value).longValue();
    if (type.equals(ValType.F32)) return Float.floatToRawIntBits(number.floatValue());
    if (type.equals(ValType.F64)) return Double.doubleToRawLongBits(number.doubleValue());
    return number.intValue();
  }

  static Object[] fromWasm(List<ValType> types, long[] values) {
    Object[] result = new Object[types.size()];
    for (int i = 0; i < result.length; i++) result[i] = fromWasm(types.get(i), values[i]);
    return result;
  }

  static long[] toWasm(List<ValType> types, Object[] values) {
    long[] result = new long[types.size()];
    for (int i = 0; i < result.length; i++) result[i] = toWasm(types.get(i), values[i]);
    return result;
  }
}
