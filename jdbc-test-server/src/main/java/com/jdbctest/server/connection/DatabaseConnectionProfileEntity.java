package com.jdbctest.server.connection;

import com.jdbctest.server.driver.JdbcDriverPackageEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "database_connection_profile")
public class DatabaseConnectionProfileEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false, length = 200) private String name;
    @Column(name = "database_type", nullable = false, length = 80) private String databaseType;
    @Column(name = "adapter_id", nullable = false, length = 100) private String adapterId;
    @Column(name = "jdbc_url", nullable = false, length = 2000) private String jdbcUrl;
    @Column(nullable = false, length = 200) private String username;
    @Column(name = "encrypted_password", length = 2000) private String encryptedPassword;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "default_driver_id") private JdbcDriverPackageEntity defaultDriver;
    @Column(length = 1000) private String description;
    @Column(name = "last_connection_test_at") private Instant lastConnectionTestAt;
    @Enumerated(EnumType.STRING) @Column(name = "last_connection_test_status", length = 20) private ConnectionTestStatus lastConnectionTestStatus;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    @Column(name = "execution_database", length = 63) private String executionDatabase;
    @Column(name = "execution_schema", length = 63) private String executionSchema;
    public String getExecutionDatabase() { return executionDatabase; }
    public String getExecutionSchema() { return executionSchema; }
    public void setExecutionScope(String database, String schema) { executionDatabase = database; executionSchema = schema; }

    protected DatabaseConnectionProfileEntity() { }
    public DatabaseConnectionProfileEntity(String name, String databaseType, String jdbcUrl, String username) {
        this(name, databaseType, databaseType, jdbcUrl, username);
    }
    public DatabaseConnectionProfileEntity(String name, String databaseType, String adapterId, String jdbcUrl, String username) {
        this.name = name; this.databaseType = databaseType; this.adapterId = adapterId; this.jdbcUrl = jdbcUrl; this.username = username;
    }
    @PrePersist void onCreate() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getDatabaseType() { return databaseType; }
    public String getAdapterId() { return adapterId; }
    public String getJdbcUrl() { return jdbcUrl; }
    public String getUsername() { return username; }
    public String getEncryptedPassword() { return encryptedPassword; }
    public void setEncryptedPassword(String value) { encryptedPassword = value; }
    public JdbcDriverPackageEntity getDefaultDriver() { return defaultDriver; }
    public void setDefaultDriver(JdbcDriverPackageEntity value) { defaultDriver = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description = value; }
    public void update(String name, String databaseType, String adapterId, String jdbcUrl, String username, String description, JdbcDriverPackageEntity defaultDriver) {
        this.name = name; this.databaseType = databaseType; this.adapterId = adapterId; this.jdbcUrl = jdbcUrl; this.username = username;
        this.description = description; this.defaultDriver = defaultDriver;
    }
    public void recordConnectionTest(ConnectionTestStatus status) { this.lastConnectionTestAt = Instant.now(); this.lastConnectionTestStatus = status; }
    public Instant getLastConnectionTestAt() { return lastConnectionTestAt; }
    public ConnectionTestStatus getLastConnectionTestStatus() { return lastConnectionTestStatus; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
