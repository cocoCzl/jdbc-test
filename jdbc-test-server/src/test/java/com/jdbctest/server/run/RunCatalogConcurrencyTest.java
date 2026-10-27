package com.jdbctest.server.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jdbctest.server.driver.DriverWorkerProperties;
import com.jdbctest.server.driver.WorkerProcessRunner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RunCatalogConcurrencyTest {

    @Test
    void concurrentColdCatalogRequestsFailFastAndTheSuccessfulLoadIsCached() throws Exception {
        var worker = mock(WorkerProcessRunner.class);
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        when(worker.run(any(), any(), any())).thenAnswer(call -> {
            started.countDown();
            assertTrue(finish.await(5, TimeUnit.SECONDS));
            return new WorkerProcessRunner.Result(0, false,
                "{\"type\":\"CATALOG_DESCRIBED\",\"protocolVersion\":2}", "");
        });
        var catalog = new RunCatalogService(worker, new DriverWorkerProperties(), new ObjectMapper());
        var first = new FutureTask<>(catalog::catalog);
        Thread thread = Thread.ofVirtual().start(first);
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals("WORKER_BUSY", assertThrows(RunException.class, catalog::catalog).code());
            finish.countDown();
            assertSame(first.get(5, TimeUnit.SECONDS), catalog.catalog());
            verify(worker, times(1)).run(any(), any(), any());
        } finally {
            finish.countDown();
            thread.join(5_000);
        }
    }
}
