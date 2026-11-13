package mcp.remastered.engine.mojang;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * The root of {@code https://piston-meta.mojang.com/mc/game/version_manifest_v2.json}.
 *
 * <p>The manifest is the entry point of the whole pipeline: it lists every version Mojang has
 * ever published together with a direct link to the JSON description of that version.</p>
 */
public final class VersionManifest {

    /**
     * The most recent release and snapshot known to the launcher.
     */
    public static final class Latest {
        @SerializedName("release")
        public String release;

        @SerializedName("snapshot")
        public String snapshot;
    }

    /**
     * A single entry of the {@code versions} array.
     */
    public static final class Entry {
        /**
         * Version identifier, for example {@code 26.3} or {@code 26.4-snapshot-7}.
         */
        public String id;

        /**
         * {@code release}, {@code snapshot}, {@code old_beta} or {@code old_alpha}.
         */
        public String type;

        /**
         * Direct link to the JSON file describing this version.
         */
        public String url;

        /**
         * When the entry was published to the manifest.
         */
        public String time;

        /**
         * When the version was actually released to the players.
         */
        @SerializedName("releaseTime")
        public String releaseTime;

        /**
         * SHA-1 of the version JSON referenced by {@link #url}.
         */
        public String sha1;

        @SerializedName("complianceLevel")
        public int complianceLevel;
    }

    @SerializedName("latest")
    public Latest latest;

    @SerializedName("versions")
    public List<Entry> versions;
}
