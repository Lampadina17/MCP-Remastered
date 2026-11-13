package mcp.remastered.engine;


import mcp.remastered.engine.cli.Console;
import mcp.remastered.engine.mojang.AssetIndex;
import mcp.remastered.engine.mojang.VersionJson;
import mcp.remastered.engine.mojang.VersionManifest;
import mcp.remastered.engine.mojang.VersionSelector;
import mcp.remastered.engine.network.CompileDependencies;
import mcp.remastered.engine.network.Http;
import mcp.remastered.engine.pipeline.GameLauncher;
import mcp.remastered.engine.pipeline.MavenGameLibraries;
import mcp.remastered.engine.pipeline.Pipeline;
import mcp.remastered.engine.workspace.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Entry point of MCP Remastered.
 *
 * <p>The tool walks through the whole pipeline interactively: it fetches the Mojang version
 * manifest, keeps the versions of the 26.1 era, asks whether the user wants a stable release or
 * a snapshot, lets them pick one, and then downloads and decompiles everything belonging to it.</p>
 *
 * <p>What comes out of it is a project: the sources of the game in {@code src/main/java}, the
 * assets the game carries in its jar in {@code src/main/resources}, and a runtime that is a
 * {@code .minecraft} folder. The user can edit the sources and run the game with the debugger
 * attached.</p>
 */
public final class Main {

    private static final String DEFAULT_WORKSPACE = ".";

    private Main() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (Exception e) {
            System.err.println();
            System.err.println("Failed: " + e.getMessage());
            if (System.getenv("MCP_DEBUG") != null) {
                e.printStackTrace();
            }
            System.exit(1);
        }
    }

    private static int run(String[] args) throws Exception {
        Workspace workspace = new Workspace(Path.of(argument(args, "--workspace", DEFAULT_WORKSPACE)));
        if (hasFlag(args, "--build")) {
            throw new IllegalArgumentException("--build is no longer supported; the tool no longer exports a game jar.");
        }
        if (hasFlag(args, "--launch")) {
            launchGame(workspace, args);
            return 0;
        }

        return prepareWorkspace(new Console(), workspace, args);
    }

    private static void launchGame(Workspace workspace, String[] args) throws Exception {
        GameLauncher.launch(workspace, requiredArgument(args, "--version"), requiredArgument(args, "--username"), requiredArgument(args, "--uuid"), requiredArgument(args, "--token"), gameDirectory(workspace, args));
    }

    private static int prepareWorkspace(Console console, Workspace workspace, String[] args) throws IOException {
        console.println();
        console.println("MCP Remastered - Minecraft 26.1+ fetcher, decompiler and patcher");
        console.println("Workspace: " + workspace.root());
        console.println();

        Files.createDirectories(workspace.root());

        Http http = new Http();
        Pipeline pipeline = new Pipeline(http, console, workspace);

        VersionManifest manifest = pipeline.fetchManifest();
        VersionSelector selector = VersionSelector.from(manifest);
        console.println("  Supported versions: " + selector.releases().size() + " releases, " + selector.snapshots().size() + " snapshots.");
        console.println();
        VersionManifest.Entry selected = selectVersion(console, selector, argument(args, "--version", null));
        if (selected == null) return 1;
        VersionJson version = pipeline.fetchVersionJson(selected.url);
        Path clientJar = pipeline.downloadJars(version);
        List<Path> libraries = pipeline.downloadLibraries(version);
        List<Path> compileOnly = pipeline.downloadCompileDependencies();
        List<Path> compileLibraries = new ArrayList<>(libraries);
        compileLibraries.addAll(CompileDependencies.downloadCrossPlatformJava(version, workspace.libraries(), http));
        configureMaven(console, workspace, version, compileLibraries, compileOnly);

        AssetIndex assetIndex = pipeline.fetchAssetIndex(version);
        pipeline.downloadAssets(assetIndex);
        pipeline.extractInternalAssets(clientJar, version.id);
        Path patchedJar = pipeline.patchBytecode(version, clientJar);
        pipeline.prepareSources(version, patchedJar, libraries, hasFlag(args, "--overwrite-sources"));
        pipeline.extractNatives(version, libraries);
        pipeline.prepareGameDirectory(gameDirectory(workspace, args));

        console.println();
        console.println("Done. Sources are in " + workspace.sources());
        return 0;
    }

    private static void configureMaven(Console console, Workspace workspace, VersionJson version, List<Path> libraries, List<Path> compileOnly) throws IOException {
        Path pom = workspace.root().resolve("game").resolve("pom.xml");
        if (Files.isRegularFile(pom)) {
            int added = MavenGameLibraries.update(pom, workspace.root(), version, libraries, compileOnly);
            console.println("  Maven game libraries configured: " + added + ".");
        } else {
            console.println("  No game/pom.xml in workspace; Maven libraries not configured.");
        }

    }

    private static VersionManifest.Entry selectVersion(Console console, VersionSelector selector, String requested) throws IOException {
        if (requested != null) {
            VersionManifest.Entry selected = find(selector, requested);
            if (selected == null) {
                console.println("Unknown version: " + requested);
                return null;
            }
            console.println("Using version " + selected.id + ".");
            console.println();
            return selected;
        }

        VersionSelector.Channel channel = askChannel(console, selector);
        List<VersionManifest.Entry> candidates = selector.versions(channel);
        if (candidates.isEmpty()) {
            console.println("No " + channel.label() + " versions available.");
            return null;
        }
        List<Console.Option> options = new ArrayList<>();
        for (VersionManifest.Entry entry : candidates) {
            options.add(new Console.Option(entry.id, entry.id + "  (" + entry.releaseTime.substring(0, 10) + ")"));
        }
        console.println("Select a " + channel.label() + " version:");
        int choice = console.choose("> ", options);
        console.println();
        return candidates.get(choice);
    }

    /**
     * Asks the user whether to continue with a snapshot or with a stable release.
     *
     * @return the chosen channel
     * @throws IOException when the input cannot be read
     */
    private static VersionSelector.Channel askChannel(Console console, VersionSelector selector) throws IOException {
        while (true) {
            console.println("Do you want to continue with a snapshot or with a stable release?");
            console.item("[0] stable  (" + selector.releases().size() + " versions available)");
            console.item("[1] snapshot (" + selector.snapshots().size() + " versions available)");
            console.println();

            String answer = console.readLine("> ");
            if (answer.isEmpty()) {
                return VersionSelector.Channel.STABLE;
            }
            if (answer.equals("0") || answer.equalsIgnoreCase("stable") || answer.equalsIgnoreCase("s")) {
                return VersionSelector.Channel.STABLE;
            }
            if (answer.equals("1") || answer.equalsIgnoreCase("snapshot") || answer.equalsIgnoreCase("n")) {
                return VersionSelector.Channel.SNAPSHOT;
            }
            console.println("  Please answer 0 or 1.");
            console.println();
        }
    }

    /**
     * The folder the game writes to.
     *
     * @param workspace the workspace of the tool
     * @param args      the command line arguments
     * @return the folder of the game
     */
    private static Path gameDirectory(Workspace workspace, String[] args) {
        return Path.of(argument(args, "--game-dir", workspace.gameDirectory().toString())).toAbsolutePath().normalize();
    }

    /**
     * Looks a version up in the channels that are supported.
     *
     * @param selector the selector holding the versions
     * @param id       the identifier of the wanted version
     * @return the entry, or {@code null} when the version is not supported
     */
    private static VersionManifest.Entry find(VersionSelector selector, String id) {
        for (VersionManifest.Entry entry : selector.releases()) {
            if (entry.id.equalsIgnoreCase(id)) {
                return entry;
            }
        }
        for (VersionManifest.Entry entry : selector.snapshots()) {
            if (entry.id.equalsIgnoreCase(id)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Reads an option from the command line.
     *
     * @param args     the command line arguments
     * @param name     the name of the option, including the leading dashes
     * @param fallback the value used when the option is absent
     * @return the value of the option
     */
    private static String argument(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
    }

    private static String requiredArgument(String[] args, String name) {
        String value = argument(args, name, null);
        if (value == null || value.isBlank() || value.startsWith("--")) {
            throw new IllegalArgumentException("--launch requires " + name + " <value>.");
        }
        return value;
    }

    private static boolean hasFlag(String[] args, String flag) {
        return Arrays.asList(args).contains(flag);
    }
}
