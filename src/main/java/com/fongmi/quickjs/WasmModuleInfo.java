package com.fongmi.quickjs;

import static com.fongmi.quickjs.WasmValues.name;
import static com.fongmi.quickjs.WasmValues.names;

import com.dylibso.chicory.wasm.InvalidException;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.ExternalType;
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

final class WasmModuleInfo {

  final WasmModule module;
  final int bytes;
  private final List<FunctionImport> functions;
  private final byte[] binary;

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
      if (value instanceof MemoryImport memory && memory.limits().shared())
        throw new InvalidException("Shared WASM memory is disabled");
      else if (value instanceof GlobalImport global) validateFunctionType(global.type());
      else if (value instanceof TableImport table) name(table.entryType());
      else if (value.importType() == ExternalType.TAG)
        throw new InvalidException("WASM exception tags are not supported");
    }
  }

  private void validateTypes() {
    for (int i = 0; i < module.typeSection().subTypeCount(); i++) {
      FunctionType type = module.typeSection().getType(i);
      if (type == null) throw new InvalidException("WASM GC types are not supported");
      for (ValType value : type.params()) validateFunctionType(value);
      for (ValType value : type.returns()) validateFunctionType(value);
    }
  }

  private void validateResources() {
    int memories =
        module.memorySection().map(MemorySection::memoryCount).orElse(0)
            + (int) module.importSection().stream().filter(MemoryImport.class::isInstance).count();
    if (memories > 1) throw new InvalidException("Multiple WASM memories are not supported");
    for (int i = 0; i < module.tableSection().tableCount(); i++)
      name(module.tableSection().getTable(i).elementType());
    if (module.tagSection().isPresent())
      throw new InvalidException("WASM exception tags are not supported");
    for (int i = 0; i < module.globalSection().globalCount(); i++)
      validateFunctionType(module.globalSection().getGlobal(i).valueType());
    for (int i = 0; i < module.memorySection().map(MemorySection::memoryCount).orElse(0); i++)
      if (module.memorySection().get().getMemory(i).limits().shared())
        throw new InvalidException("Shared WASM memory is disabled");
  }

  private void validateInstructions(Runnable check) {
    int instructions = 0;
    for (FunctionBody body : module.codeSection().functionBodies()) {
      for (ValType value : body.localTypes()) name(value);
      for (var instruction : body.instructions()) {
        if ((instructions++ & 1023) == 0) check.run();
        OpCode opcode = instruction.opcode();
        int prefix = opcode.opcode() >>> 8;
        if (prefix == 0xfb
            || prefix == 0xfd
            || prefix == 0xfe
            || opcode == OpCode.CALL_REF
            || opcode == OpCode.RETURN_CALL
            || opcode == OpCode.RETURN_CALL_REF
            || opcode == OpCode.RETURN_CALL_INDIRECT)
          throw new InvalidException("Unsupported WASM instruction: " + opcode);
        if ((opcode == OpCode.TABLE_GET || opcode == OpCode.TABLE_SET)
            && tableType((int) instruction.operand(0)).equals(ValType.FuncRef))
          throw new InvalidException("First-class WASM function references are not supported");
      }
    }
  }

  static WasmModuleInfo parse(byte[] bytes, Runnable check) {
    WasmModule module =
        Parser.builder()
            .withInterruptionCheck(check)
            .build()
            .parse(() -> new java.io.ByteArrayInputStream(bytes));
    return new WasmModuleInfo(module, bytes, check);
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

  private ValType tableType(int index) {
    for (var value : module.importSection().stream().toList())
      if (value instanceof TableImport table) {
        if (index-- == 0) return table.entryType();
      }
    return module.tableSection().getTable(index).elementType();
  }

  private static void validateFunctionType(ValType value) {
    name(value);
    if (value.equals(ValType.FuncRef))
      throw new InvalidException(
          "WASM function reference parameters and results are not supported");
  }

  FunctionType functionType(int index) {
    int type =
        index < functions.size()
            ? functions.get(index).typeIndex()
            : module.functionSection().getFunctionType(index - functions.size());
    return module.typeSection().getType(type);
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
                  names(type.params()),
                  names(type.returns())
                };
              }
              if (value instanceof GlobalImport global)
                return new Object[] {
                  value.module(),
                  value.name(),
                  "global",
                  name(global.type()),
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
