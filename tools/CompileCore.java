import javax.tools.ToolProvider;

/** Source launcher fallback for runtimes with jdk.compiler but no javac executable. */
public class CompileCore {
    public static void main(String[] args) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("JDK compiler required");
        int result = compiler.run(null, null, null, "-encoding", "UTF-8", "-d", "build/core-tests",
            "app/src/main/java/ru/smsbridge/app/Rules.java", "app/src/main/java/ru/smsbridge/app/SetupCode.java", "tests/RulesTest.java", "tools/ParseJava.java");
        if (result != 0) System.exit(result);
    }
}
