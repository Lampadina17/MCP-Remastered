package mcp.remastered.engine.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The handful of file system operations the pipeline needs over and over again.
 *
 * <p>They are gathered here so that the rest of the code does not have to repeat the long
 * {@code Files.walk} dances, and so that the behaviour on a file system that refuses an operation
 * is the same everywhere.</p>
 */
public final class Tree {

    private Tree() {
    }

    /**
     * Copies a whole folder.
     *
     * <p>The target is created if it is missing, and the files that are already there are
     * replaced.</p>
     *
     * @param from the folder to read
     * @param to   the folder to write
     * @throws IOException when a file cannot be copied
     */
    public static void copy(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) {
            return;
        }
        Files.createDirectories(to);
        try (Stream<Path> stream = Files.walk(from)) {
            List<Path> entries = stream.sorted(Comparator.comparing(Path::toString)).toList();
            for (Path entry : entries) {
                Path target = to.resolve(from.relativize(entry).toString());
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(target);
                } else {
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(entry, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * Copies the Java sources of a whole folder.
     *
     * <p>Only the {@code .java} files are copied: the decompiler saves the resources of the jar
     * next to the sources, and those belong in the resources of the project, not in its
     * sources.</p>
     *
     * @param from the folder to read
     * @param to   the folder to write
     * @throws IOException when a file cannot be copied
     */
    public static void copyJava(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) {
            return;
        }
        for (Path file : files(from, ".java")) {
            Path target = to.resolve(from.relativize(file).toString());
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Deletes a folder and everything it holds.
     *
     * <p>A folder that is not there is not an error: the operation is meant to be idempotent.</p>
     *
     * @param path the folder to remove
     * @throws IOException when a file cannot be removed
     */
    public static void delete(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Puts a folder in the place of another one.
     *
     * <p>The old folder is removed first, so that the files that are gone in the new one do not
     * survive the replacement.</p>
     *
     * @param from the folder to read
     * @param to   the folder to write
     * @throws IOException when the folders cannot be swapped
     */
    public static void replace(Path from, Path to) throws IOException {
        delete(to);
        copy(from, to);
    }

    /**
     * Lists the files of a folder that end with a given suffix.
     *
     * @param root   the folder to walk
     * @param suffix the suffix the files have to end with, the dot included
     * @return the files, sorted by their path
     * @throws IOException when the folder cannot be walked
     */
    public static List<Path> files(Path root, String suffix) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(suffix)).sorted(Comparator.comparing(Path::toString)).toList();
        }
    }

    /**
     * Lists the files of a folder, relative to it.
     *
     * @param root   the folder to walk
     * @param suffix the suffix the files have to end with, the dot included
     * @return the paths, relative to the folder and using slashes
     * @throws IOException when the folder cannot be walked
     */
    public static List<String> relativeFiles(Path root, String suffix) throws IOException {
        List<String> names = new ArrayList<>();
        for (Path file : files(root, suffix)) {
            names.add(root.relativize(file).toString().replace('\\', '/'));
        }
        return names;
    }

    /**
     * Reads the lines of a text file.
     *
     * @param file the file to read
     * @return the lines
     * @throws IOException when the file cannot be read
     */
    public static List<String> lines(Path file) throws IOException {
        return read(file).lines();
    }

    /**
     * A text file, read as lines.
     *
     * @param lines           the lines of the file
     * @param trailingNewline whether the file ended with a line break
     */
    public record Text(List<String> lines, boolean trailingNewline) {
    }

    /**
     * Reads a text file, keeping track of the line break at its end.
     *
     * @param file the file to read
     * @return the content of the file
     * @throws IOException when the file cannot be read
     */
    public static Text read(Path file) throws IOException {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        boolean trailing = text.endsWith("\n");
        return new Text(text.lines().toList(), trailing);
    }

    /**
     * Writes the lines of a text file.
     *
     * @param file            the file to write
     * @param lines           the lines of the file
     * @param trailingNewline whether the file has to end with a line break
     * @throws IOException when the file cannot be written
     */
    public static void write(Path file, List<String> lines, boolean trailingNewline) throws IOException {
        String text = String.join("\n", lines);
        if (trailingNewline) {
            text = text + "\n";
        }
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }
}
