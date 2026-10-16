package com.fongmi.quickjs;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

public final class ModuleBytecodeCache {

  private static final ReentrantReadWriteLock IO = new ReentrantReadWriteLock();
  private static final Set<File> TEMPORARY = new HashSet<>();
  private static long revision;
  private static final int MAGIC = 0x514A5301;
  private static final int MAX_ENTRY = 4 * 1024 * 1024;
  private static final byte[] HEADER =
      "FongMi QuickJS-NG 0.17.0 v4\n".getBytes(StandardCharsets.UTF_8);
  private final File directory;
  private final String identity;
  private final long maxBytes;
  private final int maxFiles;
  private final Consumer<IOException> diagnostic;

  public ModuleBytecodeCache(
      File directory,
      String identity,
      long maxBytes,
      int maxFiles,
      Consumer<IOException> diagnostic) {
    if (maxBytes <= 0 || maxFiles <= 0)
      throw new IllegalArgumentException("Invalid bytecode cache limits");
    this.directory = Objects.requireNonNull(directory, "directory");
    this.identity = Objects.requireNonNull(identity, "identity");
    this.maxBytes = maxBytes;
    this.maxFiles = maxFiles;
    this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
  }

  public byte[] load(String name, String source) {
    byte[] expected = key(name, source);
    File file = entry(expected);
    long observed;
    IOException failure;
    IO.readLock().lock();
    try {
      observed = revision;
      if (!file.isFile()) return null;
      try {
        return readEntry(file, expected);
      } catch (IOException error) {
        failure = error;
      }
    } finally {
      IO.readLock().unlock();
    }
    removeInvalidEntry(file, observed, failure);
    diagnostic.accept(failure);
    return null;
  }

  private byte[] readEntry(File file, byte[] expected) throws IOException {
    try (DataInputStream input = new DataInputStream(new FileInputStream(file))) {
      int size = readEntryHeader(input, file, expected);
      return readBytecode(input, size);
    }
  }

  private int readEntryHeader(DataInputStream input, File file, byte[] expected)
      throws IOException {
    if (input.readInt() != MAGIC) throw new IOException("Invalid bytecode cache container");
    int size = input.readInt();
    if (size <= HEADER.length || size > MAX_ENTRY || file.length() != size + 72L)
      throw new IOException("Invalid bytecode cache length");
    byte[] key = new byte[32];
    input.readFully(key);
    if (!MessageDigest.isEqual(key, expected))
      throw new IOException("Invalid bytecode cache source or runtime identity");
    return size;
  }

  private byte[] readBytecode(DataInputStream input, int size) throws IOException {
    byte[] digest = new byte[32];
    input.readFully(digest);
    byte[] bytes = new byte[size];
    input.readFully(bytes);
    if (!MessageDigest.isEqual(digest, hash(bytes)) || !header(bytes))
      throw new IOException("Invalid bytecode cache digest or engine header");
    return bytes;
  }

  private void removeInvalidEntry(File file, long observed, IOException failure) {
    IO.writeLock().lock();
    try {
      if (revision == observed) {
        deleteInvalidEntry(file, failure);
        revision++;
      }
    } finally {
      IO.writeLock().unlock();
    }
  }

  private void deleteInvalidEntry(File file, IOException failure) {
    try {
      Files.deleteIfExists(file.toPath());
    } catch (IOException error) {
      failure.addSuppressed(error);
    }
  }

  public void store(String name, String source, byte[] bytes) {
    if (bytes.length > MAX_ENTRY) return;
    if (!header(bytes)) throw new IllegalArgumentException("Incompatible bytecode engine header");
    byte[] key = key(name, source);
    File target = entry(key);
    File temporary = null;
    IOException failure = null;
    try {
      temporary = temporary();
      writeEntry(temporary, key, bytes);
      IO.writeLock().lock();
      try {
        promoteEntry(temporary, target);
        temporary = null;
        revision++;
        trim();
      } finally {
        IO.writeLock().unlock();
      }
    } catch (IOException error) {
      failure = error;
    } finally {
      failure = discard(temporary, failure);
    }
    if (failure != null) diagnostic.accept(failure);
  }

  private void promoteEntry(File temporary, File target) throws IOException {
    Files.move(
        temporary.toPath(),
        target.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING);
    TEMPORARY.remove(temporary);
  }

  private void writeEntry(File temporary, byte[] key, byte[] bytes) throws IOException {
    try (FileOutputStream file = new FileOutputStream(temporary);
        DataOutputStream output = new DataOutputStream(file)) {
      output.writeInt(MAGIC);
      output.writeInt(bytes.length);
      output.write(key);
      output.write(hash(bytes));
      output.write(bytes);
      output.flush();
      file.getFD().sync();
    }
  }

  private IOException discard(File file, IOException failure) {
    if (file == null) return failure;
    try {
      discard(file);
    } catch (IOException error) {
      if (failure == null) failure = error;
      else failure.addSuppressed(error);
    }
    return failure;
  }

  private File temporary() throws IOException {
    IO.writeLock().lock();
    try {
      File folder = directory.getCanonicalFile();
      Files.createDirectories(folder.toPath());
      removeAbandoned(folder);
      File file = File.createTempFile("module-", ".tmp", folder);
      TEMPORARY.add(file);
      return file;
    } finally {
      IO.writeLock().unlock();
    }
  }

  private void removeAbandoned(File folder) throws IOException {
    File[] abandoned =
        folder.listFiles((parent, name) -> name.startsWith("module-") && name.endsWith(".tmp"));
    if (abandoned == null) throw new IOException("Cannot list bytecode temporary files");
    for (File file : abandoned)
      if (!TEMPORARY.contains(file) && !file.delete())
        throw new IOException("Cannot remove abandoned bytecode file " + file);
  }

  private void discard(File file) throws IOException {
    IO.writeLock().lock();
    try {
      TEMPORARY.remove(file);
      Files.deleteIfExists(file.toPath());
    } finally {
      IO.writeLock().unlock();
    }
  }

  private File entry(byte[] key) {
    return new File(directory, hex(key) + ".qbc");
  }

  private byte[] key(String name, String source) {
    byte[] sourceHash = hashText(source);
    return hashText(identity + '\0' + name + '\0' + hex(sourceHash));
  }

  private void trim() throws IOException {
    File[] files = directory.listFiles((parent, name) -> name.endsWith(".qbc"));
    if (files == null) throw new IOException("Cannot list bytecode cache directory");
    Arrays.sort(
        files,
        Comparator.comparingLong(File::lastModified).reversed().thenComparing(File::getName));
    long total = 0;
    for (int i = 0; i < files.length; i++) {
      total += files[i].length();
      if ((i >= maxFiles || total > maxBytes) && !files[i].delete())
        throw new IOException("Cannot trim bytecode cache " + files[i]);
    }
  }

  private static boolean header(byte[] bytes) {
    if (bytes.length <= HEADER.length) return false;
    for (int i = 0; i < HEADER.length; i++) if (bytes[i] != HEADER[i]) return false;
    return true;
  }

  private static byte[] hash(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 unavailable", error);
    }
  }

  private static byte[] hashText(String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      updateTextHash(digest, text);
      return digest.digest();
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 unavailable", error);
    }
  }

  private static void updateTextHash(MessageDigest digest, String text) {
    byte[] block = new byte[4096];
    int size = 0;
    for (int i = 0; i < text.length(); i++) {
      char value = text.charAt(i);
      block[size++] = (byte) (value >>> 8);
      block[size++] = (byte) value;
      if (size == block.length) {
        digest.update(block);
        size = 0;
      }
    }
    digest.update(block, 0, size);
  }

  private static String hex(byte[] bytes) {
    StringBuilder result = new StringBuilder(bytes.length * 2);
    for (byte value : bytes)
      result
          .append(Character.forDigit((value & 255) >>> 4, 16))
          .append(Character.forDigit(value & 15, 16));
    return result.toString();
  }
}
