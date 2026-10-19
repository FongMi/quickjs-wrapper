package com.fongmi.quickjs;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.CompletionException;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class WebCrypto {

    private static final SecureRandom RANDOM = new SecureRandom();

    private WebCrypto() {}

    static byte[] random(int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    static Object perform(Object[] args) {
        try {
            String operation = (String) args[0];
            String algorithm = (String) args[1];
            byte[] data = (byte[]) args[3];
            if (operation.equals("digest")) return MessageDigest.getInstance(algorithm).digest(data);
            byte[] key = (byte[]) args[2];
            if (operation.equals("sign") || operation.equals("verify")) {
                byte[] signature = sign(algorithm, key, data);
                return operation.equals("sign") ? signature : MessageDigest.isEqual(signature, (byte[]) args[7]);
            }
            return createCipher(operation, algorithm, key, args).doFinal(data);
        } catch (GeneralSecurityException error) {
            throw new CompletionException(error);
        }
    }

    private static byte[] sign(String algorithm, byte[] key, byte[] data) throws GeneralSecurityException {
        String name = "Hmac" + algorithm.replace("-", "");
        Mac mac = Mac.getInstance(name);
        mac.init(new SecretKeySpec(key, name));
        return mac.doFinal(data);
    }

    private static Cipher createCipher(String operation, String algorithm, byte[] key, Object[] args) throws GeneralSecurityException {
        boolean gcm = algorithm.equals("AES-GCM");
        Cipher cipher = Cipher.getInstance(gcm ? "AES/GCM/NoPadding" : "AES/CBC/PKCS5Padding");
        byte[] iv = (byte[]) args[4];
        int mode = operation.equals("encrypt") ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE;
        if (gcm) {
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(((Number) args[6]).intValue(), iv));
            cipher.updateAAD((byte[]) args[5]);
        } else {
            cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        }
        return cipher;
    }
}
