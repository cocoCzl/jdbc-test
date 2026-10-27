package com.jdbctest.server.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:validation-races;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "jdbc-test.security.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class DriverValidationRaceTest {

    @Autowired
    DriverService service;
    @Autowired
    JdbcDriverPackageRepository packages;
    @MockitoBean
    DriverValidationProcess worker;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void configurationEditInvalidatesBothOldSuccessAndFailure(boolean success) throws Exception {
        var driver = packages.saveAndFlush(new JdbcDriverPackageEntity("fixture", "fixture", "1",
            "fixture.A", DriverSourceType.UPLOAD));
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        when(worker.validate(any(), any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            started.countDown();
            assertTrue(finish.await(10, TimeUnit.SECONDS));
            if (!success) {
                throw new DriverException(HttpStatus.UNPROCESSABLE_CONTENT, "DRIVER_VALIDATION_FAILED", "fixture");
            }
            return result("fixture.A");
        });
        var validation = new FutureTask<>(() -> service.validate(driver.getId()));
        Thread thread = Thread.ofVirtual().start(validation);
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS));
            service.update(driver.getId(), new UpdateDriverRequest("fixture", "fixture", "1", "fixture.B", null));
            // Also catches A -> B -> A: comparing the class alone is insufficient.
            service.update(driver.getId(), new UpdateDriverRequest("fixture", "fixture", "1", "fixture.A", null));
            finish.countDown();
            var failure = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                () -> validation.get(10, TimeUnit.SECONDS));
            assertEquals("DRIVER_VALIDATION_STALE", ((DriverException) failure.getCause()).code());
            assertEquals(DriverPackageStatus.PENDING_VALIDATION, packages.findById(driver.getId()).orElseThrow().getStatus());
        } finally {
            finish.countDown();
            thread.join(10_000);
            packages.deleteById(driver.getId());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void newestValidationWinsAndDeletionDoesNotResurrectTheRow(boolean delete) throws Exception {
        var driver = packages.saveAndFlush(new JdbcDriverPackageEntity("fixture", "fixture", "1",
            "fixture.A", DriverSourceType.UPLOAD));
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(worker.validate(any(), any())).thenAnswer(call -> {
            if (calls.incrementAndGet() == 1) {
                started.countDown();
                assertTrue(finish.await(10, TimeUnit.SECONDS));
            }
            return result("fixture.A");
        });
        var first = new FutureTask<>(() -> service.validate(driver.getId()));
        Thread thread = Thread.ofVirtual().start(first);
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS));
            if (delete) {
                service.delete(driver.getId());
            } else {
                service.validate(driver.getId());
            }
            finish.countDown();
            var failure = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                () -> first.get(10, TimeUnit.SECONDS));
            assertEquals("DRIVER_VALIDATION_STALE", ((DriverException) failure.getCause()).code());
            if (delete) {
                assertFalse(packages.existsById(driver.getId()));
            } else {
                assertEquals(DriverPackageStatus.VALIDATED, packages.findById(driver.getId()).orElseThrow().getStatus());
            }
        } finally {
            finish.countDown();
            thread.join(10_000);
            packages.deleteAll();
        }
    }

    @org.junit.jupiter.api.Test
    void validationNeutralMetadataEditPreservesTheValidatedClassAndNewMetadata() throws Exception {
        var driver = packages.saveAndFlush(new JdbcDriverPackageEntity("before", "fixture", "1",
            "fixture.A", DriverSourceType.UPLOAD));
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        when(worker.validate(any(), any())).thenAnswer(call -> {
            assertEquals("fixture.A", call.getArgument(0));
            assertTrue(((List<?>) call.getArgument(1)).isEmpty());
            started.countDown();
            assertTrue(finish.await(10, TimeUnit.SECONDS));
            return result("fixture.A");
        });
        var validation = new FutureTask<>(() -> service.validate(driver.getId()));
        Thread thread = Thread.ofVirtual().start(validation);
        var providers = new com.jdbctest.catalog.DataSourceProviders(
            new com.jdbctest.catalog.DataSourceProviders.Provider("fixture.DataSource", null, null, null), null, null);
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS));
            service.update(driver.getId(), new UpdateDriverRequest("after", "new vendor", "2",
                "fixture.A", "jdbc:fixture:new", providers, "postgresql"));
            finish.countDown();
            assertEquals(DriverPackageStatus.VALIDATED, validation.get(10, TimeUnit.SECONDS).status());
            var persisted = packages.findById(driver.getId()).orElseThrow();
            assertEquals(DriverPackageStatus.VALIDATED, persisted.getStatus());
            assertEquals("after", persisted.getName());
            assertEquals("new vendor", persisted.getVendor());
            assertEquals("2", persisted.getVersion());
            assertEquals("jdbc:fixture:new", persisted.getUrlPrefix());
            assertEquals(providers, persisted.getDataSources());
            assertEquals("postgresql", persisted.getDefaultSqlDialect());
        } finally {
            finish.countDown();
            thread.join(10_000);
            packages.deleteById(driver.getId());
        }
    }

    private static DriverValidationResponse result(String driverClass) {
        return new DriverValidationResponse(DriverPackageStatus.VALIDATED, driverClass, 1, 0, true,
            List.of(), null);
    }
}
