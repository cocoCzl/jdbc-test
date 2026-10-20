package com.jdbctest.server.sql;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Immutable content-addressed revision. Labels never replace existing task evidence. */
@Entity
@Table(name = "sql_test_package")
public class SqlPackageEntity {

    @Id
    @Column(length = 64)
    private String fingerprint;
    @Column(name = "snapshot_json", nullable = false, columnDefinition = "text")
    private String snapshotJson;

    protected SqlPackageEntity() {
    }

    SqlPackageEntity(String fingerprint, String snapshotJson) {
        this.fingerprint = fingerprint;
        this.snapshotJson = snapshotJson;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getSnapshotJson() {
        return snapshotJson;
    }
}
