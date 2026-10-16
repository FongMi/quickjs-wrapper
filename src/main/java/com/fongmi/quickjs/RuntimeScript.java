package com.fongmi.quickjs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

final class RuntimeScript {

    private RuntimeScript() {}

    static String read(String name) {
        try (InputStream input = RuntimeScript.class.getResourceAsStream("/com/fongmi/quickjs/" + name)) {
            if (input == null) throw new IllegalStateException("Missing " + name + " resource");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
