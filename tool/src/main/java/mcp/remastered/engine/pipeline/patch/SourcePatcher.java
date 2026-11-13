package mcp.remastered.engine.pipeline.patch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The differential patching layer of the pipeline.
 *
 * <p>Its purpose is to let work done on one version of the game survive the jump to the next
 * one. Two complementary levels are used:</p>
 *
 * <ul>
 *   <li>the {@link ClassDiff} tells which classes and methods changed at the bytecode level,</li>
 *   <li>the {@link LineDiff} tells which lines of the decompiled sources changed.</li>
 * </ul>
 *
 * <p>Together they answer the question that matters when a new version arrives: of everything
 * that was customised for the old version, what still applies and what has to be revisited.</p>
 */
public final class SourcePatcher {

    /**
     * The verdict for a single file of the sources.
     */
    public enum Status {
        /**
         * The file is identical in both versions.
         */
        UNCHANGED,
        /**
         * The file changed but the change does not touch any customised region.
         */
        PORTABLE,
        /**
         * The file changed in a region that overlaps a customisation.
         */
        CONFLICT,
        /**
         * The file exists only in the old version.
         */
        ORPHANED,
        /**
         * The file exists only in the new version.
         */
        NEW
    }

    /**
     * The outcome of the analysis for a single file.
     */
    public static final class FileResult {
        private final String path;
        private final Status status;
        private final List<LineDiff.Hunk> hunks;

        FileResult(String path, Status status, List<LineDiff.Hunk> hunks) {
            this.path = path;
            this.status = status;
            this.hunks = hunks;
        }

        public String path() {
            return path;
        }

        public Status status() {
            return status;
        }

        public List<LineDiff.Hunk> hunks() {
            return hunks;
        }

        @Override
        public String toString() {
            return status + " " + path;
        }
    }

    /**
     * The outcome of the analysis for a whole source tree.
     */
    public static final class Report {
        private final List<FileResult> results;
        private final ClassDiff classDiff;

        Report(List<FileResult> results, ClassDiff classDiff) {
            this.results = results;
            this.classDiff = classDiff;
        }

        public List<FileResult> results() {
            return results;
        }

        public ClassDiff classDiff() {
            return classDiff;
        }

        public long count(Status status) {
            return results.stream().filter(result -> result.status() == status).count();
        }
    }

    private SourcePatcher() {
    }

    /**
     * Compares two folders of decompiled sources and reports how the changes can be carried over.
     *
     * <p>A file is a {@link Status#CONFLICT} when the lines that changed in the new version
     * overlap the lines that were customised in the old one. Those are the places where a human
     * has to look; everything else can be ported automatically.</p>
     *
     * @param oldSources the sources of the previous version
     * @param newSources the sources of the new version
     * @param customised the line ranges that were customised, keyed by relative path
     * @param classDiff  the bytecode level difference between the two versions
     * @return the report
     * @throws IOException when a file cannot be read
     */
    public static Report analyse(Path oldSources, Path newSources, Map<String, List<int[]>> customised, ClassDiff classDiff) throws IOException {
        Map<String, Path> oldFiles = collect(oldSources);
        Map<String, Path> newFiles = collect(newSources);

        Map<String, List<int[]>> customisations = new TreeMap<>(customised);
        List<FileResult> results = new ArrayList<>();

        for (Map.Entry<String, Path> entry : newFiles.entrySet()) {
            String path = entry.getKey();
            Path oldFile = oldFiles.get(path);

            if (oldFile == null) {
                results.add(new FileResult(path, Status.NEW, List.of()));
                continue;
            }

            List<String> oldLines = readLines(oldFile);
            List<String> newLines = readLines(entry.getValue());
            List<LineDiff.Hunk> hunks = LineDiff.diff(oldLines, newLines);

            if (hunks.isEmpty()) {
                results.add(new FileResult(path, Status.UNCHANGED, List.of()));
                continue;
            }

            List<int[]> ranges = customisations.get(path);
            boolean conflict = ranges != null && overlaps(ranges, hunks);

            results.add(new FileResult(path, conflict ? Status.CONFLICT : Status.PORTABLE, hunks));
        }

        for (Map.Entry<String, Path> entry : oldFiles.entrySet()) {
            if (!newFiles.containsKey(entry.getKey())) {
                results.add(new FileResult(entry.getKey(), Status.ORPHANED, List.of()));
            }
        }

        results.sort((a, b) -> a.path().compareTo(b.path()));
        return new Report(results, classDiff);
    }

    /**
     * Writes the unified diff of every changed file between two source trees.
     *
     * @param oldSources the sources of the previous version
     * @param newSources the sources of the new version
     * @param output     the file the diff is written to
     * @return the number of files that differ
     * @throws IOException when a file cannot be read or the output cannot be written
     */
    public static int writeUnifiedDiff(Path oldSources, Path newSources, Path output) throws IOException {
        Map<String, Path> oldFiles = collect(oldSources);
        Map<String, Path> newFiles = collect(newSources);

        StringBuilder builder = new StringBuilder();
        int changed = 0;

        for (Map.Entry<String, Path> entry : newFiles.entrySet()) {
            Path oldFile = oldFiles.get(entry.getKey());
            if (oldFile == null) {
                continue;
            }
            String diff = LineDiff.unified(entry.getKey(), readLines(oldFile), readLines(entry.getValue()));
            if (!diff.isEmpty()) {
                builder.append(diff);
                changed++;
            }
        }

        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(output, builder.toString(), StandardCharsets.UTF_8);
        return changed;
    }

    private static boolean overlaps(List<int[]> ranges, List<LineDiff.Hunk> hunks) {
        for (int[] range : ranges) {
            int from = range[0];
            int to = range[1];
            for (LineDiff.Hunk hunk : hunks) {
                // Only the lines the hunk really changes matter, the context around them does
                // not.
                for (int[] region : hunk.changedRegions()) {
                    if (region[0] == region[1]) {
                        // Nothing was removed, lines were inserted at this position.
                        if (from <= region[0] && region[0] <= to) {
                            return true;
                        }
                        continue;
                    }
                    int hunkFrom = region[0] + 1;
                    int hunkTo = region[1];
                    if (from <= hunkTo && hunkFrom <= to) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Collects the Java sources of a tree.
     *
     * <p>Only the {@code .java} files are taken into account: the decompiler also copies the
     * resources of the jar, such as the textures, next to the sources and those are not text.</p>
     *
     * @param root the root of the tree
     * @return the sources, keyed by their path relative to the root
     * @throws IOException when the tree cannot be read
     */
    private static Map<String, Path> collect(Path root) throws IOException {
        Map<String, Path> files = new TreeMap<>();
        if (!Files.isDirectory(root)) {
            return files;
        }

        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            stream.filter(path -> Files.isRegularFile(path)).filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                files.put(root.relativize(path).toString().replace('\\', '/'), path);
            });
        }
        return files;
    }

    /**
     * Reads the lines of a file, replacing anything that is not valid text.
     *
     * <p>The decompiler copies the resources of the jar next to the sources, so a stray binary
     * file must not be able to abort the whole comparison.</p>
     *
     * @param file the file to read
     * @return the lines of the file
     * @throws IOException when the file cannot be read
     */
    private static List<String> readLines(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String text = new String(bytes, StandardCharsets.UTF_8);
        return text.lines().toList();
    }
}
