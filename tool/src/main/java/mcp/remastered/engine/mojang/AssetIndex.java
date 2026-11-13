package mcp.remastered.engine.mojang;

import com.google.gson.annotations.SerializedName;

import java.util.Map;

/**
 * The asset index of a version.
 *
 * <p>For every resource of the game the index stores the SHA-1 hash and the size of the file.
 * The actual bytes live on the asset CDN under {@code /<first two hex chars>/<full hash>}, so the
 * index is what tells us <em>which</em> file a hash belongs to.</p>
 */
public final class AssetIndex {

    /**
     * A single asset: its content hash and its size in bytes.
     */
    public static final class Entry {
        public String hash;
        public long size;
    }

    /**
     * Every asset of the game, keyed by the path it has inside the game (for example
     * {@code minecraft/font/alt.json}). The key is what lets us rebuild a browsable asset tree
     * instead of a folder full of meaningless hashes.
     */
    @SerializedName("objects")
    public Map<String, Entry> objects;

    /**
     * Legacy virtual resources, kept for completeness with older indices.
     */
    @SerializedName("virtual")
    public Map<String, Entry> virtual;
}
