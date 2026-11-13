package mcp.remastered.engine.mojang;


import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Filters and orders the versions of a manifest.
 *
 * <p>The manifest contains the entire history of Minecraft, from the first alpha build to the
 * newest snapshot. The pipeline only cares about the modern {@code 26.1} era and later, and it
 * separates those versions into the two channels the user can pick from: stable releases and
 * snapshots.</p>
 */
public final class VersionSelector {

    private final List<VersionManifest.Entry> releases = new ArrayList<>();
    private final List<VersionManifest.Entry> snapshots = new ArrayList<>();

    /**
     * Builds a selector from a manifest.
     *
     * <p>Both channels are ordered from the newest version to the oldest one, which is the order
     * the interactive picker presents them in.</p>
     *
     * @param manifest the manifest to filter
     * @return the selector
     */
    public static VersionSelector from(VersionManifest manifest) {
        VersionSelector selector = new VersionSelector();

        String releaseTimeOf261 = manifest.versions.stream().filter(entry -> "26.1".equals(entry.id)).map(entry -> entry.releaseTime).findFirst().orElse(null);

        for (VersionManifest.Entry entry : manifest.versions) {
            MinecraftVersion version;
            try {
                version = MinecraftVersion.parse(entry.id);
            } catch (IllegalArgumentException e) {
                // Ancient identifiers such as "rd-160052" are not supported.
                continue;
            }

            if (!version.isAtLeast261(releaseTimeOf261, entry.releaseTime)) {
                continue;
            }

            switch (entry.type) {
                case "release" -> selector.releases.add(entry);
                case "snapshot" -> selector.snapshots.add(entry);
                default -> {
                    // old_beta and old_alpha are long gone and are never in range anyway.
                }
            }
        }

        Comparator<VersionManifest.Entry> byNewestFirst = Comparator.comparing((VersionManifest.Entry entry) -> entry.releaseTime, Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(entry -> entry.id);

        selector.releases.sort(byNewestFirst);
        selector.snapshots.sort(byNewestFirst);

        return selector;
    }

    /**
     * The stable releases of the supported range, newest first.
     */
    public List<VersionManifest.Entry> releases() {
        return List.copyOf(releases);
    }

    /**
     * The snapshots of the supported range, newest first.
     */
    public List<VersionManifest.Entry> snapshots() {
        return List.copyOf(snapshots);
    }

    /**
     * The channel the user is asked to choose between.
     */
    public enum Channel {
        /**
         * Regular releases such as {@code 26.3}.
         */
        STABLE("stable"),
        /**
         * Snapshots and pre-releases such as {@code 26.4-snapshot-7}.
         */
        SNAPSHOT("snapshot");

        private final String label;

        Channel(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * Returns the versions of a channel, ordered from the newest to the oldest one.
     *
     * @param channel the channel to list
     * @return the entries of that channel
     */
    public List<VersionManifest.Entry> versions(Channel channel) {
        return channel == Channel.STABLE ? releases() : snapshots();
    }
}