package com.jdbctest.server.driver;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "jdbc_driver_package")
public class JdbcDriverPackageEntity {

  @Id
  private UUID id;
  @Column(nullable = false, length = 200)
  private String name;
  @Column(nullable = false, length = 120)
  private String vendor;
  @Column(nullable = false, length = 80)
  private String version;
  @Column(name = "driver_class", nullable = false, length = 300)
  private String driverClass;
  @Column(name = "url_prefix", length = 200)
  private String urlPrefix;
  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, length = 20)
  private DriverSourceType sourceType;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private DriverPackageStatus status;
  @Column(name = "package_fingerprint", length = 128)
  private String packageFingerprint;
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected JdbcDriverPackageEntity() {
  }

  public JdbcDriverPackageEntity(String name, String vendor, String version, String driverClass,
      DriverSourceType sourceType) {
    this.id = UUID.randomUUID();
    Instant now = Instant.now();
    this.createdAt = now;
    this.updatedAt = now;
    this.name = name;
    this.vendor = vendor;
    this.version = version;
    this.driverClass = driverClass;
    this.sourceType = sourceType;
    this.status = DriverPackageStatus.PENDING_VALIDATION;
  }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getVendor() {
    return vendor;
  }

  public String getVersion() {
    return version;
  }

  public String getDriverClass() {
    return driverClass;
  }

  public String getUrlPrefix() {
    return urlPrefix;
  }

  public void setUrlPrefix(String value) {
    urlPrefix = value;
  }

  public void updateMetadata(String name, String vendor, String version, String urlPrefix,
      String driverClass) {
    this.name = name;
    this.vendor = vendor;
    this.version = version;
    this.urlPrefix = urlPrefix;
    if (!this.driverClass.equals(driverClass)) {
      this.driverClass = driverClass;
      this.status = DriverPackageStatus.PENDING_VALIDATION;
    }
  }

  public DriverSourceType getSourceType() {
    return sourceType;
  }

  public DriverPackageStatus getStatus() {
    return status;
  }

  public String getPackageFingerprint() {
    return packageFingerprint;
  }

  public void setPackageFingerprint(String value) {
    packageFingerprint = value;
  }

  public void setStatus(DriverPackageStatus value) {
    status = value;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
