package mcp.remastered.engine.pipeline.patch.fixes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Applies the differences of a version to the sources the decompiler wrote.
 *
 * <p>The differences live in the workspace, in the folder of the version, and they are the
 * repairs the sources need on top of what the decompiler gives. They are applied after the
 * decompilation and before the sources are written to the project, so that the tree of the
 * project and the baseline of the differential layer agree.</p>
 *
 * <p>A difference that does not apply is not an error that passes unnoticed: it is reported, with
 * the file and the piece that did not fit. A difference whose context does not match means the
 * decompiler wrote something else than it did the last time, and that has to be looked at.</p>
 */
public final class SourcePatches {

    /**
     * What happened while the differences were applied.
     */
    public static final class Result {

        private final int applied;
        private final List<String> failed;

        Result(int applied, List<String> failed) {
            this.applied = applied;
            this.failed = List.copyOf(failed);
        }

        /**
         * The number of differences that were applied.
         *
         * @return the count
         */
        public int applied() {
            return applied;
        }

        /**
         * The differences that did not apply.
         *
         * @return a description of each of them
         */
        public List<String> failed() {
            return failed;
        }
    }

    private SourcePatches() {
    }

    /**
     * Applies the differences of a version to a tree of sources.
     *
     * @param fixes   the folder the differences of the version live in
     * @param tree    the tree of sources they are applied to
     * @param version the version the differences belong to
     * @return what happened
     * @throws IOException when the folder cannot be read
     */
    public static Result apply(Path fixes, Path tree, String version) throws IOException {
        Path folder = fixes.resolve(version);
        if (!Files.isDirectory(folder)) {
            return new Result(0, List.of());
        }

        int applied = 0;
        List<String> failed = new ArrayList<>();

        List<Path> diffs;
        try (Stream<Path> stream = Files.list(folder)) {
            diffs = stream.filter(path -> path.getFileName().toString().endsWith(".diff")).sorted().toList();
        }

        for (Path diff : diffs) {
            String text = Files.readString(diff, StandardCharsets.UTF_8);
            for (UnifiedDiff.File file : UnifiedDiff.parse(text)) {
                Path target = resolve(tree, file.path());
                if (target == null) {
                    failed.add(diff.getFileName() + ": " + file.path() + " non esiste");
                    continue;
                }
                List<String> lines = Files.readAllLines(target, StandardCharsets.UTF_8);
                List<String> result = new ArrayList<>();
                int cursor = 0;
                boolean broken = false;

                for (UnifiedDiff.Hunk hunk : file.hunks()) {
                    int at = find(lines, hunk.before(), cursor);
                    if (at < 0) {
                        failed.add(diff.getFileName() + ": " + file.path() + ":" + hunk.line() + " il contesto non c'e' piu'");
                        broken = true;
                        break;
                    }
                    result.addAll(lines.subList(cursor, at));
                    result.addAll(hunk.after());
                    cursor = at + hunk.before().size();
                }
                if (broken) {
                    continue;
                }
                result.addAll(lines.subList(cursor, lines.size()));
                // Il terminatore di riga e' quello del progetto, non quello della piattaforma.
                Files.writeString(target, String.join("\n", result) + "\n", StandardCharsets.UTF_8);
                applied++;
            }
        }

        return new Result(applied, failed);
    }

    /**
     * Looks for the file a difference talks about.
     *
     * <p>The parser removes repository prefixes. Only regular files within the source root
     * can be repaired; a missing file must never resolve to a parent directory.</p>
     *
     * @param tree the tree of sources
     * @param path the path of the file, as it is in the difference
     * @return the file, or {@code null} when there is none
     */
    private static Path resolve(Path tree, String path) {
        Path root = tree.toAbsolutePath().normalize();
        Path candidate = root.resolve(path).normalize();
        if (candidate.startsWith(root) && Files.isRegularFile(candidate)) {
            return candidate;
        }
        return null;
    }

    /**
     * Looks for a piece of a file.
     *
     * @param lines the lines of the file
     * @param piece the piece to look for
     * @param from  the line to start looking from
     * @return the line the piece starts at, or minus one when it is not there
     */
    private static int find(List<String> lines, List<String> piece, int from) {
        if (piece.isEmpty()) {
            return Math.min(from, lines.size());
        }
        for (int start = Math.max(0, from); start + piece.size() <= lines.size(); start++) {
            if (matches(lines, piece, start)) {
                return start;
            }
        }
        return -1;
    }

    /**
     * Tells whether a piece of a file is at a given line.
     */
    private static boolean matches(List<String> lines, List<String> piece, int start) {
        for (int i = 0; i < piece.size(); i++) {
            if (!piece.get(i).equals(lines.get(start + i))) {
                return false;
            }
        }
        return true;
    }
}
