package mcp.remastered.engine.mojang;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed Minecraft version identifier.
 *
 * <p>Minecraft uses several identifier shapes depending on the era:</p>
 * <ul>
 *   <li>{@code 26.3} and {@code 26.1.2} &mdash; regular releases, optionally with a patch level.</li>
 *   <li>{@code 26.4-snapshot-7}, {@code 26.2-pre-6}, {@code 26.1-rc-3} &mdash; pre-releases that
 *       belong to a specific future release.</li>
 *   <li>{@code 26w14a} &mdash; the legacy week based scheme, still used for some snapshots.</li>
 * </ul>
 *
 * <p>Only the information needed for ordering and for the {@code 26.1+} cut off is kept; the
 * original identifier is always retained so that it can be looked up in the manifest again.</p>
 */
public final class MinecraftVersion implements Comparable<MinecraftVersion> {

    /**
     * The kind of release a version identifier describes.
     */
    public enum Channel {
        /**
         * A regular release such as {@code 26.3}.
         */
        RELEASE,
        /**
         * A snapshot such as {@code 26.4-snapshot-7}.
         */
        SNAPSHOT,
        /**
         * A pre-release such as {@code 26.2-pre-6}.
         */
        PRE_RELEASE,
        /**
         * A release candidate such as {@code 26.1-rc-3}.
         */
        RELEASE_CANDIDATE,
        /**
         * The legacy {@code 26w14a} week based scheme.
         */
        LEGACY_SNAPSHOT
    }

    private static final Pattern DOTTED = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?$");
    private static final Pattern QUALIFIED = Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?-(snapshot|pre|rc)-(\\d+)$");
    private static final Pattern LEGACY = Pattern.compile("^(\\d+)w(\\d+)([a-z])$");

    private final String id;
    private final Channel channel;
    private final int major;
    private final int minor;
    private final int patch;
    private final int qualifier;

    private MinecraftVersion(String id, Channel channel, int major, int minor, int patch, int qualifier) {
        this.id = id;
        this.channel = channel;
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.qualifier = qualifier;
    }

    /**
     * Parses a version identifier.
     *
     * @param id the raw identifier, for example {@code 26.4-snapshot-7}
     * @return the parsed version
     * @throws IllegalArgumentException if the identifier is not a shape Mojang ever published
     */
    public static MinecraftVersion parse(String id) {
        Objects.requireNonNull(id, "id");

        Matcher qualified = QUALIFIED.matcher(id);
        if (qualified.matches()) {
            Channel channel = switch (qualified.group(4)) {
                case "snapshot" -> Channel.SNAPSHOT;
                case "pre" -> Channel.PRE_RELEASE;
                case "rc" -> Channel.RELEASE_CANDIDATE;
                default -> throw new IllegalStateException("unreachable");
            };
            return new MinecraftVersion(id, channel, Integer.parseInt(qualified.group(1)), Integer.parseInt(qualified.group(2)), qualified.group(3) == null ? 0 : Integer.parseInt(qualified.group(3)), Integer.parseInt(qualified.group(5)));
        }

        Matcher dotted = DOTTED.matcher(id);
        if (dotted.matches()) {
            return new MinecraftVersion(id, Channel.RELEASE, Integer.parseInt(dotted.group(1)), Integer.parseInt(dotted.group(2)), dotted.group(3) == null ? 0 : Integer.parseInt(dotted.group(3)), 0);
        }

        Matcher legacy = LEGACY.matcher(id);
        if (legacy.matches()) {
            return new MinecraftVersion(id, Channel.LEGACY_SNAPSHOT, Integer.parseInt(legacy.group(1)), 0, 0, Integer.parseInt(legacy.group(2)));
        }

        throw new IllegalArgumentException("Unknown Minecraft version identifier: " + id);
    }

    /**
     * The raw identifier as published by Mojang.
     */
    public String id() {
        return id;
    }

    public Channel channel() {
        return channel;
    }

    /**
     * The year of the release line, for example {@code 26}.
     */
    public int major() {
        return major;
    }

    /**
     * The release within the line, for example {@code 3} for {@code 26.3}.
     */
    public int minor() {
        return minor;
    }

    /**
     * The patch level, {@code 0} when the identifier does not carry one.
     */
    public int patch() {
        return patch;
    }

    /**
     * The number of the qualifier.
     *
     * <p>For a snapshot such as {@code 26.4-snapshot-7} this is the {@code 7}, and for the legacy
     * week based scheme such as {@code 26w14a} it is the week number.</p>
     */
    public int qualifier() {
        return qualifier;
    }

    /**
     * Whether this version belongs to the {@code 26.1} era or a later one.
     *
     * <p>Legacy week based identifiers such as {@code 26w14a} do not name a release line, so
     * they are resolved by their release date: they are accepted when they were published after
     * the {@code 26.1} release itself.</p>
     *
     * @param releaseTimeOf261  the release date of {@code 26.1}, may be {@code null} if unknown
     * @param releaseTimeOfThis the release date of this version
     * @return {@code true} when the version is part of the supported range
     */
    public boolean isAtLeast261(String releaseTimeOf261, String releaseTimeOfThis) {
        if (major > 26) {
            return true;
        }
        if (major < 26) {
            return false;
        }
        if (channel != Channel.LEGACY_SNAPSHOT) {
            // A regular release or a pre-release names its line, so the minor version decides.
            return minor >= 1;
        }
        // Legacy week scheme: only the release date can tell us where it belongs.
        if (releaseTimeOf261 == null || releaseTimeOfThis == null) {
            return true;
        }
        return releaseTimeOfThis.compareTo(releaseTimeOf261) >= 0;
    }

    @Override
    public int compareTo(MinecraftVersion other) {
        int result = Integer.compare(major, other.major);
        if (result != 0) {
            return result;
        }
        result = Integer.compare(minor, other.minor);
        if (result != 0) {
            return result;
        }
        result = Integer.compare(patch, other.patch);
        if (result != 0) {
            return result;
        }
        result = Integer.compare(channel.ordinal(), other.channel.ordinal());
        if (result != 0) {
            return result;
        }
        return Integer.compare(qualifier, other.qualifier);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof MinecraftVersion other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return id;
    }
}
