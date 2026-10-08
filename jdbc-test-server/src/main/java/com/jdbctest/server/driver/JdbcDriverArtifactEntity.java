package com.jdbctest.server.driver;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "jdbc_driver_artifact")
public class JdbcDriverArtifactEntity {

  @Id
  private UUID id;
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "driver_id", nullable = false)
  private JdbcDriverPackageEntity driver;
  @Column(name = "file_name", nullable = false, length = 255)
  private String fileName;
  @Column(name = "storage_key", nullable = false, length = 500)
  private String storageKey;
  @Column(nullable = false)
  private long size;
  @Column(nullable = false, length = 64)
  private String sha256;
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected JdbcDriverArtifactEntity() {
  }

  public JdbcDriverArtifactEntity(JdbcDriverPackageEntity driver, String fileName,
      String storageKey, long size, String sha256) {
    this(UUID.randomUUID(), driver, fileName, storageKey, size, sha256);
  }

  public JdbcDriverArtifactEntity(UUID id, JdbcDriverPackageEntity driver, String fileName,
      String storageKey, long size, String sha256) {
    this.id = id;
    this.driver = driver;
    this.fileName = fileName;
    this.storageKey = storageKey;
    this.size = size;
    this.sha256 = sha256;
  }

  @PrePersist
  void onCreate() {
    createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public JdbcDriverPackageEntity getDriver() {
    return driver;
  }

  public String getFileName() {
    return fileName;
  }

  public String getStorageKey() {
    return storageKey;
  }

  public long getSize() {
    return size;
  }

  public String getSha256() {
    return sha256;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
