package mcp.remastered.engine.pipeline.verify;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Runs the game twice and compares what the two runs do.
 *
 * <p>The bytecode the game was built with is a working game: it can be put on the classpath
 * instead of the classes the decompiled sources were compiled into. Running both and comparing
 * what they write catches what a comparison of the bytecode cannot: a loop that never ends, a
 * value that comes out different, a step that is skipped.</p>
 *
 * <p>The logs of the two runs are compared, line by line, once the parts that depend on the
 * machine are taken away: the times, the numbers that come from the world seed, the sizes the
 * renderer grows to, which depend on how fast the frames come.</p>
 */
public final class RuntimeCheck {

    private static final String[] VOLATILE = {"Resizing ", "Preparing spawn area", "logged in with entity id"};

    private RuntimeCheck() {
    }

    /**
     * Runs the game from the original bytecode and from the recompiled sources, and compares
     * the two logs.
     *
     * @param jar       the jar with the bytecode the game was built with
     * @param classes   the classes the decompiled sources were compiled into
     * @param workspace the root of the project
     * @param arguments the arguments the game is started with
     * @param seconds   how long each run may last
     * @return the lines that the two runs do not have in common
     */
    public static List<String> compare(Path jar, Path classes, Path workspace, List<String> arguments, int seconds) throws IOException, InterruptedException {
        Path original = run(jar, classes, workspace, arguments, seconds);
        Path recompiled = run(classes, classes, workspace, arguments, seconds);
        return diff(normalise(original), normalise(recompiled));
    }

    private static Path run(Path game, Path gameClasses, Path workspace, List<String> arguments, int seconds) throws IOException, InterruptedException {
        Path log = workspace.resolve("logs").resolve("latest.log");
        Files.deleteIfExists(log);

        List<String> command = new ArrayList<>();
        command.add(java());
        command.add("-Dfile.encoding=UTF-8");
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("--add-exports");
        command.add("java.base/jdk.internal.misc=ALL-UNNAMED");
        command.add("-Xss4M");
        command.add("-classpath");
        command.add(classpath(game, gameClasses));
        command.add("mcp.remastered.engine.Main");
        command.addAll(arguments);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workspace.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        byte[] output;
        try (InputStream in = process.getInputStream()) {
            output = in.readAllBytes();
        }
        if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }

        Path copy = workspace.resolve("logs").resolve("run-" + game.getFileName() + ".log");
        Files.createDirectories(copy.getParent());
        if (Files.isRegularFile(log)) {
            Files.copy(log, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.writeString(copy, "");
        }
        Files.write(copy.resolveSibling(copy.getFileName() + ".console"), output);
        return copy;
    }

    /**
     * The classpath of a run: the game, the tool and the libraries of the game.
     *
     * <p>The classpath of the tool already holds the libraries and the tool itself: what changes
     * between the two runs is where the classes of the game come from.</p>
     */
    private static String classpath(Path game, Path gameClasses) throws IOException {
        String current = System.getProperty("java.class.path");
        String from = gameClasses.toAbsolutePath().normalize().toString().replace('\\', '/');
        List<String> parts = new ArrayList<>();
        boolean replaced = false;
        for (String part : current.split(java.io.File.pathSeparator)) {
            if (part.isEmpty()) continue;
            String text = Path.of(part).toAbsolutePath().normalize().toString().replace('\\', '/');
            if (!replaced && (text.equals(from) || text.endsWith(from))) {
                parts.add(game.toAbsolutePath().toString());
                replaced = true;
            } else {
                parts.add(part);
            }
        }
        if (!replaced) parts.add(game.toAbsolutePath().toString());
        return String.join(java.io.File.pathSeparator, parts);
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
    }

    /**
     * Takes away what depends on the machine: the times, the numbers, the sizes the renderer
     * grows to.
     */
    static List<String> normalise(Path log) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.isRegularFile(log)) return out;
        for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
            String text = line.replaceAll("^\\[\\d\\d:\\d\\d:\\d\\d\\] ", "");
            if (volatileLine(text)) continue;
            text = text.replaceAll("[-+]?\\d+(\\.\\d+)?", "N");
            out.add(text);
        }
        return out;
    }

    private static boolean volatileLine(String text) {
        for (String marker : VOLATILE) {
            if (text.contains(marker)) return true;
        }
        return false;
    }

    /**
     * The lines the two runs do not have in common.
     */
    static List<String> diff(List<String> original, List<String> recompiled) {
        List<String> out = new ArrayList<>();
        int max = Math.max(original.size(), recompiled.size());
        for (int i = 0; i < max; i++) {
            String a = i < original.size() ? original.get(i) : "";
            String b = i < recompiled.size() ? recompiled.get(i) : "";
            if (!a.equals(b)) {
                out.add(String.format(Locale.ROOT, "riga %d", i + 1));
                out.add("  originale : " + a);
                out.add("  nostro    : " + b);
            }
        }
        return out;
    }
}
