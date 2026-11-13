package mcp.remastered.engine.network;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * A thin wrapper around {@link HttpClient} that adds retries, timeouts and hash verification.
 *
 * <p>Everything the pipeline downloads comes from Mojang's CDNs, which occasionally rate limit
 * or hiccup, so a failed request is retried a couple of times before it is given up on.</p>
 */
public final class Http {

    /**
     * Number of attempts made for a single request before it is considered failed.
     */
    private static final int MAX_ATTEMPTS = 4;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);

    private final HttpClient client;

    public Http() {
        this.client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    /**
     * Reuses a verified cached file, otherwise downloads and verifies a replacement.
     */
    public boolean downloadIfNeeded(String url, Path target, long size, String sha1) throws IOException {
        if (Hashing.matches(target, size, sha1)) return false;
        download(url, target, sha1, null);
        if ((size > 0 || (sha1 != null && !sha1.isBlank())) && !Hashing.matches(target, size, sha1)) {
            throw new IOException("Downloaded file has an unexpected size or hash: " + target);
        }
        return true;
    }

    /**
     * Downloads a file to disk, verifying its SHA-1 hash when one is known.
     *
     * <p>The bytes are hashed while they stream past, so the file never has to be read back from
     * the disk to be verified. With thousands of small files that halves the work.</p>
     *
     * @param url          the location to fetch
     * @param target       the file to write
     * @param expectedSha1 the expected SHA-1 hash, or {@code null} to skip verification
     * @param onProgress   receives the number of bytes written so far, may be {@code null}
     * @throws IOException when the download keeps failing or the hash does not match
     */
    public void download(String url, Path target, String expectedSha1, Consumer<Long> onProgress) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // The temporary file has to live on the same volume as the target, otherwise the final
        // move would have to copy the whole file across volumes.
        Path temporary = Files.createTempFile(parent, ".download", ".part");

        try {
            MessageDigest digest = expectedSha1 == null || expectedSha1.isBlank() ? null : MessageDigest.getInstance("SHA-1");

            stream(url, temporary, digest, onProgress);

            if (digest != null) {
                String actual = Hashing.toHex(digest.digest());
                if (!actual.equalsIgnoreCase(expectedSha1)) {
                    throw new IOException("Hash mismatch for " + url + "\n  expected " + expectedSha1 + "\n  actual   " + actual);
                }
            }

            // Two assets can share the same hash and therefore the same target. The first one to
            // arrive wins, the other one keeps what is already there.
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (FileAlreadyExistsException e) {
                // Somebody else wrote the very same content in the meantime.
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is not available in this JVM", e);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Streams a resource into a file, hashing the bytes on the way.
     *
     * @param url        the location to fetch
     * @param target     the file to write
     * @param digest     the digest to feed, may be {@code null}
     * @param onProgress receives the number of bytes written so far, may be {@code null}
     * @return the number of bytes written
     * @throws IOException when the download keeps failing
     */
    private long stream(String url, Path target, MessageDigest digest, Consumer<Long> onProgress) throws IOException {
        IOException lastFailure = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                if (digest != null) digest.reset();
                HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT).header("User-Agent", "MCP-Remastered/1.0").GET().build();

                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    response.body().close();
                    throw new IOException("HTTP " + response.statusCode() + " for " + url);
                }

                long total = 0;
                try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(target)) {
                    byte[] chunk = new byte[1 << 16];
                    int read;
                    while ((read = in.read(chunk)) != -1) {
                        if (digest != null) {
                            digest.update(chunk, 0, read);
                        }
                        out.write(chunk, 0, read);
                        total += read;
                        if (onProgress != null) {
                            onProgress.accept(total);
                        }
                    }
                }
                return total;

            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while downloading " + url, e);
                }
                lastFailure = (IOException) e;
                if (attempt < MAX_ATTEMPTS) {
                    sleepBackoff(attempt);
                }
            }
        }

        throw new IOException("Failed to download " + url + " after " + MAX_ATTEMPTS + " attempts", lastFailure);
    }

    /**
     * Fetches a resource into memory.
     *
     * @param url        the location to fetch
     * @param onProgress receives the number of bytes read so far, may be {@code null}
     * @return the body of the response
     * @throws IOException when the request keeps failing
     */
    public byte[] get(String url, Consumer<Long> onProgress) throws IOException {
        IOException lastFailure = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT).header("User-Agent", "MCP-Remastered/1.0").GET().build();

                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    response.body().close();
                    throw new IOException("HTTP " + response.statusCode() + " for " + url);
                }

                try (InputStream in = response.body()) {
                    return readFully(in, onProgress);
                }
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while downloading " + url, e);
                }
                lastFailure = (IOException) e;
                if (attempt < MAX_ATTEMPTS) {
                    sleepBackoff(attempt);
                }
            }
        }

        throw new IOException("Failed to download " + url + " after " + MAX_ATTEMPTS + " attempts", lastFailure);
    }

    /**
     * Fetches a resource into memory without progress reporting.
     */
    public byte[] get(String url) throws IOException {
        return get(url, null);
    }

    private static byte[] readFully(InputStream in, Consumer<Long> onProgress) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(1 << 16);
        byte[] chunk = new byte[1 << 16];
        long total = 0;

        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
            total += read;
            if (onProgress != null) {
                onProgress.accept(total);
            }
        }
        return buffer.toByteArray();
    }

    private static void sleepBackoff(int attempt) {
        try {
            Thread.sleep(500L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Copies a stream to an output stream without closing either.
     */
    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] chunk = new byte[1 << 16];
        int read;
        while ((read = in.read(chunk)) != -1) {
            out.write(chunk, 0, read);
        }
    }
}
