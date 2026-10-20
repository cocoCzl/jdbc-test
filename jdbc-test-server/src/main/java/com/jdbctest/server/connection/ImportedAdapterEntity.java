package com.jdbctest.server.connection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Current explicit selection; runs retain independent immutable package snapshots. */
@Entity
@Table(name = "imported_adapter")
public class ImportedAdapterEntity {

    @Id
    @Column(length = 32)
    private String id;
    @Column(nullable = false, length = 64)
    private String fingerprint;
    @Column(name = "snapshot_json", nullable = false, columnDefinition = "text")
    private String snapshotJson;
    @Column(nullable = false)
    private boolean archived;
    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    protected ImportedAdapterEntity() {
    }

    ImportedAdapterEntity(String id) {
        this.id = id;
    }

    void replace(String fingerprint, String snapshotJson) {
        this.fingerprint = fingerprint;
        this.snapshotJson = snapshotJson;
        archived = false;
    }

    void archive() {
        archived = true;
    }

    public String getId() {
        return id;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getSnapshotJson() {
        return snapshotJson;
    }

    public boolean isArchived() {
        return archived;
    }
}
