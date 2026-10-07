package com.jdbctest.server.run;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "test_run_credential")
public class TestRunCredentialEntity {
    @Id @Column(name = "run_id") private UUID runId;
    @Column(name = "encrypted_password", nullable = false, length = 2000) private String encryptedPassword;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected TestRunCredentialEntity() { }
    TestRunCredentialEntity(UUID runId, String encryptedPassword) { this.runId = runId; this.encryptedPassword = encryptedPassword; this.createdAt = Instant.now(); }
    String getEncryptedPassword() { return encryptedPassword; }
}
