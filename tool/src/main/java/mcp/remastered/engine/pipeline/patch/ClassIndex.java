package mcp.remastered.engine.pipeline.patch;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads the structure of a Minecraft client jar with ASM.
 *
 * <p>This is the building block of the differential patching system: two versions of the game are
 * compared class by class, so that the pipeline knows exactly which parts of the code base
 * actually changed and which parts can be carried over untouched.</p>
 *
 * <p>For every class the reader keeps:</p>
 * <ul>
 *   <li>the super class and the implemented interfaces,</li>
 *   <li>the fields, and</li>
 *   <li>the methods together with a hash of their bytecode.</li>
 * </ul>
 */
public final class ClassIndex {

    /**
     * The structural fingerprint of a single class.
     */
    public static final class ClassInfo {
        private final String name;
        private final String superName;
        private final List<String> interfaces;
        private final Map<String, String> fields;
        private final Map<String, String> methods;

        ClassInfo(String name, String superName, List<String> interfaces, Map<String, String> fields, Map<String, String> methods) {
            this.name = name;
            this.superName = superName;
            this.interfaces = interfaces;
            this.fields = fields;
            this.methods = methods;
        }

        public String name() {
            return name;
        }

        public String superName() {
            return superName;
        }

        public List<String> interfaces() {
            return interfaces;
        }

        /**
         * The fields of the class, keyed by {@code name descriptor}.
         */
        public Map<String, String> fields() {
            return fields;
        }

        /**
         * The methods of the class, keyed by {@code name descriptor}.
         */
        public Map<String, String> methods() {
            return methods;
        }

        /**
         * A stable fingerprint of the whole class.
         *
         * @return the fingerprint as a hexadecimal string
         */
        public String fingerprint() {
            StringBuilder builder = new StringBuilder();
            builder.append(superName).append('|');
            builder.append(String.join(",", interfaces)).append('|');
            fields.forEach((key, value) -> builder.append(key).append(':').append(value).append(';'));
            builder.append('|');
            methods.forEach((key, value) -> builder.append(key).append(':').append(value).append(';'));
            return hash(builder.toString());
        }
    }

    private final Map<String, ClassInfo> classes;

    private ClassIndex(Map<String, ClassInfo> classes) {
        this.classes = classes;
    }

    /**
     * Indexes every class of a jar.
     *
     * @param jar the jar to read
     * @return the index
     * @throws IOException when the jar cannot be read
     */
    public static ClassIndex of(Path jar) throws IOException {
        Map<String, ClassInfo> classes = new LinkedHashMap<>();

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            List<? extends ZipEntry> entries = Collections.list(zip.entries());
            entries.sort((a, b) -> a.getName().compareTo(b.getName()));

            for (ZipEntry entry : entries) {
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    ClassInfo info = read(in.readAllBytes());
                    if (info != null) {
                        classes.put(info.name(), info);
                    }
                }
            }
        }

        return new ClassIndex(classes);
    }

    /**
     * All indexed classes, keyed by their internal name.
     */
    public Map<String, ClassInfo> classes() {
        return classes;
    }

    public int size() {
        return classes.size();
    }

    private static ClassInfo read(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.SKIP_FRAMES);

        List<String> interfaces = new ArrayList<>();
        if (node.interfaces != null) {
            interfaces.addAll(node.interfaces);
        }

        Map<String, String> fields = new LinkedHashMap<>();
        if (node.fields != null) {
            for (FieldNode field : node.fields) {
                fields.put(field.name + " " + field.desc, field.desc);
            }
        }

        Map<String, String> methods = new LinkedHashMap<>();
        if (node.methods != null) {
            for (MethodNode method : node.methods) {
                methods.put(method.name + method.desc, methodHash(method));
            }
        }

        return new ClassInfo(node.name, node.superName, interfaces, fields, methods);
    }

    /**
     * Hashes the bytecode of a method.
     *
     * <p>The hash is computed over the instruction stream, so a method whose body changed is
     * detected even when its signature stayed the same.</p>
     */
    private static String methodHash(MethodNode method) {
        StringBuilder builder = new StringBuilder();
        builder.append(method.name).append(method.desc).append(':');

        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
            builder.append(instruction.getOpcode());
            if (instruction instanceof org.objectweb.asm.tree.FieldInsnNode field) {
                builder.append(field.owner).append('.').append(field.name).append(field.desc);
            } else if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call) {
                builder.append(call.owner).append('.').append(call.name).append(call.desc);
            } else if (instruction instanceof org.objectweb.asm.tree.TypeInsnNode type) {
                builder.append(type.desc);
            } else if (instruction instanceof org.objectweb.asm.tree.LdcInsnNode ldc) {
                builder.append('=').append(String.valueOf(ldc.cst));
            } else if (instruction instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode indy) {
                builder.append(indy.name).append(indy.desc);
            }
            builder.append(';');
        }
        return hash(builder.toString());
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is not available in this JVM", e);
        }
    }
}
