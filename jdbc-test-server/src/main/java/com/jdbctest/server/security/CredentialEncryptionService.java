package com.jdbctest.server.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public final class CredentialEncryptionService {

  private static final String VERSION = "v1";
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;
  private final SecretKeySpec key;
  private final SecureRandom random;

  @Autowired
  public CredentialEncryptionService(SecurityProperties properties) {
    this(decodeKey(properties.masterKey()), new SecureRandom());
  }

  CredentialEncryptionService(byte[] keyBytes, SecureRandom random) {
    if (keyBytes == null || keyBytes.length != 32) {
      throw new IllegalArgumentException("JDBC_TEST_MASTER_KEY must decode to exactly 32 bytes");
    }
    this.key = new SecretKeySpec(keyBytes, "AES");
    this.random = random;
  }

  public String encrypt(String plaintext) {
    if (plaintext == null) {
      return null;
    }
    byte[] nonce = new byte[NONCE_BYTES];
    random.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      return VERSION + ":" + Base64.getEncoder().encodeToString(nonce) + ":"
          + Base64.getEncoder().encodeToString(ciphertext);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Unable to encrypt credential", exception);
    }
  }

  public String decrypt(String envelope) {
    if (envelope == null || envelope.isEmpty()) {
      return null;
    }
    String[] parts = envelope.split(":", -1);
    if (parts.length != 3 || !VERSION.equals(parts[0])) {
      throw new IllegalArgumentException("Unsupported credential envelope");
    }
    try {
      byte[] nonce = Base64.getDecoder().decode(parts[1]);
      if (nonce.length != NONCE_BYTES) {
        throw new IllegalArgumentException("Invalid credential nonce");
      }
      byte[] ciphertext = Base64.getDecoder().decode(parts[2]);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
      return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException exception) {
      throw exception;
    } catch (GeneralSecurityException exception) {
      throw new IllegalArgumentException("Unable to decrypt credential envelope", exception);
    }
  }

  private static byte[] decodeKey(String encoded) {
    if (encoded == null || encoded.isBlank()) {
      throw new IllegalArgumentException("JDBC_TEST_MASTER_KEY is required");
    }
    try {
      return Base64.getDecoder().decode(encoded);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("JDBC_TEST_MASTER_KEY must be Base64", exception);
    }
  }
}
