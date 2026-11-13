package mcp.remastered.engine.pipeline;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import mcp.remastered.engine.cli.Console;
import mcp.remastered.engine.mojang.AssetIndex;
import mcp.remastered.engine.mojang.VersionJson;
import mcp.remastered.engine.mojang.VersionManifest;
import mcp.remastered.engine.network.AssetDownloader;
import mcp.remastered.engine.network.Http;
import mcp.remastered.engine.pipeline.decompile.VineflowerDecompiler;
import mcp.remastered.engine.pipeline.patch.*;
import mcp.remastered.engine.pipeline.patch.bytecode.BytecodeFixes;
import mcp.remastered.engine.pipeline.patch.bytecode.BytecodePatch;
import mcp.remastered.engine.pipeline.patch.bytecode.BytecodePatcher;
import mcp.remastered.engine.pipeline.patch.fixes.SourcePatches;
import mcp.remastered.engine.util.Tree;
import mcp.remastered.engine.workspace.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * The pipeline that turns a version identifier into a fully decompiled, ready to patch source
 * tree.
 *
 * <p>The steps are the ones the pipeline is made of:</p>
 * <ol>
 *   <li>fetch the version manifest,</li>
 *   <li>deserialize it with GSON,</li>
 *   <li>keep only the 26.1+ versions,</li>
 *   <li>ask the user for a channel,</li>
 *   <li>ask the user for a version,</li>
 *   <li>fetch and deserialize the version JSON,</li>
 *   <li>download the jars and the libraries,</li>
 *   <li>download and deserialize the asset index,</li>
 *   <li>download the assets, content addressed like the launcher keeps them,</li>
 *   <li>decompile the client jar with Vineflower,</li>
 *   <li>take the assets the game carries in its jar out of it,</li>
 *   <li>apply the differences of the version to the sources,</li>
 *   <li>and finally carry the changes of the user over to the new version.</li>
 * </ol>
 */
public final class Pipeline {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().registerTypeAdapter(VersionJson.Arguments.class, (com.google.gson.JsonDeserializer<VersionJson.Arguments>) (json, type, context) -> {
        VersionJson.Arguments arguments = new VersionJson.Arguments();
        com.google.gson.JsonObject object = json.getAsJsonObject();
        if (object.has("default-user-jvm")) {
            arguments.defaultUserJvm = VersionJson.Arguments.groupsFrom(object.getAsJsonArray("default-user-jvm"));
        }
        if (object.has("jvm")) {
            arguments.jvm = VersionJson.Arguments.listFrom(object.getAsJsonArray("jvm"));
        }
        if (object.has("game")) {
            arguments.game = VersionJson.Arguments.listFrom(object.getAsJsonArray("game"));
        }
        return arguments;
    }).create();

    private final Http http;
    private final Console console;
    private final Workspace workspace;

    public Pipeline(Http http, Console console, Workspace workspace) {
        this.http = http;
        this.console = console;
        this.workspace = workspace;
    }

    /**
     * The workspace the pipeline works in.
     *
     * @return the workspace
     */
    public Workspace workspace() {
        return workspace;
    }

    /**
     * Fetches and deserializes the version manifest.
     *
     * @return the manifest
     * @throws IOException when the manifest cannot be fetched
     */
    public VersionManifest fetchManifest() throws IOException {
        String url = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
        console.println("Fetching version manifest...");
        byte[] bytes = http.get(url);
        VersionManifest manifest = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), VersionManifest.class);
        console.println("  Manifest contains " + manifest.versions.size() + " versions.");
        return manifest;
    }

    /**
     * Fetches and deserializes the JSON of a single version.
     *
     * @param url the location of the version JSON
     * @return the version description
     * @throws IOException when the JSON cannot be fetched
     */
    public VersionJson fetchVersionJson(String url) throws IOException {
        console.println("Fetching version metadata...");
        byte[] bytes = http.get(url);
        VersionJson version = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), VersionJson.class);
        console.println("  Version " + version.id + " requires Java " + version.javaVersion.majorVersion + ".");
        return version;
    }

    /**
     * Downloads the client and server jars of a version.
     *
     * @param version the version to download the jars for
     * @return the path of the client jar
     * @throws IOException when a jar cannot be downloaded
     */
    public Path downloadJars(VersionJson version) throws IOException {
        console.println("Downloading jars...");
        Path target = workspace.jars(version.id);
        Files.createDirectories(target);

        VersionJson.Download client = version.downloads.client;
        Path clientJar = target.resolve("client.jar");
        console.item("client.jar (" + formatSize(client.size) + ")");
        http.downloadIfNeeded(client.url, clientJar, client.size, client.sha1);

        VersionJson.Download server = version.downloads.server;
        if (server != null) {
            Path serverJar = target.resolve("server.jar");
            console.item("server.jar (" + formatSize(server.size) + ")");
            http.downloadIfNeeded(server.url, serverJar, server.size, server.sha1);
        }

        storeMetadata(version, workspace.version(version.id));

        return clientJar;
    }

    /**
     * Downloads the libraries of a version, keeping only the ones that apply to this machine.
     *
     * @param version the version to download the libraries for
     * @return the paths of the downloaded libraries
     * @throws IOException when a library cannot be downloaded
     */
    public List<Path> downloadLibraries(VersionJson version) throws IOException {
        console.println("Downloading libraries...");
        List<Path> downloaded = new ArrayList<>();

        for (VersionJson.Library library : version.libraries) {
            if (!appliesToCurrentPlatform(library.rules)) {
                continue;
            }
            if (library.downloads == null || library.downloads.artifact == null) {
                continue;
            }

            VersionJson.LibraryArtifact artifact = library.downloads.artifact;
            Path file = workspace.libraries().resolve(artifact.path);
            http.downloadIfNeeded(artifact.url, file, artifact.size, artifact.sha1);
            downloaded.add(file);
        }

        console.println("  " + downloaded.size() + " libraries ready.");
        return downloaded;
    }

    /**
     * Downloads the libraries that are only needed to compile the sources.
     *
     * <p>They are kept in the folder of the libraries of the game, so that the IDE can put them
     * on the classpath of the module of the game as well.</p>
     *
     * @return the paths of the downloaded libraries
     * @throws IOException when a library cannot be downloaded
     */
    public List<Path> downloadCompileDependencies() throws IOException {
        return new mcp.remastered.engine.network.CompileDependencies(workspace.libraries()).download(http);
    }

    /**
     * Downloads every asset of a version and stores them the way the launcher keeps them.
     *
     * <p>The assets are content addressed: the index maps a name to a hash and the bytes live in
     * a folder named after the hash. That is the layout of a {@code .minecraft} folder, so the
     * game finds them where it expects them.</p>
     *
     * @param index the asset index
     * @return the number of assets that were downloaded
     * @throws IOException when an asset cannot be downloaded
     */
    public int downloadAssets(AssetIndex index) throws IOException {
        AssetDownloader downloader = new AssetDownloader(http);
        return downloader.downloadAll(index, workspace.objects());
    }

    /**
     * Fetches the asset index and keeps a copy of it where the game expects it.
     *
     * <p>The game reads {@code <assets>/indexes/<id>.json} and looks the assets up in
     * {@code <assets>/objects/}, so the index has to be stored next to the objects.</p>
     *
     * @param version the version to fetch the index for
     * @return the asset index
     * @throws IOException when the index cannot be fetched
     */
    public AssetIndex fetchAssetIndex(VersionJson version) throws IOException {
        console.println("Fetching asset index " + version.assetIndex.id + "...");
        Files.createDirectories(workspace.indexes());
        Path cached = workspace.indexes().resolve(version.assetIndex.id + ".json");
        http.downloadIfNeeded(version.assetIndex.url, cached, version.assetIndex.size, version.assetIndex.sha1);
        byte[] bytes = Files.readAllBytes(cached);
        AssetIndex index = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), AssetIndex.class);
        console.println("  Index lists " + index.objects.size() + " assets.");

        return index;
    }

    /**
     * Extracts the native libraries of a version.
     *
     * @param version   the version the natives belong to
     * @param libraries the libraries that apply to this machine
     * @return the number of files that were extracted
     * @throws IOException when a jar cannot be read
     */
    public int extractNatives(VersionJson version, List<Path> libraries) throws IOException {
        console.println("Extracting natives...");

        String architecture = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        int extracted = new NativesExtractor(architecture).extract(version, libraries, workspace.natives(version.id));
        console.println("  " + extracted + " native libraries extracted.");
        return extracted;
    }

    /**
     * Prepares the folder the game writes to.
     *
     * @param gameDirectory the folder of the game
     * @throws IOException when the folder cannot be created
     */
    public void prepareGameDirectory(Path gameDirectory) throws IOException {
        for (String folder : new String[]{"saves", "mods", "resourcepacks", "config", "screenshots", "logs", "shaderpacks"}) {
            Files.createDirectories(gameDirectory.resolve(folder));
        }
    }

    /**
     * Downloads the logging configuration of the client.
     *
     * <p>The game is told where to write its log with a system property, and the path of the
     * configuration ends up in the arguments of the launch.</p>
     *
     * @param version the version of the client
     * @param target  the folder the configuration is written to
     * @return the path of the configuration, or {@code null} when there is none
     * @throws IOException when the configuration cannot be downloaded
     */
    public Path downloadLoggingConfig(VersionJson version, Path target) throws IOException {
        if (version.logging == null || version.logging.client == null) {
            return null;
        }
        VersionJson.Download file = version.logging.client.file;
        Files.createDirectories(target);
        Path destination = target.resolve(file.url.substring(file.url.lastIndexOf('/') + 1));
        if (!Files.exists(destination) || Files.size(destination) != file.size) {
            http.download(file.url, destination, file.sha1, null);
        }
        return destination;
    }

    /**
     * Takes the assets the game carries inside its own jar out of it.
     *
     * <p>They are put in {@code src/main/resources}, so that they are part of the project and
     * can be edited like the sources.</p>
     *
     * @param clientJar the jar of the client
     * @param versionId the version the jar belongs to
     * @return the number of files that were written
     * @throws IOException when the jar cannot be read
     */
    public int extractInternalAssets(Path clientJar, String versionId) throws IOException {
        Path marker = workspace.root().resolve("cache").resolve("resources-version.txt");
        String extracted = readText(marker);
        if (versionId.equals(extracted) && Files.isDirectory(workspace.resources())) {
            console.println("  The assets of the jar are already extracted, skipping.");
            return 0;
        }

        console.println("Extracting the assets of the jar...");
        int count = mcp.remastered.engine.pipeline.InternalAssets.extract(clientJar, workspace.resources());
        console.println("  " + count + " files extracted to " + workspace.root().relativize(workspace.resources()) + ".");

        writeText(marker, versionId);
        return count;
    }

    /**
     * Patches the bytecode of the client, before it is decompiled.
     *
     * <p>The decompiler reads the patched jar, so the sources that come out describe the patched
     * game and not the original one.</p>
     *
     * @param version   the version the jar belongs to
     * @param clientJar the jar of the client
     * @return the path of the patched jar
     * @throws IOException when the jar cannot be read or written
     */
    public Path patchBytecode(VersionJson version, Path clientJar) throws IOException {
        List<BytecodePatch> patches = BytecodeFixes.forVersion(version.id);
        if (patches.isEmpty()) {
            console.println("  No bytecode patch for " + version.id + ".");
            return clientJar;
        }

        console.println("Patching the bytecode...");
        Path patched = workspace.cache().resolve("patched").resolve("client.jar");
        int changed = BytecodePatcher.patch(clientJar, patches, patched);
        console.println("  " + changed + " classes patched.");
        return patched;
    }

    /**
     * Decompiles the client jar into a folder of Java sources.
     *
     * @param clientJar the jar to decompile
     * @param libraries the libraries put on the decompiler classpath
     * @param target    the folder that receives the sources
     * @return the number of classes that were decompiled
     * @throws IOException when the decompiler fails
     */
    public int decompile(Path clientJar, List<Path> libraries, Path target, String versionId) throws IOException {
        console.println("Decompiling with Vineflower...");
        long start = System.currentTimeMillis();

        Files.createDirectories(target);
        new VineflowerDecompiler(Runtime.getRuntime().availableProcessors()).decompile(clientJar, target, libraries);

        // The decompiler saves the resources of the jar next to the sources. They belong in the
        // resources of the project, not in its sources, so they are taken out of the tree.
        int removed = removeResources(target);

        long elapsed = (System.currentTimeMillis() - start) / 1000;
        long count = countFiles(target, ".java");
        console.println("  Decompiled " + count + " classes in " + elapsed + "s." + (removed > 0 ? " (" + removed + " resources taken out)." : ""));


        int repaired = repairSources(target, versionId);
        return (int) count;
    }

    /**
     * Applies the repairs of the version to the sources the decompiler wrote.
     *
     * <p>They are differences, not code: what the decompiler cannot write is repaired in the
     * text of the sources. A difference that does not apply means the decompiler wrote something
     * else than it did the last time, and it is reported instead of passing unnoticed.</p>
     *
     * @param target    the tree of sources to repair
     * @param versionId the version the repairs belong to
     * @return the number of files that were repaired
     * @throws IOException when the repairs cannot be read
     */
    private int repairSources(Path target, String versionId) throws IOException {
        SourcePatches.Result result = SourcePatches.apply(workspace.fixes(), target, versionId);
        if (result.applied() > 0) {
            console.println("  " + result.applied() + " files repaired.");
        }
        for (String failure : result.failed()) {
            console.println("  ! riparazione non applicata: " + failure);
        }
        if (!result.failed().isEmpty()) {
            throw new IOException("Source repairs failed: " + String.join("; ", result.failed()));
        }
        return result.applied();
    }


    /**
     * Takes the files that are not sources out of a decompiled tree.
     *
     * @param root the folder to clean
     * @return the number of files that were removed
     * @throws IOException when a file cannot be removed
     */
    private static int removeResources(Path root) throws IOException {
        int removed = 0;

        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            List<Path> files = stream.filter(Files::isRegularFile).filter(path -> !path.toString().endsWith(".java")).toList();

            for (Path file : files) {
                Files.deleteIfExists(file);
                removed++;
            }
        }

        // The folders that are left empty are of no use either.
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            List<Path> folders = stream.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList();

            for (Path folder : folders) {
                if (!folder.equals(root)) {
                    try (java.util.stream.Stream<Path> children = Files.list(folder)) {
                        if (children.findAny().isEmpty()) {
                            Files.deleteIfExists(folder);
                        }
                    }
                }
            }
        }

        return removed;
    }

    /**
     * Prepares the sources of the project.
     *
     * <p>The project tree in {@code src/main/java} always holds the sources of the version that is
     * being worked on, with the changes the user made on top of them. When a new version shows
     * up, the changes are carried over with a three way merge: the base is the tree the project
     * was derived from, the changes are the ones of the user and the other side is the new
     * version.</p>
     *
     * @param version   the version being prepared
     * @param clientJar the jar of the client
     * @param libraries the libraries put on the decompiler classpath
     * @throws IOException when the sources cannot be prepared
     */
    public void prepareSources(VersionJson version, Path clientJar, List<Path> libraries) throws IOException {
        prepareSources(version, clientJar, libraries, false);
    }

    /**
     * Re-decompiles to a staging directory and overwrites matching project sources when requested.
     */
    public void prepareSources(VersionJson version, Path clientJar, List<Path> libraries, boolean overwrite) throws IOException {
        Path baseline = workspace.baseline();
        String baselineId = readText(workspace.baselineVersion());
        if (!overwrite && baselineId == null && !Tree.files(workspace.sources(), ".java").isEmpty()) {
            throw new IOException("Game sources already exist but their baseline is missing. Back up source edits and use --overwrite-sources to regenerate them explicitly, or prepare a separate workspace.");
        }
        if (overwrite) {
            if (baselineId != null && !baselineId.equals(version.id)) {
                throw new IOException("Cannot overwrite sources across versions; the baseline belongs to " + baselineId + ". Use the normal upgrade flow instead.");
            }
            Files.createDirectories(workspace.cache());
            Path staged = Files.createTempDirectory(workspace.cache(), "redecompile-");
            try {
                if (decompile(clientJar, libraries, staged, version.id) == 0)
                    throw new IOException("Decompiler produced no Java sources; existing sources were not overwritten.");
                Path tool = Path.of("mcp", "remastered", "engine");
                for (Path file : Tree.files(staged, ".java")) {
                    if (staged.relativize(file).startsWith(tool))
                        throw new IOException("Decompiler output overlaps the tool's own source package: " + file);
                }
                console.println("  Overwriting matching game sources; unrelated files are kept.");
                Tree.copyJava(staged, workspace.sources());
                Tree.replace(staged, baseline);
                writeText(workspace.baselineVersion(), version.id);
                reconcileSources(baseline);
            } finally {
                Tree.delete(staged);
            }
            return;
        }
        boolean upgrade = baselineId != null && !baselineId.equals(version.id);

        // The tree the sources are written to. A new version is decompiled aside, so that the
        // baseline is still there while the changes of the user are carried over.
        Path work = upgrade ? workspace.prepared() : baseline;
        Path marker = upgrade ? workspace.preparedVersion() : workspace.baselineVersion();

        if (version.id.equals(readText(marker)) && countFiles(work, ".java") > 0) {
            console.println("  Sources are already decompiled, skipping.");
        } else {
            Tree.delete(work);
            decompile(clientJar, libraries, work, version.id);
            writeText(marker, version.id);
        }

        Path project = workspace.sources();

        if (baselineId == null) {
            console.println("  Writing the sources to " + workspace.root().relativize(project) + "...");
            Tree.copyJava(work, project);
        } else if (upgrade) {
            recordDifferential(baselineId, work, version);
            ProjectMerger.Result carried = ProjectMerger.carryOver(baseline, project, work);
            console.println("  Changes carried over: " + carried.merged() + " merged, " + carried.conflicts() + " conflicted, " + carried.kept() + " kept as they are, " + carried.added() + " added.");
            Tree.replace(work, baseline);
            writeText(workspace.baselineVersion(), version.id);
            Tree.delete(workspace.prepared());
            Tree.delete(workspace.preparedVersion());
        } else {
            console.println("  The sources of " + version.id + " are already in place, the changes are kept.");
        }
        reconcileSources(baseline);
    }

    private void reconcileSources(Path baseline) throws IOException {
        SourceReconciler.Result result = SourceReconciler.reconcile(workspace.sources(), baseline,
                SourceReconciler.sourcePaths(workspace.versions()), workspace.cache());
        if (result.renamed() > 0) console.println("  Corrected source filename case: " + result.renamed() + ".");
        if (result.archived() > 0) console.println("  Obsolete game sources archived: " + result.archived() + " in " + result.archive());
    }

    /**
     * Compares the version that was processed before with the one that is being processed now
     * and stores the result, so that the work of the old version can be carried over.
     *
     * @param previousId the identifier of the previous version
     * @param theirs     the tree of the new version
     * @param version    the version being processed
     * @throws IOException when the comparison fails
     */
    private void recordDifferential(String previousId, Path theirs, VersionJson version) throws IOException {
        Path baseline = workspace.baseline();
        Path patchDir = workspace.patches().resolve(previousId + "__" + version.id);
        Files.createDirectories(patchDir);

        console.println("Comparing against " + previousId + "...");

        ClassIndex oldIndex = ClassIndex.of(workspace.clientJar(previousId));
        ClassIndex newIndex = ClassIndex.of(workspace.clientJar(version.id));
        ClassDiff diff = ClassDiff.between(oldIndex, newIndex);

        console.println("  " + diff.modifiedClasses().size() + " classes modified, " + diff.addedClasses().size() + " added, " + diff.removedClasses().size() + " removed.");

        SourcePatcher.Report report = SourcePatcher.analyse(baseline, theirs, readCustomisations(baseline), diff);

        console.println("  " + report.count(SourcePatcher.Status.PORTABLE) + " files portable, " + report.count(SourcePatcher.Status.CONFLICT) + " need a look.");

        int changed = SourcePatcher.writeUnifiedDiff(baseline, theirs, patchDir.resolve("sources.diff"));

        console.println("  Patch written to " + workspace.root().relativize(patchDir) + " (" + changed + " files).");
        writePatchSummary(patchDir, previousId, version.id, diff, report);
    }

    /**
     * Reads the lines that were customised in the sources of the project.
     *
     * <p>The customisations are the difference between the tree the project was derived from and
     * the files of {@code src/main/java}, so the ranges returned are the ones a change of the new
     * version has to be checked against.</p>
     *
     * @param baseline the tree the project was derived from
     * @return the customised line ranges, keyed by the path of the file
     * @throws IOException when a file cannot be read
     */
    private Map<String, List<int[]>> readCustomisations(Path baseline) throws IOException {
        Map<String, List<int[]>> customisations = new TreeMap<>();
        Path project = workspace.sources();

        if (!Files.isDirectory(baseline) || !Files.isDirectory(project)) {
            return customisations;
        }

        for (String relative : Tree.relativeFiles(project, ".java")) {
            Path pristine = baseline.resolve(relative);
            if (!Files.exists(pristine)) {
                continue;
            }

            List<int[]> ranges = new ArrayList<>();
            for (LineDiff.Hunk hunk : LineDiff.diff(Tree.lines(pristine), Tree.lines(project.resolve(relative)))) {
                ranges.addAll(hunk.changedRegions());
            }
            if (!ranges.isEmpty()) {
                customisations.put(relative, ranges);
            }
        }
        return customisations;
    }

    /**
     * Stores the metadata of a version next to its files, so that the differential patching
     * layer can tell the versions apart later on.
     *
     * @param version the version that was processed
     * @param target  the folder of the version
     * @throws IOException when the metadata cannot be written
     */
    public void storeMetadata(VersionJson version, Path target) throws IOException {
        Files.createDirectories(target);
        writeText(target.resolve("metadata.json"), GSON.toJson(version));
    }

    private void writePatchSummary(Path patchDir, String previousId, String versionId, ClassDiff diff, SourcePatcher.Report report) throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("# Differential patch ").append(previousId).append(" -> ").append(versionId).append('\n');
        builder.append('\n');
        builder.append("## Bytecode level\n");
        for (ClassDiff.Entry entry : diff.entries()) {
            builder.append("- ").append(entry.kind()).append(' ').append(entry.className());
            if (!entry.changedMethods().isEmpty()) {
                builder.append(" (changed methods: ").append(entry.changedMethods().size()).append(')');
            }
            builder.append('\n');
        }
        builder.append('\n');
        builder.append("## Source level\n");
        for (SourcePatcher.FileResult result : report.results()) {
            if (result.status() == SourcePatcher.Status.UNCHANGED) {
                continue;
            }
            builder.append("- ").append(result.status()).append(' ').append(result.path()).append('\n');
        }

        Files.writeString(patchDir.resolve("summary.txt"), builder.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Decides whether a library applies to the machine the pipeline runs on.
     *
     * <p>The rules follow the launcher semantics: a library is allowed by default and every rule
     * that matches the current platform flips it.</p>
     *
     * @param rules the rules of the library, may be {@code null}
     * @return whether the library should be downloaded
     */
    public static boolean appliesToCurrentPlatform(List<VersionJson.Rule> rules) {
        if (rules == null || rules.isEmpty()) {
            return true;
        }

        boolean allowed = false;
        for (VersionJson.Rule rule : rules) {
            if (matches(rule.os)) {
                allowed = "allow".equals(rule.action);
            }
        }
        return allowed;
    }

    private static boolean matches(VersionJson.OsSpec os) {
        if (os == null) {
            return true;
        }

        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String currentName = osName.contains("win") ? "windows" : osName.contains("mac") ? "osx" : osName.contains("linux") ? "linux" : osName;

        if (os.name != null && !os.name.equalsIgnoreCase(currentName)) {
            return false;
        }

        if (os.arch != null) {
            String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
            String currentArch = switch (currentName) {
                case "windows" -> switch (arch) {
                    case "x86", "i386", "i486", "i586", "i686" -> "x86";
                    case "aarch64", "arm64" -> "arm64";
                    default -> "x86_64";
                };
                case "osx" -> switch (arch) {
                    case "aarch64", "arm64" -> "aarch64";
                    default -> "x86_64";
                };
                default -> switch (arch) {
                    case "aarch64", "arm64" -> "aarch64";
                    default -> "x86_64";
                };
            };
            if (!os.arch.equalsIgnoreCase(currentArch)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Reads the content of a small text file.
     *
     * @param file the file to read
     * @return the content, or {@code null} when the file is not there
     */
    private static String readText(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Writes a small text file.
     *
     * @param file    the file to write
     * @param content the content to write
     * @throws IOException when the file cannot be written
     */
    private static void writeText(Path file, String content) throws IOException {
        if (Files.isRegularFile(file) && Files.readString(file, StandardCharsets.UTF_8).equals(content)) return;
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static long countFiles(Path root, String suffix) throws IOException {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(suffix)).count();
        }
    }

    private static String formatSize(long bytes) {
        if (bytes > 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
        }
        if (bytes > 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }
}
