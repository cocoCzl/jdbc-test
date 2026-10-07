package com.jdbctest.server.connection;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface DatabaseConnectionProfileRepository extends JpaRepository<DatabaseConnectionProfileEntity, UUID> {
    long countByDefaultDriver_Id(UUID driverId);
}
