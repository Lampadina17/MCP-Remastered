package mcp.remastered.engine.pipeline.patch.bytecode;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Applies the patches of the bytecode to the jar of the client.
 *
 * <p>The result is a jar the game can run and the decompiler can read: the classes are the ones
 * of the project, the resources are the ones of the original jar.</p>
 */
public final class BytecodePatcher {

    private BytecodePatcher() {
    }

    /**
     * Patches the classes of a jar.
     *
     * @param source  the jar to read
     * @param patches the patches to apply
     * @param output  the jar to write
     * @return the number of classes that were changed
     * @throws IOException when the jar cannot be read or written
     */
    public static int patch(Path source, List<BytecodePatch> patches, Path output) throws IOException {
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = output.resolveSibling(output.getFileName() + ".part");

        int changed = 0;
        try (ZipFile zip = new ZipFile(source.toFile()); ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(temporary))) {

            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                // Modified classes cannot retain the original publisher's JAR signature.
                String upper = entry.getName().toUpperCase(java.util.Locale.ROOT);
                if (upper.startsWith("META-INF/") && upper.indexOf('/', 9) < 0
                        && (upper.endsWith(".SF") || upper.endsWith(".RSA")
                        || upper.endsWith(".DSA") || upper.endsWith(".EC")
                        || upper.substring(9).startsWith("SIG-"))) {
                    continue;
                }

                ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTime(entry.getTime());
                out.putNextEntry(copy);

                if (entry.getName().endsWith(".class")) {
                    byte[] bytes = zip.getInputStream(entry).readAllBytes();
                    byte[] patched = apply(patches, bytes);
                    if (patched != null) {
                        bytes = patched;
                        changed++;
                    }
                    out.write(bytes);
                } else {
                    try (InputStream in = zip.getInputStream(entry)) {
                        in.transferTo(out);
                    }
                }
                out.closeEntry();
            }
        }

        Files.move(temporary, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return changed;
    }

    /**
     * Applies the patches to the bytes of a class.
     *
     * @param patches the patches to apply
     * @param bytes   the class to read
     * @return the patched class, or {@code null} when nothing changed
     */
    private static byte[] apply(List<BytecodePatch> patches, byte[] bytes) {
        ClassNode clazz = new ClassNode();
        new ClassReader(bytes).accept(clazz, 0);

        boolean changed = false;
        for (BytecodePatch patch : patches) {
            String name = patch.className();
            if (name != null && !name.equals(clazz.name)) {
                continue;
            }
            if (patch.apply(clazz)) {
                changed = true;
            }
        }

        if (!changed) {
            return null;
        }

        ClassWriter writer = new ClassWriter(0);
        clazz.accept(writer);
        return writer.toByteArray();
    }
}
