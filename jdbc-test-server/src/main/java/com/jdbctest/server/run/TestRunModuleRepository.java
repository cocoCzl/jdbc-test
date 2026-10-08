package com.jdbctest.server.run;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestRunModuleRepository extends JpaRepository<TestRunModuleEntity, UUID> {

}
