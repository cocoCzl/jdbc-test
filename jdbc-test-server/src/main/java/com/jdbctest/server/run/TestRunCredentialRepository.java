package com.jdbctest.server.run;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestRunCredentialRepository extends JpaRepository<TestRunCredentialEntity, UUID> { }
