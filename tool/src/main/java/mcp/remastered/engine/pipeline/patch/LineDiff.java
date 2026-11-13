package mcp.remastered.engine.pipeline.patch;

import java.util.ArrayList;
import java.util.List;

/**
 * A line based diff, used to compute the patch that turns one version of the sources into
 * another.
 *
 * <p>The implementation follows the usual shape of a unified diff:</p>
 * <ol>
 *   <li>the common prefix and the common suffix of the two files are trimmed away,</li>
 *   <li>the remaining middle is aligned with a longest common subsequence,</li>
 *   <li>the aligned segments are turned into hunks and neighbouring hunks are merged,</li>
 *   <li>and every hunk is padded with a few lines of context.</li>
 * </ol>
 *
 * <p>Very large files are handled without blowing up: the alignment table is only built when the
 * two middles are small enough, otherwise the whole middle is reported as one replacement.</p>
 */
public final class LineDiff {

    /**
     * Number of context lines kept around every hunk.
     */
    public static final int CONTEXT = 3;

    /**
     * The largest number of cells the alignment table may use. Beyond that the middle of the file
     * is reported as a single replacement, which is cheaper and still correct.
     */
    private static final int MAX_TABLE_CELLS = 4_000_000;

    /**
     * A single line inside a hunk.
     */
    public record Line(char kind, String text) {
    }

    /**
     * A region of the old file that the new file replaces.
     *
     * <p>The bounds are zero based and half open: {@code [oldFrom, oldTo)} in the old file is
     * replaced by {@code [newFrom, newTo)} in the new one.</p>
     */
    public record Change(int oldFrom, int oldTo, int newFrom, int newTo) {
    }

    /**
     * A contiguous block of changes inside a file.
     */
    public static final class Hunk {
        private final int oldStart;
        private final int oldCount;
        private final int newStart;
        private final int newCount;
        private final List<int[]> changedRegions;
        private final List<Line> lines;

        Hunk(int oldStart, int oldCount, int newStart, int newCount, List<int[]> changedRegions, List<Line> lines) {
            this.oldStart = oldStart;
            this.oldCount = oldCount;
            this.newStart = newStart;
            this.newCount = newCount;
            this.changedRegions = changedRegions;
            this.lines = lines;
        }

        /**
         * First line of the hunk in the old file, one based.
         */
        public int oldStart() {
            return oldStart;
        }

        /**
         * Number of lines the hunk takes from the old file.
         */
        public int oldCount() {
            return oldCount;
        }

        /**
         * First line of the hunk in the new file, one based.
         */
        public int newStart() {
            return newStart;
        }

        /**
         * Number of lines the hunk contributes to the new file.
         */
        public int newCount() {
            return newCount;
        }

        /**
         * The regions of the old file the hunk really changes.
         *
         * <p>Each region is a pair of zero based, half open bounds. A region whose bounds are
         * equal marks a position where lines were inserted without removing anything. The context
         * lines around the changes are not part of it, so this is the range a customisation has to
         * be checked against.</p>
         */
        public List<int[]> changedRegions() {
            return changedRegions;
        }

        /**
         * The lines of the hunk, each one tagged with its kind.
         */
        public List<Line> lines() {
            return lines;
        }

        /**
         * The lines the hunk removes from the old file.
         */
        public List<String> removed() {
            List<String> result = new ArrayList<>();
            for (Line line : lines) {
                if (line.kind() == '-') {
                    result.add(line.text());
                }
            }
            return result;
        }

        /**
         * The lines the hunk adds to the new file.
         */
        public List<String> added() {
            List<String> result = new ArrayList<>();
            for (Line line : lines) {
                if (line.kind() == '+') {
                    result.add(line.text());
                }
            }
            return result;
        }
    }

    private LineDiff() {
    }

    /**
     * Computes the difference between two lists of lines.
     *
     * @param oldLines the lines of the original file
     * @param newLines the lines of the new file
     * @return the hunks that turn the original into the new file
     */
    public static List<Hunk> diff(List<String> oldLines, List<String> newLines) {
        int prefix = 0;
        int maxPrefix = Math.min(oldLines.size(), newLines.size());
        while (prefix < maxPrefix && oldLines.get(prefix).equals(newLines.get(prefix))) {
            prefix++;
        }

        int suffix = 0;
        int maxSuffix = Math.min(oldLines.size() - prefix, newLines.size() - prefix);
        while (suffix < maxSuffix && oldLines.get(oldLines.size() - 1 - suffix).equals(newLines.get(newLines.size() - 1 - suffix))) {
            suffix++;
        }

        List<String> oldMiddle = oldLines.subList(prefix, oldLines.size() - suffix);
        List<String> newMiddle = newLines.subList(prefix, newLines.size() - suffix);

        List<Change> changes = align(oldMiddle, newMiddle);
        if (changes.isEmpty()) {
            return List.of();
        }

        // Merge the changes that are close to each other, so that a hunk is not split in two by a
        // couple of unchanged lines.
        List<List<Change>> groups = new ArrayList<>();
        for (Change change : changes) {
            if (!groups.isEmpty()) {
                List<Change> last = groups.get(groups.size() - 1);
                Change previous = last.get(last.size() - 1);
                int gap = change.oldFrom() - previous.oldTo();
                if (gap <= CONTEXT * 2) {
                    last.add(change);
                    continue;
                }
            }
            groups.add(new ArrayList<>(List.of(change)));
        }

        List<Hunk> hunks = new ArrayList<>();
        for (List<Change> group : groups) {
            hunks.add(buildHunk(oldLines, newLines, prefix, group));
        }
        return hunks;
    }

    /**
     * Computes the regions that differ between two lists of lines, without any context.
     *
     * <p>The bounds are absolute, in the coordinates of the whole files rather than of the
     * trimmed middles, so a region can be applied directly to the original file.</p>
     *
     * @param oldLines the lines of the original file
     * @param newLines the lines of the new file
     * @return the differing regions, in order
     */
    public static List<Change> changes(List<String> oldLines, List<String> newLines) {
        int prefix = 0;
        int maxPrefix = Math.min(oldLines.size(), newLines.size());
        while (prefix < maxPrefix && oldLines.get(prefix).equals(newLines.get(prefix))) {
            prefix++;
        }

        int suffix = 0;
        int maxSuffix = Math.min(oldLines.size() - prefix, newLines.size() - prefix);
        while (suffix < maxSuffix && oldLines.get(oldLines.size() - 1 - suffix).equals(newLines.get(newLines.size() - 1 - suffix))) {
            suffix++;
        }

        List<String> oldMiddle = oldLines.subList(prefix, oldLines.size() - suffix);
        List<String> newMiddle = newLines.subList(prefix, newLines.size() - suffix);

        List<Change> changes = new ArrayList<>();
        for (Change change : align(oldMiddle, newMiddle)) {
            changes.add(new Change(prefix + change.oldFrom(), prefix + change.oldTo(), prefix + change.newFrom(), prefix + change.newTo()));
        }
        return changes;
    }

    /**
     * Renders the difference between two files as a unified diff.
     *
     * @param path     the path of the file, used in the header
     * @param oldLines the lines of the original file
     * @param newLines the lines of the new file
     * @return the unified diff, or an empty string when the files are identical
     */
    public static String unified(String path, List<String> oldLines, List<String> newLines) {
        List<Hunk> hunks = diff(oldLines, newLines);
        if (hunks.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("--- a/").append(path).append('\n');
        builder.append("+++ b/").append(path).append('\n');

        for (Hunk hunk : hunks) {
            builder.append("@@ -").append(hunk.oldStart()).append(',').append(hunk.oldCount()).append(" +").append(hunk.newStart()).append(',').append(hunk.newCount()).append(" @@\n");
            for (Line line : hunk.lines()) {
                builder.append(line.kind()).append(line.text()).append('\n');
            }
        }

        return builder.toString();
    }

    /**
     * Aligns two lists of lines and returns the regions that differ.
     *
     * @param a the lines of the original
     * @param b the lines of the new file
     * @return the differing regions, in order
     */
    private static List<Change> align(List<String> a, List<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return List.of();
        }
        if (a.isEmpty() || b.isEmpty()) {
            return List.of(new Change(0, a.size(), 0, b.size()));
        }

        long cells = (long) (a.size() + 1) * (b.size() + 1);
        if (cells > MAX_TABLE_CELLS) {
            // Too big to align exactly, report the whole middle as replaced.
            return List.of(new Change(0, a.size(), 0, b.size()));
        }

        int[][] lcs = longestCommonSubsequence(a, b);
        List<Change> changes = new ArrayList<>();

        int i = 0;
        int j = 0;
        int changeOldFrom = -1;
        int changeNewFrom = -1;

        while (i < a.size() && j < b.size()) {
            if (a.get(i).equals(b.get(j))) {
                if (changeOldFrom >= 0) {
                    changes.add(new Change(changeOldFrom, i, changeNewFrom, j));
                    changeOldFrom = -1;
                }
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                if (changeOldFrom < 0) {
                    changeOldFrom = i;
                    changeNewFrom = j;
                }
                i++;
            } else {
                if (changeOldFrom < 0) {
                    changeOldFrom = i;
                    changeNewFrom = j;
                }
                j++;
            }
        }

        if (changeOldFrom >= 0) {
            // The change is still open when the walk stops, so it has to be closed against
            // whatever is left of the side that ran out first.
            int oldTo = i;
            int newTo = j;
            if (i == a.size() && j < b.size()) {
                newTo = b.size();
            } else if (j == b.size() && i < a.size()) {
                oldTo = a.size();
            }
            changes.add(new Change(changeOldFrom, oldTo, changeNewFrom, newTo));
        } else if (i < a.size()) {
            // Only the old side has lines left, they were removed.
            changes.add(new Change(i, a.size(), j, j));
        } else if (j < b.size()) {
            // Only the new side has lines left, they were added.
            changes.add(new Change(i, i, j, b.size()));
        }

        return changes;
    }

    /**
     * Builds the hunk of a group of changes, padded with the surrounding context lines.
     *
     * @param oldLines the lines of the old file
     * @param newLines the lines of the new file
     * @param prefix   the number of common lines trimmed from the front of both files
     * @param group    the changes the hunk covers
     * @return the hunk
     */
    private static Hunk buildHunk(List<String> oldLines, List<String> newLines, int prefix, List<Change> group) {
        Change first = group.get(0);
        Change last = group.get(group.size() - 1);

        // Absolute bounds of the changed area, the prefix trimmed in front is added back.
        int oldChangeFrom = prefix + first.oldFrom();
        int oldChangeTo = prefix + last.oldTo();
        int newChangeFrom = prefix + first.newFrom();
        int newChangeTo = prefix + last.newTo();

        // The context in front of the first change and behind the last one.
        int leading = Math.min(CONTEXT, oldChangeFrom);
        int trailing = Math.min(CONTEXT, oldLines.size() - oldChangeTo);

        int oldStart = oldChangeFrom - leading;
        int oldEnd = oldChangeTo + trailing;
        int newStart = newChangeFrom - leading;
        int newEnd = newChangeTo + trailing;

        List<Line> lines = new ArrayList<>();
        int oldIndex = oldStart;
        int newIndex = newStart;

        for (Change change : group) {
            int changeOldFrom = prefix + change.oldFrom();
            int changeNewFrom = prefix + change.newFrom();

            // The unchanged lines between the previous change and this one.
            while (oldIndex < changeOldFrom) {
                lines.add(new Line(' ', oldLines.get(oldIndex)));
                oldIndex++;
                newIndex++;
            }

            for (int i = change.oldFrom(); i < change.oldTo(); i++) {
                lines.add(new Line('-', oldLines.get(prefix + i)));
            }
            for (int i = change.newFrom(); i < change.newTo(); i++) {
                lines.add(new Line('+', newLines.get(prefix + i)));
            }

            oldIndex = prefix + change.oldTo();
            newIndex = prefix + change.newTo();
        }

        // The context behind the last change.
        while (oldIndex < oldEnd) {
            lines.add(new Line(' ', oldLines.get(oldIndex)));
            oldIndex++;
            newIndex++;
        }

        List<int[]> changedRegions = new ArrayList<>();
        for (Change change : group) {
            int from = prefix + change.oldFrom();
            int to = prefix + change.oldTo();
            changedRegions.add(new int[]{from, to});
        }

        return new Hunk(oldStart + 1, oldEnd - oldStart, newStart + 1, newEnd - newStart, changedRegions, lines);
    }

    private static int[][] longestCommonSubsequence(List<String> a, List<String> b) {
        int[][] table = new int[a.size() + 1][b.size() + 1];

        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                if (a.get(i).equals(b.get(j))) {
                    table[i][j] = table[i + 1][j + 1] + 1;
                } else {
                    table[i][j] = Math.max(table[i + 1][j], table[i][j + 1]);
                }
            }
        }
        return table;
    }
}
