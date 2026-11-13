package mcp.remastered.engine.pipeline;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Takes the assets the game carries inside its own jar out of it.
 *
 * <p>The client jar holds two very different things: the class files, which are the code, and a
 * pile of resources, which are the data of the game: the sounds, the models, the translations
 * and the data packs. The resources are what makes the jar a game and not a library, and they are
 * worth having as plain files, so that they can be looked at, changed and shipped again.</p>
 *
 * <p>They end up in {@code src/main/resources}, the folder the build takes them from, so the
 * patched jar is assembled from the classes and from the resources the user sees.</p>
 */
public final class InternalAssets {

    private InternalAssets() {
    }

    /**
     * Extracts the resources of a client jar.
     *
     * <p>The class files and the signature of the jar are left out: the first ones are rebuilt
     * from the sources, the second one does not describe the patched jar any more.</p>
     *
     * @param clientJar the jar of the client
     * @param target    the folder the resources are written to
     * @return the number of files that were written
     * @throws IOException when the jar cannot be read or a file cannot be written
     */
    public static int extract(Path clientJar, Path target) throws IOException {
        int written = 0;

        try (ZipFile zip = new ZipFile(clientJar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }

                String name = entry.getName().replace('\\', '/');
                if (name.endsWith(".class") || isSignature(name)) {
                    continue;
                }

                Path file = target.resolve(name);
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }

                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
                }
                written++;
            }
        }

        return written;
    }

    /**
     * Tells whether an entry belongs to the signature or to the manifest of the jar.
     *
     * <p>The manifest of the original jar is a few megabytes of digests, one for every file of
     * the jar. It describes the jar as it was published, so it has no meaning once the classes
     * are replaced.</p>
     *
     * @param name the name of the entry
     * @return whether the entry has to be left out
     */
    public static boolean isSignature(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("META-INF/")) {
            return false;
        }
        return upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC") || upper.equals("META-INF/MANIFEST.MF");
    }
}
