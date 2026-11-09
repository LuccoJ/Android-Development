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
 * classes are kept too, since compilers need the full hierarchy. Method bodies are replaced by
 * {@code throw new RuntimeException("Stub!")}, as in the SDK's android.jar, which AGP's
 * unit-test support expects.
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
        Map<String, String> superNames = new HashMap<>();
        for (Map.Entry<String, byte[]> c : classes.entrySet()) {
            ClassNode cn = new ClassNode();
            new ClassReader(c.getValue()).accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            int a = cn.access;
            for (InnerClassNode ic : cn.innerClasses) if (ic.name.equals(cn.name)) a = ic.access;
            access.put(cn.name, a);
            List<String> s = new ArrayList<>(cn.interfaces);
            if (cn.superName != null) s.add(cn.superName);
            hierarchy.put(cn.name, new ArrayList<>(s));
            superNames.put(cn.name, cn.superName);
            for (MethodNode m : cn.methods) {
                if (m.name.equals("<init>")) constructors.computeIfAbsent(cn.name, k -> new ArrayList<>()).add(m);
            }
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
                // Non-private constructors stay, as stub constructors may call them
                cn.methods.removeIf(m -> !visible(m.access) && !m.name.equals("<clinit>")
                        && !(m.name.equals("<init>") && (m.access & Opcodes.ACC_PRIVATE) == 0));
                for (MethodNode m : cn.methods) stubBody(m, cn.superName);
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

    /** Constructors of every class, from the first pass. */
    static final Map<String, List<MethodNode>> constructors = new HashMap<>();

    /**
     * Replaces a method's code: static initializers do nothing, everything else throws.
     * Constructors first call a superclass constructor, as the JVM requires and as AGP's
     * mockable-jar step expects.
     */
    static void stubBody(MethodNode m, String superName) {
        if ((m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return;
        InsnList code = new InsnList();
        int stack = 3;
        if (m.name.equals("<clinit>")) {
            code.add(new InsnNode(Opcodes.RETURN));
        } else {
            if (m.name.equals("<init>") && superName != null) {
                String superInit = superConstructor(superName);
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                int size = 1;
                for (Type arg : Type.getArgumentTypes(superInit)) {
                    code.add(defaultValue(arg));
                    size += arg.getSize();
                }
                code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, superName, "<init>", superInit, false));
                stack = Math.max(stack, size);
            }
            code.add(new TypeInsnNode(Opcodes.NEW, "java/lang/RuntimeException"));
            code.add(new InsnNode(Opcodes.DUP));
            code.add(new LdcInsnNode("Stub!"));
            code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V", false));
            code.add(new InsnNode(Opcodes.ATHROW));
        }
        m.instructions = code;
        m.tryCatchBlocks.clear();
        m.localVariables = null;
        m.visibleLocalVariableAnnotations = null;
        m.invisibleLocalVariableAnnotations = null;
        m.visibleTypeAnnotations = null;
        m.invisibleTypeAnnotations = null;
        m.maxStack = stack;
        int locals = (m.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        for (Type arg : Type.getArgumentTypes(m.desc)) locals += arg.getSize();
        m.maxLocals = locals;
    }

    /** A constructor of the superclass to call: a visible one with the fewest parameters. */
    static String superConstructor(String superName) {
        List<MethodNode> candidates = constructors.getOrDefault(superName, List.of());
        return candidates.stream()
                .filter(c -> (c.access & Opcodes.ACC_PRIVATE) == 0)
                .sorted(Comparator.comparingInt((MethodNode c) -> visible(c.access) ? 0 : 1)
                        .thenComparingInt(c -> Type.getArgumentTypes(c.desc).length))
                .map(c -> c.desc)
                .findFirst()
                .orElse("()V");
    }

    static AbstractInsnNode defaultValue(Type type) {
        switch (type.getSort()) {
            case Type.LONG: return new InsnNode(Opcodes.LCONST_0);
            case Type.FLOAT: return new InsnNode(Opcodes.FCONST_0);
            case Type.DOUBLE: return new InsnNode(Opcodes.DCONST_0);
            case Type.OBJECT: case Type.ARRAY: return new InsnNode(Opcodes.ACONST_NULL);
            default: return new InsnNode(Opcodes.ICONST_0);
        }
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
