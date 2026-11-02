package com.fongmi.quickjs;

import static com.fongmi.quickjs.WasmValues.name;

import com.dylibso.chicory.wasm.InvalidException;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.FunctionBody;
import com.dylibso.chicory.wasm.types.FunctionImport;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.GlobalImport;
import com.dylibso.chicory.wasm.types.MemoryImport;
import com.dylibso.chicory.wasm.types.MemorySection;
import com.dylibso.chicory.wasm.types.MutabilityType;
import com.dylibso.chicory.wasm.types.OpCode;
import com.dylibso.chicory.wasm.types.TableImport;
import com.dylibso.chicory.wasm.types.ValType;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class WasmModuleInfo {

  final WasmModule module;
  final int bytes;
  private final List<FunctionImport> functions;
  private final byte[] binary;

  private WasmModuleInfo(WasmModuleInfo source) {
    module = source.module;
    bytes = source.bytes;
    binary = source.binary;
    functions = source.functions;
  }

  private WasmModuleInfo(WasmModule module, byte[] binary, Runnable check) {
    this.module = module;
    this.bytes = binary.length;
    this.binary = binary;
    this.functions =
        module.importSection().stream()
            .filter(FunctionImport.class::isInstance)
            .map(FunctionImport.class::cast)
            .toList();
    validateImports();
    validateTypes();
    validateResources();
    validateInstructions(check);
  }

  private void validateImports() {
    for (var value : module.importSection().stream().toList()) {
      if (value instanceof GlobalImport global) validateFunctionType(global.type());
      else if (value instanceof TableImport table) valueName(table.entryType());
    }
  }

  private void validateTypes() {
    for (int i = 0; i < module.typeSection().subTypeCount(); i++) {
      var composite = module.typeSection().getSubType(i).compType();
      if (composite.structType() != null)
        for (var field : composite.structType().fieldTypes()) {
          ValType value = field.storageType().valType();
          if (value != null) validateFunctionType(value);
        }
      if (composite.arrayType() != null) {
        ValType value = composite.arrayType().fieldType().storageType().valType();
        if (value != null) validateFunctionType(value);
      }
      FunctionType type = module.typeSection().getType(i);
      if (type == null) continue;
      for (ValType value : type.params()) validateFunctionType(value);
      for (ValType value : type.returns()) validateFunctionType(value);
    }
  }

  private void validateResources() {
    for (int i = 0; i < module.tableSection().tableCount(); i++)
      valueName(module.tableSection().getTable(i).elementType());
    for (int i = 0; i < module.globalSection().globalCount(); i++)
      validateFunctionType(module.globalSection().getGlobal(i).valueType());
  }

  private void validateInstructions(Runnable check) {
    int instructions = 0;
    int function = functions.size();
    for (FunctionBody body : module.codeSection().functionBodies()) {
      for (ValType value : body.localTypes()) valueName(value);
      for (var instruction : body.instructions()) {
        if ((instructions++ & 1023) == 0) check.run();
        OpCode opcode = instruction.opcode();
        int prefix = opcode.opcode() >>> 8;
        if (prefix == 0xfd)
          throw unsupportedInstruction(
              "Unsupported WASM instruction", opcode, function, instruction.address());
      }
      function++;
    }
  }

  private static InvalidException unsupportedInstruction(
      String reason, OpCode opcode, int function, int address) {
    return new InvalidException(
        reason
            + ": "
            + opcode
            + " at wasm-function["
            + function
            + "] offset 0x"
            + Integer.toHexString(address));
  }

  static WasmModuleInfo parse(byte[] bytes, Runnable check) {
    WasmModule module =
        Parser.builder()
            .withCustomParsers(Map.of())
            .withInterruptionCheck(check)
            .build()
            .parse(() -> new java.io.ByteArrayInputStream(bytes));
    return new WasmModuleInfo(module, bytes, check);
  }

  WasmModuleInfo copy() {
    return new WasmModuleInfo(this);
  }

  int memoryCount() {
    return module.memorySection().map(MemorySection::memoryCount).orElse(0)
        + (int) module.importSection().stream().filter(MemoryImport.class::isInstance).count();
  }

  ByteBuffer binaryKey() {
    return ByteBuffer.wrap(binary).asReadOnlyBuffer();
  }

  Object[] customSections(String name, Runnable check) {
    List<byte[]> result = new ArrayList<>();
    ByteBuffer buffer = ByteBuffer.wrap(binary);
    buffer.position(8);
    while (buffer.hasRemaining()) {
      check.run();
      int kind = buffer.get() & 0xff;
      int length = (int) com.dylibso.chicory.wasm.Encoding.readVarUInt32(buffer);
      int end = buffer.position() + length;
      if (kind == 0 && com.dylibso.chicory.wasm.Encoding.readName(buffer).equals(name)) {
        byte[] content = new byte[end - buffer.position()];
        buffer.get(content);
        result.add(content);
      }
      buffer.position(end);
    }
    return result.toArray();
  }

  private void validateFunctionType(ValType value) {
    valueName(value);
  }

  FunctionType functionType(int index) {
    int type =
        index < functions.size()
            ? functions.get(index).typeIndex()
            : module.functionSection().getFunctionType(index - functions.size());
    return module.typeSection().getType(type);
  }

  String valueName(ValType value) {
    return WasmValues.name(value, module.typeSection());
  }

  String[] valueNames(List<ValType> values) {
    return values.stream().map(this::valueName).toArray(String[]::new);
  }

  Object[] imports() {
    return module.importSection().stream()
        .map(
            value -> {
              if (value instanceof FunctionImport function) {
                FunctionType type = module.typeSection().getType(function.typeIndex());
                return new Object[] {
                  value.module(),
                  value.name(),
                  "function",
                  valueNames(type.params()),
                  valueNames(type.returns())
                };
              }
              if (value instanceof GlobalImport global)
                return new Object[] {
                  value.module(),
                  value.name(),
                  "global",
                  valueName(global.type()),
                  global.mutabilityType() == MutabilityType.Var
                };
              return new Object[] {
                value.module(), value.name(), value.importType().name().toLowerCase(Locale.ROOT)
              };
            })
        .toArray();
  }

  Object[] exports() {
    return java.util.stream.IntStream.range(0, module.exportSection().exportCount())
        .mapToObj(module.exportSection()::getExport)
        .map(
            value ->
                new Object[] {value.name(), value.exportType().name().toLowerCase(Locale.ROOT)})
        .toArray();
  }
}
