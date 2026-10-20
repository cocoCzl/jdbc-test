package com.jdbctest.server.connection;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportedAdapterRepository extends JpaRepository<ImportedAdapterEntity, String> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select adapter from ImportedAdapterEntity adapter where adapter.id = :id")
    java.util.Optional<ImportedAdapterEntity> findForUpdate(
        @org.springframework.data.repository.query.Param("id") String id);
}
