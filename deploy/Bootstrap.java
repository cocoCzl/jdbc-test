import java.nio.file.*;
import java.util.*;
import com.jdbctest.server.deploy.LaunchProcesses;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * JDK-only bootstrap. All configuration/launch logic lives in the existing Server tool.
 */
class Bootstrap {

    private static final Path ROOT = Path.of(".").toAbsolutePath().normalize();

    private static String version(LaunchProcesses processes, String executable, String... args)
        throws Exception {
        Process child = processes.start(LaunchProcesses.command(executable, args), ROOT,
            LaunchProcesses.basicEnvironment(), true);
        String value = new String(child.getInputStream().readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8).strip();
      if (child.waitFor() != 0) {
        throw new Exception("Dependency check failed: " + executable);
      }
        return value;
    }

    public static void main(String[] args) {
        int status = 0;
        try (var processes = new LaunchProcesses()) {
          if (Runtime.version().feature() != 21) {
            throw new Exception("Install JDK 21 and put its java on PATH.");
          }
          if (args.length == 0 || !List.of("dev", "build").contains(args[0])) {
            throw new Exception(
                "Source commands: ./jdbc-test dev | build. Runtime bundle: ./jdbc-test start.");
          }
            String node = version(processes, "node", "--version");
            String[] numbers = node.replaceFirst("^v", "").split("\\.");
          if (Integer.parseInt(numbers[0]) < 22 || (Integer.parseInt(numbers[0]) == 22
              && Integer.parseInt(numbers[1]) < 12)) {
            throw new Exception(
                "Node >=22.12 and npm are required on the build/development machine.");
          }
            String npm = version(processes,
                System.getProperty("os.name").startsWith("Windows") ? "npm.cmd" : "npm",
                "--version");
            if (args[0].equals("build")) {
                String python = version(processes,
                    System.getProperty("os.name").startsWith("Windows") ? "python" : "python3",
                    "--version");
                String[] pv = python.replaceFirst("^Python ", "").split("\\.");
              if (Integer.parseInt(pv[0]) < 3 || (Integer.parseInt(pv[0]) == 3
                  && Integer.parseInt(pv[1]) < 9)) {
                throw new Exception(
                    "Build packaging requires Python >=3.9; runtime start does not require Python.");
              }
            }
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var project = factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile())
                .getDocumentElement();
            String projectVersion = null;
          for (var child = project.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeName().equals("version")) {
              projectVersion = child.getTextContent().strip();
            }
          }
          if (projectVersion == null || !projectVersion.matches("[a-zA-Z0-9._-]+")) {
            throw new Exception("Cannot determine exact current Maven project version.");
          }
            System.out.println(
                "Checking/building current Java and Worker modules (tests are not executed)...");
            processes.run(LaunchProcesses.command(
                    Path.of(System.getProperty("os.name").startsWith("Windows") ? "mvnw.cmd" : "mvnw")
                        .toAbsolutePath().toString(), "-B", "-DskipTests", "install"), ROOT,
                LaunchProcesses.basicEnvironment());
            Path jar = Path.of(
                    "jdbc-test-server/target/jdbc-test-server-" + projectVersion + "-exec.jar")
                .toAbsolutePath();
          if (!Files.isRegularFile(jar)) {
            throw new Exception("Current Server artifact missing; no stale artifact fallback.");
          }
            var launch = new ArrayList<>(
                List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Djdbc.test.version=" + projectVersion,
                    "-Djdbc.test.node=" + node + ":" + npm + ":" + System.getProperty("os.name")
                        + ":" + System.getProperty("os.arch"),
                    "-Dloader.main=com.jdbctest.server.deploy.UnifiedLauncher", "-cp",
                    jar.toString(), "org.springframework.boot.loader.launch.PropertiesLauncher"));
            launch.addAll(List.of(args));
            processes.run(launch, ROOT, LaunchProcesses.basicEnvironment());
        } catch (Exception exception) {
            System.err.println(exception.getMessage() == null
                ? "Bootstrap failed. Check JDK 21, Node/npm, build dependencies and permissions."
                : exception.getMessage());
            status = 1;
        }
      if (status != 0) {
        System.exit(status);
      }
    }
}
