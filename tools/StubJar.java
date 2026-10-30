import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * Builds an SDK-style android.jar: the public/protected API of an AOSP framework jar plus the
 * java.* / javax.* / org.w3c / org.xml API of the running OpenJDK. Non-public supertypes of kept
 * classes are kept too, since compilers need the full hierarchy.
 * Optionally also writes a framework.aidl declaring the public Parcelable classes, for the AIDL compiler.
 * Usage: StubJar out.jar framework.jar [framework.aidl]
 */
public class StubJar {
    static final String[] JDK_MODULES = {"java.base", "java.logging", "java.sql", "java.xml"};
    static final String[] JDK_PREFIXES = {"java/", "javax/", "org/w3c/", "org/xml/"};

    static boolean visible(int access) {
        return (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0;
    }

    public static void main(String[] args) throws Exception {
        Map<String, byte[]> classes = new HashMap<>();   // internal name -> bytes
        Map<String, byte[]> resources = new LinkedHashMap<>();

        try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(new FileInputStream(args[1])))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                String name = e.getName();
                if (e.isDirectory() || name.startsWith("META-INF/") || name.endsWith("module-info.class")) continue;
                // java.* comes from the JDK below, so drop the few stray copies in the framework jar
                if (Arrays.stream(JDK_PREFIXES).anyMatch(name::startsWith) && name.endsWith(".class")) continue;
                byte[] data = in.readAllBytes();
                if (name.endsWith(".class")) classes.put(name.substring(0, name.length() - 6), data);
                else resources.put(name, data);
            }
        }

        FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
        for (String module : JDK_MODULES) {
            Path root = jrt.getPath("/modules", module);
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path p : (Iterable<Path>) paths::iterator) {
                    String rel = root.relativize(p).toString();
                    if (!rel.endsWith(".class") || rel.equals("module-info.class")) continue;
                    if (Arrays.stream(JDK_PREFIXES).noneMatch(rel::startsWith)) continue;
                    classes.put(rel.substring(0, rel.length() - 6), Files.readAllBytes(p));
                }
            }
        }

        // Effective access (inner classes carry theirs in the InnerClasses attribute) and supertypes
        Map<String, Integer> access = new HashMap<>();
        Map<String, List<String>> supers = new HashMap<>();
        Map<String, List<String>> hierarchy = new HashMap<>();
        for (Map.Entry<String, byte[]> c : classes.entrySet()) {
            ClassNode cn = new ClassNode();
            new ClassReader(c.getValue()).accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            int a = cn.access;
            for (InnerClassNode ic : cn.innerClasses) if (ic.name.equals(cn.name)) a = ic.access;
            access.put(cn.name, a);
            List<String> s = new ArrayList<>(cn.interfaces);
            if (cn.superName != null) s.add(cn.superName);
            hierarchy.put(cn.name, new ArrayList<>(s));
            if (cn.outerClass != null) s.add(cn.outerClass);
            int dollar = cn.name.lastIndexOf('$');
            if (dollar > 0) s.add(cn.name.substring(0, dollar));
            supers.put(cn.name, s);
        }

        Set<String> keep = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>();
        for (String name : classes.keySet()) if (visible(access.get(name))) todo.add(name);
        while (!todo.isEmpty()) {
            String name = todo.pop();
            if (!classes.containsKey(name) || !keep.add(name)) continue;
            todo.addAll(supers.get(name));
        }

        try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(args[0])))) {
            for (String name : new TreeSet<>(keep)) {
                ClassNode cn = new ClassNode();
                new ClassReader(classes.get(name)).accept(cn, 0);
                cn.fields.removeIf(f -> !visible(f.access));
                cn.methods.removeIf(m -> !visible(m.access));
                cn.innerClasses.removeIf(ic -> !keep.contains(ic.name));
                cn.nestMembers = null;
                cn.nestHostClass = null;
                cn.permittedSubclasses = null;
                ClassWriter cw = new ClassWriter(0);
                cn.accept(cw);
                out.putNextEntry(new ZipEntry(name + ".class"));
                out.write(cw.toByteArray());
                out.closeEntry();
            }
            for (Map.Entry<String, byte[]> r : resources.entrySet()) {
                out.putNextEntry(new ZipEntry(r.getKey()));
                out.write(r.getValue());
                out.closeEntry();
            }
        }
        if (args.length > 2) {
            try (PrintWriter aidl = new PrintWriter(args[2], "UTF-8")) {
                for (String name : new TreeSet<>(keep)) {
                    if (name.contains("$") || !name.startsWith("android/") || !visible(access.get(name))) continue;
                    if ((access.get(name) & Opcodes.ACC_INTERFACE) != 0) continue;
                    if (implementsParcelable(name, hierarchy, new HashSet<>())) {
                        aidl.println("parcelable " + name.replace('/', '.') + ";");
                    }
                }
            }
        }
        System.out.println("kept " + keep.size() + " of " + classes.size() + " classes");
    }

    static boolean implementsParcelable(String name, Map<String, List<String>> hierarchy, Set<String> seen) {
        if (name.equals("android/os/Parcelable")) return true;
        if (!seen.add(name) || !hierarchy.containsKey(name)) return false;
        for (String parent : hierarchy.get(name)) {
            if (implementsParcelable(parent, hierarchy, seen)) return true;
        }
        return false;
    }
}
