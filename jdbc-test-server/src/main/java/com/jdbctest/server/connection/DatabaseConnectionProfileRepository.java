package com.jdbctest.server.connection;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface DatabaseConnectionProfileRepository extends
    JpaRepository<DatabaseConnectionProfileEntity, UUID> {

  long countByDefaultDriver_Id(UUID driverId);

  Page<DatabaseConnectionProfileEntity> findByAdapterId(String adapterId, Pageable pageable);
}
