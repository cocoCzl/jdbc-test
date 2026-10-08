package com.jdbctest.server.driver;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JdbcDriverArtifactRepository extends
    JpaRepository<JdbcDriverArtifactEntity, UUID> {

  List<JdbcDriverArtifactEntity> findByDriver_Id(UUID driverId);

  void deleteByDriver_Id(UUID driverId);
}
