package mcp.remastered.engine.pipeline.patch;

import mcp.remastered.engine.util.Tree;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.objectweb.asm.*;

/** Keeps files belonging to older game versions out of the current compilation. */
public final class SourceReconciler {
    private SourceReconciler() {}

    public record Result(int renamed, int archived, Path archive) {}

    /** Reads source ownership from original class metadata, not guesses about package names. */
    public static Set<String> sourcePaths(Path versions) throws IOException {
        Set<String> result = new HashSet<>();
        if (!Files.isDirectory(versions)) return result;
        try (var paths = Files.walk(versions)) {
            for (Path jar : paths.filter(p -> p.getFileName().toString().equals("client.jar")).toList()) {
                try (var zip = new ZipFile(jar.toFile())) {
                    var entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        var entry = entries.nextElement();
                        if (!entry.getName().endsWith(".class")) continue;
                        try (var in = zip.getInputStream(entry)) {
                            var reader = new ClassReader(in);
                            String name = reader.getClassName();
                            int slash = name.lastIndexOf('/');
                            String prefix = slash < 0 ? "" : name.substring(0, slash + 1);
                            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                                @Override public void visitSource(String source, String debug) {
                                    if (source != null && source.endsWith(".java") && !source.contains("/") && !source.contains("\\"))
                                        result.add(prefix + source);
                                }
                            }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
                        }
                    }
                }
            }
        }
        return result;
    }

    /** Unknown user-created files are preserved. Known obsolete files are moved, never discarded. */
    public static Result reconcile(Path project, Path baseline, Set<String> managed, Path archives) throws IOException {
        Set<String> expected = new HashSet<>(Tree.relativeFiles(baseline, ".java"));
        Map<String, String> folded = new HashMap<>();
        for (String name : expected) {
            String previous = folded.put(name.toLowerCase(Locale.ROOT), name);
            if (previous != null) throw new IOException("Case-colliding baseline sources: " + previous + " / " + name);
        }
        int renamed = 0, archived = 0;
        Path archive = null;
        for (String name : Tree.relativeFiles(project, ".java")) {
            if (expected.contains(name)) continue;
            Path file = project.resolve(name);
            String canonical = folded.get(name.toLowerCase(Locale.ROOT));
            if (canonical != null && managed.contains(name)) {
                Path target = project.resolve(canonical);
                if (!Files.exists(target) || Files.isSameFile(file, target)) {
                    // A temporary name forces a case-only rename on Windows/macOS too.
                    Path temporary = Files.createTempFile(file.getParent(), ".mcp-case-", ".tmp");
                    Files.delete(temporary);
                    Files.move(file, temporary);
                    try {
                        Files.createDirectories(target.getParent());
                        Files.move(temporary, target);
                    } catch (IOException failure) {
                        Files.move(temporary, file);
                        throw failure;
                    }
                    renamed++;
                    continue;
                }
            }
            if (!managed.contains(name)) continue;
            if (archive == null) {
                Files.createDirectories(archives);
                archive = Files.createTempDirectory(archives, "orphaned-sources-");
            }
            Path target = archive.resolve(name);
            Files.createDirectories(target.getParent());
            Files.move(file, target);
            archived++;
        }
        return new Result(renamed, archived, archive);
    }
}
