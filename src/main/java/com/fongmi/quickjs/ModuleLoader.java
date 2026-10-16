package com.fongmi.quickjs;

public abstract class ModuleLoader {

  public abstract boolean isBytecodeMode();

  public abstract byte[] getModuleBytecode(String moduleName);

  public abstract String getModuleStringCode(String moduleName);

  public String moduleNormalizeName(String baseModuleName, String moduleName) {
    return moduleName;
  }
}
