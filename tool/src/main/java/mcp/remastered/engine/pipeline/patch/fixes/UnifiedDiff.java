package mcp.remastered.engine.pipeline.patch.fixes;

import java.util.ArrayList;
import java.util.List;

/**
 * A difference between two versions of a file, in the unified format.
 *
 * <p>The format is the one {@code git diff} writes: a header per file and a list of hunks, each
 * one made of the lines that do not change, of the lines that go away and of the lines that
 * come in.</p>
 */
public final class UnifiedDiff {

    /**
     * A piece of a file that changes.
     */
    public static final class Hunk {

        private final List<String> before;
        private final List<String> after;
        private final int line;

        Hunk(List<String> before, List<String> after, int line) {
            this.before = List.copyOf(before);
            this.after = List.copyOf(after);
            this.line = line;
        }

        /**
         * The lines the file has to hold for the hunk to apply.
         *
         * @return the lines, the ones that go away included
         */
        public List<String> before() {
            return before;
        }

        /**
         * The lines the file has to hold once the hunk is applied.
         *
         * @return the lines, the ones that come in included
         */
        public List<String> after() {
            return after;
        }

        /**
         * The line the hunk starts at, in the file it applies to.
         *
         * @return the line, counting from one
         */
        public int line() {
            return line;
        }
    }

    /**
     * The difference of a single file.
     */
    public static final class File {

        private final String path;
        private final List<Hunk> hunks;

        File(String path, List<Hunk> hunks) {
            this.path = path;
            this.hunks = List.copyOf(hunks);
        }

        /**
         * The path of the file, relative to the root of the sources.
         *
         * @return the path, with the slashes of the system
         */
        public String path() {
            return path;
        }

        /**
         * The pieces of the file that change.
         *
         * @return the hunks
         */
        public List<Hunk> hunks() {
            return hunks;
        }
    }

    private UnifiedDiff() {
    }

    /**
     * Reads the differences of a unified diff.
     *
     * @param text the diff
     * @return the files that change, in the order they appear
     */
    public static List<File> parse(String text) {
        List<File> files = new ArrayList<>();
        String path = null;
        List<Hunk> hunks = new ArrayList<>();

        List<String> lines = text.lines().toList();
        int index = 0;
        while (index < lines.size()) {
            String line = lines.get(index);

            if (line.startsWith("--- ")) {
                // Un nuovo file: quello che viene dopo il '+' e' il percorso.
                String target = null;
                for (int ahead = index + 1; ahead < Math.min(index + 4, lines.size()); ahead++) {
                    if (lines.get(ahead).startsWith("+++ ")) {
                        target = lines.get(ahead).substring(4).trim();
                        break;
                    }
                }
                if (target == null) {
                    index++;
                    continue;
                }
                if (path != null) {
                    files.add(new File(path, hunks));
                }
                path = strip(target);
                hunks = new ArrayList<>();
                index++;
                continue;
            }

            if (line.startsWith("+++ ")) {
                index++;
                continue;
            }

            if (line.startsWith("@@") && path != null) {
                java.util.regex.Matcher header = java.util.regex.Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$").matcher(line);
                if (!header.matches()) throw new IllegalArgumentException("Invalid diff hunk: " + line);
                int start = Integer.parseInt(header.group(1));
                int oldRemaining = header.group(2) == null ? 1 : Integer.parseInt(header.group(2));
                int newRemaining = header.group(4) == null ? 1 : Integer.parseInt(header.group(4));
                index++;
                List<String> before = new ArrayList<>();
                List<String> after = new ArrayList<>();
                while (oldRemaining > 0 || newRemaining > 0) {
                    if (index >= lines.size()) throw new IllegalArgumentException("Truncated diff hunk: " + line);
                    String body = lines.get(index);
                    if (body.startsWith("\\")) {
                        index++;
                        continue;
                    }
                    if (body.isEmpty()) throw new IllegalArgumentException("Missing diff line prefix near: " + line);
                    String content = body.substring(1);
                    if (body.startsWith(" ")) {
                        before.add(content);
                        after.add(content);
                        oldRemaining--;
                        newRemaining--;
                    } else if (body.startsWith("-")) {
                        before.add(content);
                        oldRemaining--;
                    } else if (body.startsWith("+")) {
                        after.add(content);
                        newRemaining--;
                    } else {
                        throw new IllegalArgumentException("Invalid diff body near: " + line);
                    }
                    if (oldRemaining < 0 || newRemaining < 0) throw new IllegalArgumentException("Diff hunk count mismatch: " + line);
                    index++;
                }
                hunks.add(new Hunk(before, after, start));
                continue;
            }

            index++;
        }

        if (path != null) {
            files.add(new File(path, hunks));
        }
        return files;
    }

    /**
     * Takes the prefix a diff puts on the paths away.
     *
     * @param path the path as it is in the diff
     * @return the path relative to the root of the sources
     */
    private static String strip(String path) {
        String result = path;
        if (result.startsWith("a/") || result.startsWith("b/")) {
            result = result.substring(2);
        }
        int space = result.indexOf(' ');
        if (space > 0) {
            result = result.substring(0, space);
        }
        return result.replace('/', java.io.File.separatorChar);
    }
}
