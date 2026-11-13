package mcp.remastered.engine.pipeline;

import mcp.remastered.engine.mojang.VersionJson;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts the native libraries of the game.
 *
 * <p>Since the 1.19 versions Mojang ships the natives as separate jars, one per operating
 * system, and the version describes where every group of them has to end up with a system
 * property: the shared libraries of LWJGL go into {@code org.lwjgl.system.SharedLibraryExtractPath},
 * the ones JNA loads go into {@code java.library.path}, and so on.</p>
 *
 * <p>The layout inside a jar is kept as it is, because that is the path the libraries look
 * themselves up with: {@code windows/x64/org/lwjgl/lwjgl.dll} and the like.</p>
 */
public final class NativesExtractor {

    /**
     * The properties of the version that say where the native libraries have to be found.
     */
    private static final List<String> NATIVE_PROPERTIES = List.of(
            "java.library.path",
            "jna.tmpdir",
            "org.lwjgl.system.SharedLibraryExtractPath",
            "io.netty.native.workdir"
    );

    private final String architecture;

    /**
     * Creates an extractor for the architecture of the machine.
     *
     * @param architecture the architecture, for example {@code x86_64}
     */
    public NativesExtractor(String architecture) {
        this.architecture = architecture;
    }

    /**
     * Extracts the native libraries of the version into the folders its arguments point at.
     *
     * @param version   the version to extract the natives of
     * @param libraries the libraries that apply to this machine
     * @param target    the folder the natives are extracted to
     * @return the number of files that were extracted
     * @throws IOException when a jar cannot be read
     */
    public int extract(VersionJson version, List<Path> libraries, Path target) throws IOException {
        Map<String, Path> destinations = destinations(version, target);

        // Every folder the version points at has to be there: the loaders of the native
        // libraries look themselves up in them and some of them do not create them.
        for (String property : NATIVE_PROPERTIES) {
            Path destination = destinations.get(property);
            if (destination != null && destination.isAbsolute()) {
                Files.createDirectories(destination);
            }
        }

        int extracted = 0;
        for (Path library : libraries) {
            String name = library.getFileName().toString();
            if (!name.endsWith(".jar")) {
                continue;
            }
            extracted += extract(library, destination(library, destinations, target));
        }
        return extracted;
    }

    /**
     * Reads the folder the natives of a library have to end up in.
     *
     * <p>Every native library ships in a jar of its own and the version says where each of them
     * has to be found: LWJGL extracts its own in the folder it is given, the native transports of
     * netty in the folder of netty, and everything else, the profiler and the libraries JNA
     * loads, in the ordinary library path.</p>
     *
     * @param library      the jar the natives come from
     * @param destinations the folders the version points at
     * @param target       the folder the natives are extracted to
     * @return the folder the natives of the library have to go in
     */
    private Path destination(Path library, Map<String, Path> destinations, Path target) {
        String name = library.getFileName().toString();
        if (name.startsWith("lwjgl")) {
            return destinations.getOrDefault("org.lwjgl.system.SharedLibraryExtractPath", target);
        }
        if (name.contains("netty-transport-native")) {
            return destinations.getOrDefault("io.netty.native.workdir", target);
        }
        return destinations.getOrDefault("java.library.path", target);
    }

    /**
     * Reads the folders the system properties of the version point at.
     *
     * @param version the version being launched
     * @param target  the folder the natives are extracted to
     * @return the folders, keyed by the name of the property
     * @throws IOException when the arguments cannot be read
     */
    private Map<String, Path> destinations(VersionJson version, Path target) throws IOException {
        Map<String, Path> destinations = new LinkedHashMap<>();
        if (version.arguments == null || version.arguments.jvm == null) {
            return destinations;
        }

        for (VersionJson.Argument argument : version.arguments.jvm) {
            for (String value : argument.asList()) {
                if (!value.startsWith("-D")) {
                    continue;
                }
                int equals = value.indexOf('=');
                if (equals < 0) {
                    continue;
                }
                String name = value.substring(2, equals);
                String path = value.substring(equals + 1).replace("${natives_directory}", target.toAbsolutePath().toString());
                destinations.put(name, Path.of(path));
            }
        }
        return destinations;
    }

    /**
     * Extracts the natives of a single jar.
     *
     * @param jar         the jar to read
     * @param destination the folder the natives are extracted to
     * @return the number of files that were extracted
     * @throws IOException when the jar cannot be read
     */
    public int extract(Path jar, Path destination) throws IOException {
        int extracted = 0;

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                if (!isNative(entry.getName())) {
                    continue;
                }

                Path target = destination.resolve(entry.getName().replace('\\', '/'));
                if (matchesEntry(target, entry)) continue;
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                extracted++;
            }
        }
        return extracted;
    }

    private static boolean matchesEntry(Path target, ZipEntry entry) throws IOException {
        if (!Files.isRegularFile(target) || entry.getSize() < 0 || entry.getCrc() < 0 || Files.size(target) != entry.getSize())
            return false;
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        try (InputStream in = Files.newInputStream(target)) {
            byte[] bytes = new byte[65536];
            int read;
            while ((read = in.read(bytes)) != -1) crc.update(bytes, 0, read);
        }
        return crc.getValue() == entry.getCrc();
    }

    /**
     * Tells whether an entry of a natives jar belongs to this machine.
     *
     * <p>The archives follow the layout of the official launcher: the natives of the machine sit
     * under a folder named after the architecture, for example {@code windows/x64/}. The libraries
     * that are not tied to an architecture, such as the profiler, sit at the root.</p>
     *
     * @param name the name of the entry
     * @return whether the entry has to be extracted
     */
    boolean isNative(String name) {
        String lower = name.toLowerCase(Locale.ROOT);

        // The natives of the other architectures are of no use here.
        if (lower.contains("/x86/") || lower.startsWith("x86/")) {
            return architecture.equals("x86");
        }
        if (lower.contains("/aarch64/") || lower.startsWith("aarch64/") || lower.contains("/arm64/") || lower.startsWith("arm64/")) {
            return architecture.equals("aarch64") || architecture.equals("arm64");
        }

        return lower.endsWith(".dll") || lower.endsWith(".so") || lower.endsWith(".dylib") || lower.endsWith(".jnilib");
    }
}
