package mcp.remastered.engine.pipeline.verify;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;

/**
 * Checks that the sources the decompiler wrote still describe the game.
 *
 * <p>The sources are compiled again and the bytecode that comes out is compared with the
 * bytecode the game was built with. What matters is not that the two are the same: the game is
 * compiled by Mojang and the sources are compiled by javac, so the instructions differ. What
 * matters is that the methods still call the same things, read the same fields and use the same
 * constants: a method that does not call something the original called does something else, and
 * that is how a decompiler that gets a class wrong shows itself.</p>
 *
 * <p>The differences are written next to the repairs of the version, so that the next run can be
 * compared with the previous one and a new difference stands out.</p>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        String version = argument(args, "--version", "26.3");
        Path root = Path.of(".").toAbsolutePath().normalize();
        Path jar = Path.of(argument(args, "--jar", root.resolve("cache/patched/client.jar").toString()));
        Path classes = Path.of(argument(args, "--classes", root.resolve("game/target/classes").toString()));
        Path reference = Path.of(argument(args, "--reference", root.resolve("fixes/" + version + "/bytecode-differences.txt").toString()));
        boolean write = List.of(args).contains("--write");

        if (List.of(args).contains("--runtime")) {
            runtime(root, jar, classes, version, args);
            return;
        }

        if (!Files.isRegularFile(jar)) {
            System.err.println("Il jar con il bytecode del gioco non c'e': " + jar);
            System.exit(2);
            return;
        }
        if (!Files.isDirectory(classes)) {
            System.err.println("La cartella delle classi compilate non c'e': " + classes);
            System.exit(2);
            return;
        }

        Map<String, byte[]> original = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    original.put(entry.getName().substring(0, entry.getName().length() - ".class".length()), in.readAllBytes());
                }
            }
        }

        List<ClassVerifier.Difference> differences = new ArrayList<>();
        int identical = 0;
        int compared = 0;
        int missing = 0;

        for (Path file : files(classes)) {
            String name = classes.relativize(file).toString().replace("\\", "/").replace(".class", "");
            byte[] theirs = original.get(name);
            if (theirs == null) continue;
            compared++;
            List<ClassVerifier.Difference> found = ClassVerifier.compare(theirs, Files.readAllBytes(file));
            if (found.isEmpty()) {
                identical++;
            } else {
                differences.addAll(found);
            }
        }
        for (String name : original.keySet()) {
            if (!Files.exists(classes.resolve(name + ".class"))) missing++;
        }

        TreeSet<String> lines = new TreeSet<>();
        for (ClassVerifier.Difference difference : differences) {
            lines.add(difference.kind() + "  " + difference.detail());
        }

        System.out.println("Classi nel jar          : " + original.size());
        System.out.println("Classi confrontate      : " + compared);
        System.out.println("Classi identiche        : " + identical);
        System.out.println("Classi diverse          : " + (compared - identical));
        System.out.println("Classi solo nel jar     : " + missing);
        System.out.println("Differenze da rivedere  : " + lines.size());
        System.out.println();

        if (write) {
            Files.createDirectories(reference.getParent());
            Files.writeString(reference, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
            System.out.println("Riferimento scritto in " + reference);
            return;
        }

        if (Files.isRegularFile(reference)) {
            List<String> before = Files.readAllLines(reference, StandardCharsets.UTF_8);
            List<String> now = new ArrayList<>(lines);
            List<String> fresh = new ArrayList<>(now);
            fresh.removeAll(before);
            List<String> gone = new ArrayList<>(before);
            gone.removeAll(now);
            System.out.println("Differenze nuove rispetto a " + reference + ": " + fresh.size());
            fresh.forEach(line -> System.out.println("  + " + line));
            System.out.println("Differenze risolte: " + gone.size());
            gone.forEach(line -> System.out.println("  - " + line));
            return;
        }

        System.out.println("Non c'e' nessun riferimento in " + reference + ": le differenze sono elencate qui sotto.");
        lines.forEach(line -> System.out.println("  " + line));
    }

    /**
     * Runs the game from the original bytecode and from the recompiled sources, and compares
     * what the two runs write.
     */
    private static void runtime(Path root, Path jar, Path classes, String version, String[] args) throws IOException, InterruptedException {
        int seconds = Integer.parseInt(argument(args, "--seconds", "60"));
        List<String> gameArguments = List.of(
                "--launch",
                "--version", version,
                "--username", argument(args, "--username", "Lampadina_17"),
                "--uuid", argument(args, "--uuid", "f769c798-1743-4cf0-9600-08ade6a4bb8f"),
                "--token", argument(args, "--token", "0")
        );
        System.out.println("Prima esecuzione: il bytecode con cui il gioco e' stato costruito.");
        System.out.println("Seconda esecuzione: le classi ricompilate dai sorgenti decompilati.");
        System.out.println();
        List<String> differences = RuntimeCheck.compare(jar, classes, root, gameArguments, seconds);
        if (differences.isEmpty()) {
            System.out.println("Le due esecuzioni fanno le stesse cose: nessuna differenza.");
            return;
        }
        System.out.println("Differenze fra le due esecuzioni: " + (differences.size() / 3));
        differences.forEach(line -> System.out.println("  " + line));
    }

    private static List<Path> files(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream.filter(path -> path.toString().endsWith(".class")).sorted().toList();
        }
    }

    private static String argument(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) return args[i + 1];
        }
        return fallback;
    }
}
