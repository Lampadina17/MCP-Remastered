package mcp.remastered.engine.pipeline.decompile;

import org.jetbrains.java.decompiler.main.decompiler.BaseDecompiler;
import org.jetbrains.java.decompiler.main.decompiler.DirectoryResultSaver;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Runs Vineflower over a Minecraft client jar.
 *
 * <p>Vineflower is driven through its programmatic API rather than its command line so that the
 * pipeline keeps control over the output location and over the logging.</p>
 */
public final class VineflowerDecompiler {

    private final int threadCount;

    public VineflowerDecompiler(int threadCount) {
        this.threadCount = threadCount;
    }

    /**
     * Decompiles a jar into a folder of Java sources.
     *
     * @param clientJar the jar to decompile
     * @param outputDir the folder that receives the decompiled sources
     * @param libraries extra jars put on the decompiler classpath, may be empty
     */
    public void decompile(Path clientJar, Path outputDir, Iterable<Path> libraries) {
        File destination = outputDir.toFile();
        if (!destination.exists() && !destination.mkdirs()) {
            throw new IllegalStateException("Cannot create " + destination);
        }

        Map<String, Object> options = new TreeMap<>(IFernflowerPreferences.getDefaults());
        options.put("thread-count", String.valueOf(threadCount));
        options.put("log-level", "ERROR");
        options.put("decompile-generics", "1");
        options.put("decompile-enums", "1");
        options.put("remove-bridge", "1");
        options.put("hide-default-constructor", "1");

        BaseDecompiler decompiler = new BaseDecompiler(new DirectoryResultSaver(destination), options, new SilentLogger());

        for (Path library : libraries) {
            File file = library.toFile();
            if (file.isFile()) {
                decompiler.addLibrary(file);
            }
        }

        decompiler.addSource(clientJar.toFile());
        decompiler.decompileContext();
    }

    /**
     * A logger that keeps the console readable; only real problems are surfaced.
     */
    private static final class SilentLogger extends IFernflowerLogger {
        @Override
        public void writeMessage(String message, Severity severity) {
            if (severity == Severity.ERROR && isRealProblem(message)) {
                System.err.println("  ! vineflower: " + message);
            }
        }

        @Override
        public void writeMessage(String message, Severity severity, Throwable throwable) {
            if (severity == Severity.ERROR && isRealProblem(message)) {
                System.err.println("  ! vineflower: " + message);
            }
        }

        /**
         * Tells whether a message describes a problem worth showing.
         *
         * <p>A class that is part of several sources is reported as an error by Vineflower even
         * though it is perfectly normal, so those messages are dropped.</p>
         *
         * @param message the message of the decompiler
         * @return {@code true} when the message should be shown
         */
        private static boolean isRealProblem(String message) {
            return !message.startsWith("Skipping processing already existing class");
        }

        @Override
        public void writeMessage(String message, Throwable throwable) {
        }

        @Override
        public void startProcessingClass(String className) {
        }

        @Override
        public void endProcessingClass() {
        }

        @Override
        public void startReadingClass(String className) {
        }

        @Override
        public void endReadingClass() {
        }

        @Override
        public void startClass(String className) {
        }

        @Override
        public void endClass() {
        }

        @Override
        public void startMethod(String methodName) {
        }

        @Override
        public void endMethod() {
        }

        @Override
        public void startWriteClass(String className) {
        }

        @Override
        public void endWriteClass() {
        }
    }
}
