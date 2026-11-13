package mcp.remastered.engine.workspace;


import java.nio.file.Path;

/**
 * The layout of the workspace.
 *
 * <p>The workspace holds two things that are kept apart on purpose:</p>
 *
 * <ul>
 *   <li>the <em>runtime</em>, which is the folder the game is started from and which is laid out
 *       like the {@code .minecraft} folder of the official launcher: the assets in their content
 *       addressed form, the libraries, the versions and the folders the game writes to;</li>
 *   <li>the <em>project</em>, which holds the decompiled sources under {@code src/main/java} and
 *       the assets the game carries inside its jar under {@code src/main/resources}. Those are
 *       the files the user edits, so they are laid out like the sources of any other project
 *       and are marked as a source root of the IntelliJ project.</li>
 * </ul>
 *
 * <p>The tree looks like this:</p>
 *
 * <pre>
 * runtime/                     the .minecraft folder
 *   assets/indexes/&lt;id&gt;.json      the asset indexes
 *   assets/objects/&lt;xx&gt;/&lt;hash&gt;     the assets, content addressed
 *   libraries/                 the libraries of the game
 *   natives/&lt;id&gt;/               the native libraries
 *   versions/&lt;id&gt;/              the jars and the metadata of every version
 *   game/                      the folder the game writes to
 * src/main/java/               the decompiled sources, the files the user edits
 * src/main/resources/          the assets the game carries inside its jar
 * cache/baseline/              the sources the project tree was derived from
 * cache/prepared/              the sources the tool prepared for the version being processed
 * patches/                     the differential patches between two versions
 * </pre>
 */
public final class Workspace {

    private final Path root;

    public Workspace(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * The folder that holds everything.
     */
    public Path root() {
        return root;
    }

    /**
     * The folder the game is started from, laid out like a {@code .minecraft} folder.
     */
    public Path runtime() {
        return root.resolve("runtime");
    }

    /**
     * The decompiled sources of the game, the files the user edits.
     */
    public Path sources() {
        return root.resolve("src").resolve("main").resolve("java");
    }

    /**
     * The assets the game carries inside its own jar.
     */
    public Path resources() {
        return root.resolve("src").resolve("main").resolve("resources");
    }

    /**
     * The folder of the assets, holding the index and the content addressed store.
     */
    public Path assets() {
        return runtime().resolve("assets");
    }

    /**
     * The content addressed store of the assets, exactly as the launcher keeps it.
     */
    public Path objects() {
        return assets().resolve("objects");
    }

    /**
     * The index files of the assets.
     */
    public Path indexes() {
        return assets().resolve("indexes");
    }

    /**
     * The libraries of the game.
     */
    public Path libraries() {
        return runtime().resolve("libraries");
    }

    /**
     * The folder holding the versions of the game.
     */
    public Path versions() {
        return runtime().resolve("versions");
    }

    /**
     * The folder of a single version.
     */
    public Path version(String id) {
        return versions().resolve(id);
    }

    /**
     * The folder the jars of a version are kept in.
     */
    public Path jars(String id) {
        return version(id).resolve("jars");
    }

    /**
     * The jar of the client of a version.
     */
    public Path clientJar(String id) {
        return jars(id).resolve("client.jar");
    }

    /**
     * The metadata of a version.
     */
    public Path metadata(String id) {
        return version(id).resolve("metadata.json");
    }

    /**
     * The natives of a version.
     */
    public Path natives(String id) {
        return runtime().resolve("natives").resolve(id);
    }

    /**
     * The folder the game writes to.
     */
    public Path gameDirectory() {
        return runtime().resolve("game");
    }

    /**
     * The sources the project tree was derived from.
     *
     * <p>They are the base of the three way merge and the reference the differential layer
     * compares against. They are kept out of the project tree on purpose, so that the files the
     * user edits are the only ones that look like part of the project.</p>
     */
    public Path baseline() {
        return root.resolve("cache").resolve("baseline");
    }

    /**
     * The file that tells which version the baseline belongs to.
     */
    public Path baselineVersion() {
        return root.resolve("cache").resolve("baseline-version.txt");
    }

    /**
     * The sources the tool prepared for the version that is being processed.
     */
    public Path prepared() {
        return root.resolve("cache").resolve("prepared");
    }

    /**
     * The file that tells which version the prepared tree belongs to.
     */
    public Path preparedVersion() {
        return root.resolve("cache").resolve("prepared-version.txt");
    }

    /**
     * The folder the intermediate files of the pipeline are kept in.
     *
     * <p>The jar with the patched bytecode lives here: it is what the decompiler reads, and it
     * is rebuilt every time the tool runs.</p>
     */
    public Path cache() {
        return root.resolve("cache");
    }

    /**
     * The folder the patches of the differential layer are written to.
     */
    public Path patches() {
        return root.resolve("patches");
    }

    /**
     * The folder the repairs of the sources live in.
     *
     * <p>They are the differences that are applied to the sources the decompiler writes, one
     * folder per version.</p>
     */
    public Path fixes() {
        return root.resolve("fixes");
    }
}
