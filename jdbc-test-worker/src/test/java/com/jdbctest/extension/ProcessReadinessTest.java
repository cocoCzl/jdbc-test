package com.jdbctest.extension;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ProcessReadinessTest {

    @Test
    void timeoutKillsSilentChildBeforeClosingReader() throws Exception {
        Process child = child("silent");
        try {
            assertThrows(TimeoutException.class,
                () -> ProcessReadiness.awaitLine(child, Duration.ofMillis(200)));
            assertTrue(child.waitFor(2, TimeUnit.SECONDS));
            assertFalse(child.isAlive());
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    void successfulReadDoesNotKillChild() throws Exception {
        Process child = child("ready");
        try {
            assertEquals("READY", ProcessReadiness.awaitLine(child, Duration.ofSeconds(5)));
            assertTrue(child.isAlive());
        } finally {
            child.destroyForcibly();
            assertTrue(child.waitFor(2, TimeUnit.SECONDS));
        }
    }

    private Process child(String mode) throws Exception {
        return new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"), SilentChild.class.getName(),
            mode).start();
    }

    public static class SilentChild {

        public static void main(String[] args) throws Exception {
            if ("ready".equals(args[0])) {
                System.out.println("READY");
                System.out.flush();
            }
            Thread.sleep(Duration.ofMinutes(1));
        }
    }
}
