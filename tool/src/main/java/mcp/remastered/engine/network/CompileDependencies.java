package mcp.remastered.engine.network;

import mcp.remastered.engine.mojang.VersionJson;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Holds the libraries that are only needed to compile the sources.
 *
 * <p>The game uses a few annotations that are not part of the runtime, because they are only read
 * by the tools: {@code javax.annotation.concurrent} of JSR-305 and the annotations of JetBrains.
 * Without them the sources do not compile at all.</p>
 */
public final class CompileDependencies {

    /**
     * The libraries that are added to the classpath of the compilation.
     */
    private static final List<String> LIBRARIES = List.of("com.google.code.findbugs:jsr305:3.0.2", "org.jetbrains:annotations:26.0.2");

    private final Path folder;

    /**
     * Creates the folder the libraries are downloaded into.
     *
     * @param folder the folder to use
     */
    public CompileDependencies(Path folder) {
        this.folder = folder;
    }

    /**
     * The coordinates of the libraries.
     *
     * @return the coordinates
     */
    public static List<String> coordinates() {
        return LIBRARIES;
    }

    /**
     * Downloads the libraries, if they are not there yet.
     *
     * <p>They are kept in the folder of the libraries of the game, with the same layout, so that
     * the IDE can put them on the classpath of the module of the game.</p>
     *
     * @param downloader the HTTP layer to use
     * @return the paths of the jars
     * @throws IOException when a library cannot be downloaded
     */
    public List<Path> download(mcp.remastered.engine.network.Http downloader) throws IOException {
        List<Path> jars = new ArrayList<>();
        Files.createDirectories(folder);

        for (String coordinate : LIBRARIES) {
            String[] parts = coordinate.split(":");
            String group = parts[0];
            String artifact = parts[1];
            String version = parts[2];

            String path = group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version + ".jar";
            Path jar = folder.resolve(path);

            if (!Files.exists(jar)) {
                String url = "https://repo1.maven.org/maven2/" + path;
                downloader.download(url, jar, null, null);
            }
            jars.add(jar);
        }
        return jars;
    }

    /**
     * Downloads version artifacts excluded from the runtime classpath by platform rules.
     */
    public static List<Path> downloadVersionArtifacts(VersionJson version, Path libraries, mcp.remastered.engine.network.Http downloader) throws IOException {
        List<Path> jars = new ArrayList<>();
        if (version.libraries == null) return jars;
        Path root = libraries.toAbsolutePath().normalize();
        for (VersionJson.Library library : version.libraries) {
            if (library.downloads == null || library.downloads.artifact == null) continue;
            VersionJson.LibraryArtifact artifact = library.downloads.artifact;
            if (artifact.path == null || artifact.url == null) continue;
            Path jar = root.resolve(artifact.path).normalize();
            if (!jar.startsWith(root)) throw new IOException("Invalid library path: " + artifact.path);
            if (!Files.exists(jar) || (artifact.size > 0 && Files.size(jar) != artifact.size)) {
                downloader.download(artifact.url, jar, artifact.sha1, null);
            }
            if (!jars.contains(jar)) jars.add(jar);
        }
        return jars;
    }

    /**
     * Downloads unclassified Java artifacts needed to compile cross-platform sources.
     */
    public static List<Path> downloadCrossPlatformJava(VersionJson version, Path libraries, Http downloader) throws IOException {
        List<Path> jars = new ArrayList<>();
        if (version.libraries == null) return jars;
        Path root = libraries.toAbsolutePath().normalize();
        for (VersionJson.Library library : version.libraries) {
            if (library.name == null || library.name.split(":", -1).length != 3 || library.downloads == null || library.downloads.artifact == null)
                continue;
            VersionJson.LibraryArtifact artifact = library.downloads.artifact;
            if (artifact.path == null || artifact.url == null || !artifact.path.endsWith(".jar")) continue;
            Path jar = root.resolve(artifact.path).normalize();
            if (!jar.startsWith(root)) throw new IOException("Invalid library path: " + artifact.path);
            downloader.downloadIfNeeded(artifact.url, jar, artifact.size, artifact.sha1);
            if (!jars.contains(jar)) jars.add(jar);
        }
        return jars;
    }

    /**
     * Unpacks a library that is only needed at compile time into the folder of the classes.
     *
     * <p>Some of the annotations are not shipped as a jar but only as a class file inside the
     * jar of the game, so they are taken from there.</p>
     *
     * @param jar    the jar to take the classes from
     * @param prefix the prefix of the classes to take
     * @param target the folder the classes are written to
     * @return the number of classes that were written
     * @throws IOException when a class cannot be written
     */
    public static int unpack(Path jar, String prefix, Path target) throws IOException {
        int written = 0;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(prefix)) {
                    continue;
                }
                Path destination = target.resolve(entry.getName().replace('\\', '/'));
                Files.createDirectories(destination.getParent());
                try (InputStream in = zip.getInputStream(entry); OutputStream out = Files.newOutputStream(destination)) {
                    in.transferTo(out);
                }
                written++;
            }
        }
        return written;
    }
}
