package com.jdbctest.server.sql;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SqlPackageRepository extends JpaRepository<SqlPackageEntity, String> {
}
