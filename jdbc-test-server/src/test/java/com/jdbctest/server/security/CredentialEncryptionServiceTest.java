package com.jdbctest.server.security;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class CredentialEncryptionServiceTest {

  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

  private CredentialEncryptionService service() {
    return new CredentialEncryptionService(new SecurityProperties(KEY));
  }

  @Test
  void encryptDecryptAndRandomNonce() {
    CredentialEncryptionService service = service();
    String first = service.encrypt("密码-🔐");
    String second = service.encrypt("密码-🔐");
    assertNotEquals("密码-🔐", first);
    assertNotEquals(first, second);
    assertEquals("密码-🔐", service.decrypt(first));
  }

  @Test
  void tamperingAndWrongKeyFail() {
    CredentialEncryptionService service = service();
    String envelope = service.encrypt("secret");
    String tampered = envelope.substring(0, envelope.length() - 1) + "A";
    assertThrows(IllegalArgumentException.class, () -> service.decrypt(tampered));
    byte[] otherKey = new byte[32];
    otherKey[0] = 1;
    CredentialEncryptionService other = new CredentialEncryptionService(new SecurityProperties(
        Base64.getEncoder().encodeToString(otherKey)));
    assertThrows(IllegalArgumentException.class, () -> other.decrypt(envelope));
  }

  @Test
  void invalidKeyAndEmptyValuesAreExplicit() {
    assertThrows(IllegalArgumentException.class,
        () -> new CredentialEncryptionService(new SecurityProperties("bad")));
    CredentialEncryptionService service = service();
    assertNull(service.encrypt(null));
    assertNull(service.decrypt(null));
    assertThrows(IllegalArgumentException.class, () -> service.decrypt("not-an-envelope"));
  }
}
