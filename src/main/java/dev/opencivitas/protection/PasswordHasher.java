package dev.opencivitas.protection;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class PasswordHasher {
    private static final String PREFIX = "pbkdf2-sha256";
    private static final int ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;

    private final SecureRandom random = new SecureRandom();
    private final byte[] pepper;

    public PasswordHasher(String encodedPepper) {
        try {
            pepper = Base64.getDecoder().decode(encodedPepper);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Password pepper must be valid base64", exception);
        }
        if (pepper.length != 32) {
            throw new IllegalArgumentException("Password pepper must decode to 32 bytes");
        }
    }

    public String hash(String password) {
        if (password == null || password.isEmpty() || password.length() > 128) {
            throw new IllegalArgumentException("Password must contain 1 to 128 characters");
        }
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = derive(password, salt, ITERATIONS);
        return PREFIX + "$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(hash) + "$" + fingerprint(password);
    }

    public boolean matches(String password, String encoded) {
        if (password == null || password.isEmpty() || password.length() > 128 || encoded == null) return false;
        String[] parts = encoded.split("\\$", -1);
        if (parts.length != 5 || !PREFIX.equals(parts[0])) return false;
        try {
            byte[] storedIndex = Base64.getDecoder().decode(parts[4]);
            byte[] suppliedIndex = Base64.getDecoder().decode(fingerprint(password));
            if (storedIndex.length != 32 || !MessageDigest.isEqual(storedIndex, suppliedIndex)) return false;
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 10_000 || iterations > 1_000_000) return false;
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            return salt.length >= 8 && expected.length == HASH_BITS / Byte.SIZE
                    && MessageDigest.isEqual(expected, derive(password, salt, iterations));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public String fingerprint(String password) {
        if (password == null || password.isEmpty() || password.length() > 128) {
            throw new IllegalArgumentException("Password must contain 1 to 128 characters");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(password.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }

    public static String storedFingerprint(String encoded) {
        if (encoded == null) return "";
        String[] parts = encoded.split("\\$", -1);
        return parts.length == 5 && PREFIX.equals(parts[0]) ? parts[4] : "";
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec specification = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is unavailable", exception);
        } finally {
            specification.clearPassword();
        }
    }
}
