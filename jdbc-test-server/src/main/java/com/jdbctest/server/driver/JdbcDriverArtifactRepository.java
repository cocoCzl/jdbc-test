package com.jdbctest.server.driver;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
import java.util.List;

public interface JdbcDriverArtifactRepository extends JpaRepository<JdbcDriverArtifactEntity, UUID> {
    List<JdbcDriverArtifactEntity> findByDriver_Id(UUID driverId);
    void deleteByDriver_Id(UUID driverId);
}
