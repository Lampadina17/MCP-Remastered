package mcp.remastered.engine.pipeline.patch;

import mcp.remastered.engine.util.Tree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Carries the changes of the user over to a new version of the game.
 *
 * <p>The project tree holds the sources the user works on. It started as a copy of the tree the
 * tool prepared for a version, and the difference between the two is the work of the user. When
 * a new version shows up, the tree of that version is merged into the project tree with a three
 * way merge: the base is the tree the project was derived from, the changes are the ones of the
 * user and the other side is the new version.</p>
 *
 * <p>What is merged is decided file by file:</p>
 * <ul>
 *   <li>a file that both sides changed in different places is merged,</li>
 *   <li>a file that only one side changed is taken from that side,</li>
 *   <li>a file the new version removed is left alone, so that nothing is lost,</li>
 *   <li>a file the user wrote from scratch is left alone as well,</li>
 *   <li>and a file the new version brought in is added to the project.</li>
 * </ul>
 */
public final class ProjectMerger {

    private ProjectMerger() {
    }

    /**
     * The outcome of the carry over.
     *
     * @param merged    the number of files that were merged
     * @param conflicts the number of files where both sides touched the same lines
     * @param kept      the number of files that were left alone
     * @param added     the number of files the new version brought in
     */
    public record Result(int merged, int conflicts, int kept, int added) {
    }

    /**
     * Merges the tree of a new version into the tree of the project.
     *
     * @param baseline the tree the project was derived from
     * @param project  the tree the user works on
     * @param theirs   the tree of the new version
     * @return the outcome of the carry over
     * @throws IOException when a file cannot be read or written
     */
    public static Result carryOver(Path baseline, Path project, Path theirs) throws IOException {
        int merged = 0;
        int conflicts = 0;
        int kept = 0;
        int added = 0;

        for (String relative : Tree.relativeFiles(project, ".java")) {
            Path base = baseline.resolve(relative);
            Path ours = project.resolve(relative);
            Path their = theirs.resolve(relative);

            if (!Files.exists(their)) {
                // The class is gone in the new version, the file of the user is kept as it is.
                kept++;
                continue;
            }
            if (!Files.exists(base)) {
                // The file was written by the user, there is nothing to carry over.
                kept++;
                continue;
            }

            Tree.Text baseText = Tree.read(base);
            Tree.Text ourText = Tree.read(ours);
            Tree.Text theirText = Tree.read(their);

            SourceMerger.Result result = SourceMerger.merge(relative, baseText.lines(), ourText.lines(), theirText.lines());

            if (result.outcome() == SourceMerger.Outcome.CONFLICT) {
                conflicts++;
            } else {
                merged++;
            }

            Tree.write(ours, result.merged(), ourText.trailingNewline());
        }

        // The files that the new version brought in.
        for (String relative : Tree.relativeFiles(theirs, ".java")) {
            Path target = project.resolve(relative);
            if (Files.exists(target)) {
                continue;
            }
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(theirs.resolve(relative), target);
            added++;
        }

        return new Result(merged, conflicts, kept, added);
    }
}
