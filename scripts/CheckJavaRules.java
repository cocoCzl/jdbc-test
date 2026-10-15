import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

/** Syntax-only development check. No attribution, application classes or annotation processors. */
public final class CheckJavaRules {

  private CheckJavaRules() {
  }

  public static void main(String[] args) {
    try {
      System.exit(check(args));
    } catch (Exception exception) {
      System.err.println("Java rule check could not run: " + exception);
      System.exit(2);
    }
  }

  private static int check(String[] args) throws Exception {
    var compiler = ToolProvider.getSystemJavaCompiler();
    if (Runtime.version().feature() != 21 || compiler == null || args.length == 0) {
      throw new IllegalArgumentException("JDK 21 and at least one Java source are required");
    }
    var paths = new ArrayList<Path>();
    var seen = new HashSet<Path>();
    for (String arg : args) {
      Path path = Path.of(arg).toAbsolutePath().normalize();
      if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)
          || !path.toString().endsWith(".java") || !seen.add(path)) {
        throw new IllegalArgumentException("Invalid or duplicate Java source: " + path);
      }
      paths.add(path);
    }
    var diagnostics = new DiagnosticCollector<JavaFileObject>();
    var findings = new ArrayList<String>();
    try (var files = compiler.getStandardFileManager(diagnostics, Locale.ROOT,
        StandardCharsets.UTF_8)) {
      var task = (JavacTask) compiler.getTask(null, files, diagnostics,
          List.of("--release", "21", "-proc:none"), null,
          files.getJavaFileObjectsFromPaths(paths));
      var trees = Trees.instance(task);
      for (CompilationUnitTree unit : task.parse()) {
        new Scanner(unit, trees, findings).scan(unit, null);
      }
    }
    boolean invalid = false;
    for (var diagnostic : diagnostics.getDiagnostics()) {
      if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
        invalid = true;
        System.err.println(diagnostic);
      }
    }
    if (invalid) {
      return 2;
    }
    findings.forEach(System.out::println);
    return findings.isEmpty() ? 0 : 1;
  }

  private static final class Scanner extends TreePathScanner<Void, Void> {

    private final CompilationUnitTree unit;
    private final Trees trees;
    private final List<String> findings;

    private Scanner(CompilationUnitTree unit, Trees trees, List<String> findings) {
      this.unit = unit;
      this.trees = trees;
      this.findings = findings;
    }

    @Override
    public Void visitImport(ImportTree node, Void unused) {
      if (node.getQualifiedIdentifier().toString().endsWith(".*")) {
        report("wildcard-import", node, node.toString().strip());
      }
      return super.visitImport(node, unused);
    }

    @Override
    public Void visitCatch(CatchTree node, Void unused) {
      if (node.getBlock().getStatements().isEmpty()) {
        report("empty-catch", node, node.getParameter().toString());
      }
      return super.visitCatch(node, unused);
    }

    @Override
    public Void visitAnnotation(AnnotationTree node, Void unused) {
      String name = node.getAnnotationType().toString();
      if ((name.equals("SuppressWarnings") || name.equals("java.lang.SuppressWarnings"))
          && node.getArguments().stream().anyMatch(Scanner::containsAll)) {
        report("suppress-all", node, "SuppressWarnings(all)");
      }
      return super.visitAnnotation(node, unused);
    }

    private static boolean containsAll(Tree node) {
      return switch (node) {
        case LiteralTree literal -> "all".equals(literal.getValue());
        case AssignmentTree assignment -> containsAll(assignment.getExpression());
        case ParenthesizedTree parentheses -> containsAll(parentheses.getExpression());
        case NewArrayTree array -> array.getInitializers() != null
            && array.getInitializers().stream().anyMatch(Scanner::containsAll);
        default -> false;
      };
    }

    private void report(String rule, Tree node, String detail) {
      long position = trees.getSourcePositions().getStartPosition(unit, node);
      long line = unit.getLineMap().getLineNumber(position);
      findings.add("{\"path\":" + quote(Path.of(unit.getSourceFile().toUri()).toString())
          + ",\"rule\":" + quote(rule) + ",\"owner\":" + quote(owner())
          + ",\"detail\":" + quote(detail) + ",\"line\":" + line + "}");
    }

    private String owner() {
      var owners = new ArrayList<String>();
      for (TreePath path = getCurrentPath(); path != null; path = path.getParentPath()) {
        if (path.getLeaf() instanceof MethodTree method) {
          owners.add(method.getName() + "(" + String.join(",", method.getParameters().stream()
              .map(parameter -> parameter.getType().toString()).toList()) + ")");
        } else if (path.getLeaf() instanceof ClassTree type) {
          owners.add(type.getSimpleName().toString());
        }
      }
      java.util.Collections.reverse(owners);
      return String.join("/", owners);
    }
  }

  private static String quote(String value) {
    var result = new StringBuilder("\"");
    for (int i = 0; i < value.length(); i++) {
      char character = value.charAt(i);
      switch (character) {
        case '"' -> result.append("\\\"");
        case '\\' -> result.append("\\\\");
        default -> {
          if (character < 0x20) {
            result.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
          } else {
            result.append(character);
          }
        }
      }
    }
    return result.append('"').toString();
  }
}
