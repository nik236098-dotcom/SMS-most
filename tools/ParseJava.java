import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import com.sun.source.util.JavacTask;

public class ParseJava {
    public static void main(String[] args) throws Exception {
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
        try(StandardJavaFileManager fm=compiler.getStandardFileManager(diagnostics,null,null)) {
            List<java.io.File> files=new ArrayList<>();
            try(var paths=Files.walk(Path.of(args[0]))) {paths.filter(p->p.toString().endsWith(".java")).forEach(p->files.add(p.toFile()));}
            JavacTask task=(JavacTask)compiler.getTask(null,fm,diagnostics,List.of("-proc:none"),null,fm.getJavaFileObjectsFromFiles(files));task.parse();
            boolean failed=false;for(var d:diagnostics.getDiagnostics())if(d.getKind()==Diagnostic.Kind.ERROR){System.err.println(d);failed=true;}
            if(failed)System.exit(1);System.out.println("Syntax parsed: "+files.size()+" Java files (no Android type checking)");
        }
    }
}
