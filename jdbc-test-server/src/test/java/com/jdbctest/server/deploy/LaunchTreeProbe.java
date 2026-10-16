package com.jdbctest.server.deploy;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Test-only process tree; never connects to a database or opens service ports.
 */
public class LaunchTreeProbe {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.exit(19);
            return;
        }
        Path directory = Path.of(args[1]);
        Files.writeString(directory.resolve(args[0] + ".pid"),
            Long.toString(ProcessHandle.current().pid()));
        if (args[0].equals("parent")) {
            new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), LaunchTreeProbe.class.getName(),
                "child", directory.toString())
                .inheritIO().start();
        }
        Thread.sleep(60000);
    }
}
