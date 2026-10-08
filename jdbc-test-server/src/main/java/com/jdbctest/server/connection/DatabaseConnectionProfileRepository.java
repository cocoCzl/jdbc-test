package com.jdbctest.server.connection;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DatabaseConnectionProfileRepository extends
    JpaRepository<DatabaseConnectionProfileEntity, UUID> {

  long countByDefaultDriver_Id(UUID driverId);
}
