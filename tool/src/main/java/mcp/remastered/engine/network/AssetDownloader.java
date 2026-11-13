package mcp.remastered.engine.network;

import mcp.remastered.engine.mojang.AssetIndex;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Downloads every asset referenced by an {@link AssetIndex} and lays them out on disk the way the
 * official launcher does.
 *
 * <p>Modern Minecraft versions ship their assets as content addressed blobs: the index maps a
 * resource path such as {@code minecraft/font/alt.json} to the SHA-1 hash of the file, and the
 * bytes are served from {@code https://resources.download.minecraft.net/<xx>/<hash>}.</p>
 *
 * <p>The assets are stored as {@code objects/<xx>/<hash>}, which is the layout of a
 * {@code .minecraft} folder: the game looks the file up by the hash the index gives it, and
 * nothing else has to be kept around.</p>
 */
public final class AssetDownloader {

    /**
     * Base URL of the asset CDN.
     */
    private static final String ASSET_CDN = "https://resources.download.minecraft.net/";

    /**
     * Number of assets downloaded in parallel.
     *
     * <p>Almost every asset is a tiny file, so the download is bound by the number of requests
     * rather than by the bandwidth. More connections therefore mean a shorter download, up to the
     * point where the CDN stops answering as fast.</p>
     */
    private static final int THREADS = 16;

    private final Http http;

    /**
     * The base URL the assets are fetched from, overridable for the tests.
     */
    private final String cdn;

    /**
     * One lock per blob, so that the assets sharing a hash do not race each other.
     */
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    public AssetDownloader(Http http) {
        this(http, ASSET_CDN);
    }

    /**
     * Creates a downloader that fetches the assets from another location.
     *
     * @param http the HTTP layer to use
     * @param cdn  the base URL of the assets
     */
    public AssetDownloader(Http http, String cdn) {
        this.http = http;
        this.cdn = cdn;
    }

    /**
     * Downloads all assets of an index.
     *
     * @param index      the asset index to download
     * @param objectsDir the content addressed store that receives the assets
     * @return the number of assets that were downloaded
     * @throws IOException when the download fails
     */
    public int downloadAll(AssetIndex index, Path objectsDir) throws IOException {
        List<Map.Entry<String, AssetIndex.Entry>> entries = new ArrayList<>(index.objects.entrySet());
        entries.sort(Map.Entry.comparingByKey());

        AtomicInteger completed = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        AtomicInteger failed = new AtomicInteger();

        System.out.println("  Downloading " + entries.size() + " assets...");

        // A fork join pool is a good fit here: the assets have very different sizes, so the work
        // stealing keeps the connections busy instead of leaving them waiting on a big file.
        ForkJoinPool pool = new ForkJoinPool(THREADS);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>(entries.size());

            for (Map.Entry<String, AssetIndex.Entry> entry : entries) {
                futures.add(pool.submit(() -> {
                    try {
                        long size = downloadOne(entry.getValue(), objectsDir);
                        bytes.addAndGet(size);
                    } catch (IOException e) {
                        failed.incrementAndGet();
                        System.err.println("  ! failed to download asset " + entry.getKey() + ": " + e.getMessage());
                    } finally {
                        int done = completed.incrementAndGet();
                        if (done % 250 == 0 || done == entries.size()) {
                            System.out.printf("  %d/%d assets (%.1f MB)%n", done, entries.size(), bytes.get() / 1024.0 / 1024.0);
                        }
                    }
                    return null;
                }));
            }

            for (java.util.concurrent.Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while downloading assets", e);
                } catch (java.util.concurrent.ExecutionException e) {
                    // Failures are already reported by the worker itself.
                }
            }
        } finally {
            pool.shutdown();
            try {
                pool.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (failed.get() > 0) {
            System.err.println("  ! " + failed.get() + " assets could not be downloaded");
        }
        return entries.size() - failed.get();
    }

    /**
     * Downloads a single asset and stores it hashed.
     *
     * @param entry      the hash and size of the asset
     * @param objectsDir the content addressed store
     * @return the number of bytes written
     * @throws IOException when the download fails
     */
    private long downloadOne(AssetIndex.Entry entry, Path objectsDir) throws IOException {
        String hash = entry.hash;
        String objectPath = hash.substring(0, 2) + "/" + hash;
        Path objectFile = objectsDir.resolve(objectPath);

        // Several assets can share the same hash, so the download of a blob is done once and the
        // others wait for it instead of racing each other over the same file.
        synchronized (lockFor(hash)) {
            http.downloadIfNeeded(cdn + objectPath, objectFile, entry.size, hash);
        }

        return entry.size;
    }

    /**
     * Returns the lock that guards the download of a blob.
     *
     * @param hash the hash of the blob
     * @return the lock to hold while the blob is fetched
     */
    private Object lockFor(String hash) {
        return locks.computeIfAbsent(hash, key -> new Object());
    }
}
