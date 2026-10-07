package com.jdbctest.server.run;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface TestRunModuleRepository extends JpaRepository<TestRunModuleEntity, UUID> { }
