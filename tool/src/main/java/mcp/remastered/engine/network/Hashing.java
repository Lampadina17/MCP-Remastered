package mcp.remastered.engine.network;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Small hashing helpers used to verify the files downloaded from Mojang's CDNs.
 */
public final class Hashing {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Hashing() {
    }

    /**
     * Checks cached bytes against the size and SHA-1 supplied by the version metadata.
     */
    public static boolean matches(Path file, long expectedSize, String expectedSha1) throws IOException {
        if (!Files.isRegularFile(file) || (expectedSize > 0 && Files.size(file) != expectedSize)) return false;
        if (expectedSha1 == null || expectedSha1.isBlank()) return expectedSize > 0;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            return toHex(digest.digest()).equalsIgnoreCase(expectedSha1);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is not available", e);
        }
    }

    /**
     * Encodes a byte array as a lowercase hexadecimal string.
     *
     * @param bytes the bytes to encode
     * @return the hexadecimal string
     */
    public static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            out[i * 2] = HEX[value >>> 4];
            out[i * 2 + 1] = HEX[value & 0x0F];
        }
        return new String(out);
    }
}
