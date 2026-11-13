package mcp.remastered.engine.pipeline.patch;

import java.util.ArrayList;
import java.util.List;

/**
 * A three way merge of a file that was customised on top of an older version of the game.
 *
 * <p>The merge has three inputs:</p>
 * <ul>
 *   <li>the <em>base</em>, the pristine sources of the version the work started from,</li>
 *   <li>the <em>ours</em>, those sources with the customisations applied, and</li>
 *   <li>the <em>theirs</em>, the pristine sources of the new version.</li>
 * </ul>
 *
 * <p>A change made on only one side is carried over. When both sides touched the same lines the
 * merge cannot decide, and the region is reported as a conflict instead of being guessed at.</p>
 */
public final class SourceMerger {

    /**
     * How the merge of a single file went.
     */
    public enum Outcome {
        /**
         * Neither side changed anything.
         */
        CLEAN,
        /**
         * The customisations were carried over without any trouble.
         */
        MERGED,
        /**
         * Both sides touched the same lines, a human has to decide.
         */
        CONFLICT,
        /**
         * The file only exists in the new version.
         */
        ADDED,
        /**
         * The file only existed in the old version.
         */
        REMOVED
    }

    /**
     * The result of merging one file.
     */
    public static final class Result {
        private final String path;
        private final Outcome outcome;
        private final List<String> merged;
        private final List<int[]> conflicts;

        Result(String path, Outcome outcome, List<String> merged, List<int[]> conflicts) {
            this.path = path;
            this.outcome = outcome;
            this.merged = merged;
            this.conflicts = conflicts;
        }

        public String path() {
            return path;
        }

        public Outcome outcome() {
            return outcome;
        }

        /**
         * The merged content, or the original one when the merge failed.
         */
        public List<String> merged() {
            return merged;
        }

        /**
         * The conflicting regions of the base file, each one a pair of zero based, half open
         * bounds.
         */
        public List<int[]> conflicts() {
            return conflicts;
        }

        @Override
        public String toString() {
            return outcome + " " + path;
        }
    }

    /**
     * A change of one side, expressed in the coordinates of the base file.
     */
    private record Edit(int from, int to, List<String> replacement) {
    }

    private SourceMerger() {
    }

    /**
     * Merges one file.
     *
     * @param path   the path of the file, used in the result
     * @param base   the pristine sources of the old version
     * @param ours   the customised sources of the old version
     * @param theirs the pristine sources of the new version
     * @return the result of the merge
     */
    public static Result merge(String path, List<String> base, List<String> ours, List<String> theirs) {
        List<Edit> ourEdits = edits(base, ours);
        List<Edit> theirEdits = edits(base, theirs);

        if (ourEdits.isEmpty() && theirEdits.isEmpty()) {
            return new Result(path, Outcome.CLEAN, theirs, List.of());
        }
        if (ourEdits.isEmpty()) {
            return new Result(path, Outcome.MERGED, theirs, List.of());
        }
        if (theirEdits.isEmpty()) {
            return new Result(path, Outcome.MERGED, ours, List.of());
        }

        List<int[]> conflicts = new ArrayList<>();
        List<String> merged = new ArrayList<>();

        int baseIndex = 0;
        int ourIndex = 0;
        int theirIndex = 0;

        while (ourIndex < ourEdits.size() || theirIndex < theirEdits.size()) {
            Edit our = ourIndex < ourEdits.size() ? ourEdits.get(ourIndex) : null;
            Edit their = theirIndex < theirEdits.size() ? theirEdits.get(theirIndex) : null;

            int nextFrom = our != null ? our.from() : Integer.MAX_VALUE;
            if (their != null) {
                nextFrom = Math.min(nextFrom, their.from());
            }

            // Copy the untouched lines up to the next change.
            while (baseIndex < nextFrom) {
                merged.add(base.get(baseIndex));
                baseIndex++;
            }

            // The two sides only clash when they touch the same part of the base file.
            boolean overlapping = our != null && their != null && our.from() < their.to() && their.from() < our.to();

            if (overlapping) {
                int end = Math.max(our.to(), their.to());
                if (our.replacement().equals(their.replacement())) {
                    // Both sides made the same change, there is nothing to decide.
                    merged.addAll(our.replacement());
                } else {
                    conflicts.add(new int[]{Math.min(our.from(), their.from()), end});
                    merged.addAll(their.replacement());
                }
                baseIndex = end;
                ourIndex++;
                theirIndex++;
                continue;
            }

            if (our != null && our.from() <= nextFrom) {
                merged.addAll(our.replacement());
                baseIndex = our.to();
                ourIndex++;
                continue;
            }

            merged.addAll(their.replacement());
            baseIndex = their.to();
            theirIndex++;
        }

        while (baseIndex < base.size()) {
            merged.add(base.get(baseIndex));
            baseIndex++;
        }

        return new Result(path, conflicts.isEmpty() ? Outcome.MERGED : Outcome.CONFLICT, merged, conflicts);
    }

    /**
     * Turns the difference between two versions of a file into the edits of one side.
     *
     * @param base  the lines of the base file
     * @param other the lines of the side that was changed
     * @return the edits, in the coordinates of the base file
     */
    private static List<Edit> edits(List<String> base, List<String> other) {
        List<Edit> edits = new ArrayList<>();

        for (LineDiff.Change change : LineDiff.changes(base, other)) {
            List<String> replacement = new ArrayList<>(other.subList(change.newFrom(), change.newTo()));
            edits.add(new Edit(change.oldFrom(), change.oldTo(), replacement));
        }

        return edits;
    }
}
