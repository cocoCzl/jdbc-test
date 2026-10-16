package com.jdbctest.server.driver;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JdbcDriverPackageRepository extends JpaRepository<JdbcDriverPackageEntity, UUID> {

    Optional<JdbcDriverPackageEntity> findByPackageFingerprint(String packageFingerprint);
}
