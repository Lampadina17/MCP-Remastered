package mcp.remastered.engine.mojang;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/**
 * The JSON document that describes a single Minecraft version.
 *
 * <p>It is fetched from the {@code url} of a {@link VersionManifest.Entry} and contains everything
 * the pipeline needs: the downloadable artifacts, the libraries, the asset index and the Java
 * runtime the game expects.</p>
 */
public final class VersionJson {

    /**
     * The parser used for the rules of the arguments.
     */
    private static final Gson GSON = new Gson();

    /**
     * A downloadable file with its expected hash and size.
     */
    public static final class Download {
        public String sha1;
        public long size;
        public String url;
    }

    /**
     * The {@code downloads} block of a version.
     */
    public static final class Downloads {
        public Download client;
        public Download server;

        /**
         * Official ProGuard mappings, only present on the older (pre 26.x) versions.
         */
        @SerializedName("client_mappings")
        public Download clientMappings;

        @SerializedName("server_mappings")
        public Download serverMappings;
    }

    /**
     * The artifact of a single library.
     */
    public static final class LibraryArtifact {
        /**
         * Path of the file relative to the libraries folder, for example {@code com/mojang/...}.
         */
        public String path;
        public String sha1;
        public long size;
        public String url;
    }

    /**
     * The downloadable files of a library.
     */
    public static final class LibraryDownloads {
        public LibraryArtifact artifact;

        /**
         * Native classifier variant of the library, when present.
         */
        public LibraryArtifact classifier;
    }

    /**
     * Operating system constraint of a rule.
     */
    public static final class OsSpec {
        public String name;

        /**
         * The range of versions the rule applies to.
         */
        public VersionRange versionRange;

        public String arch;
    }

    /**
     * A range of operating system versions.
     *
     * <p>Both bounds are dotted numbers, for example {@code 10.0.17134}.</p>
     */
    public static final class VersionRange {
        /**
         * The lowest version of the range.
         */
        public String min;

        /**
         * The highest version of the range.
         */
        public String max;
    }

    /**
     * A single rule deciding whether a library applies to the current machine.
     */
    public static final class Rule {
        /**
         * {@code allow} or {@code disallow}.
         */
        public String action;
        public OsSpec os;

        /**
         * The features of the session the rule depends on.
         */
        public java.util.Map<String, Boolean> features;
    }

    /**
     * A library entry.
     */
    public static final class Library {
        /**
         * Maven style coordinates, for example {@code com.mojang:jtracy:1.14.38}.
         */
        public String name;
        public LibraryDownloads downloads;
        public List<Rule> rules;
    }

    /**
     * The asset index describing every resource file of the game.
     */
    public static final class AssetIndex {
        /**
         * Identifier of the index, also used as the folder name of the index file.
         */
        public String id;
        public String sha1;
        public long size;

        /**
         * Total size in bytes of every asset referenced by the index.
         */
        @SerializedName("totalSize")
        public long totalSize;

        public String url;
    }

    /**
     * The Java runtime the game wants to run on.
     */
    public static final class JavaVersion {
        public String component;
        @SerializedName("majorVersion")
        public int majorVersion;
    }

    /**
     * The log4j configuration used by the client.
     *
     * <p>The file of the configuration is a download, and the argument and the format describe how
     * the game is told about it.</p>
     */
    public static final class Logging {
        /**
         * The configuration of the client.
         */
        public LoggingConfig client;

        /**
         * The configuration of the server.
         */
        public LoggingConfig server;
    }

    /**
     * The log4j configuration of one side of the game.
     */
    public static final class LoggingConfig {
        /**
         * The system property that points at the configuration file.
         */
        public String argument;

        /**
         * The file of the configuration.
         */
        public Download file;

        /**
         * The format of the file, for example {@code log4j2-xml}.
         */
        public String type;
    }

    /**
     * An argument of the game, with the rules that decide whether it applies.
     *
     * <p>Both the JVM arguments and the game arguments are a list of these: a plain string is
     * always used, while one carrying rules is used only when they all match.</p>
     */
    public static final class Argument {
        /**
         * The argument itself, either one string or a list of them.
         */
        public Object value;

        /**
         * The rules that decide whether the argument applies.
         */
        public List<Rule> rules;

        /**
         * The argument as a list of strings.
         *
         * @return the single string, or the list when the argument carries several
         */
        @SuppressWarnings("unchecked")
        public List<String> asList() {
            if (value instanceof com.google.gson.JsonArray array) {
                // The default arguments come in groups of arguments, sometimes wrapped in
                // another array.
                List<String> strings = new ArrayList<>(array.size());
                for (com.google.gson.JsonElement group : array) {
                    if (group.isJsonArray()) {
                        for (com.google.gson.JsonElement member : group.getAsJsonArray()) {
                            strings.add(member.getAsString());
                        }
                    } else {
                        strings.add(group.getAsString());
                    }
                }
                return strings;
            }
            if (value instanceof List<?> list) {
                List<String> strings = new ArrayList<>(list.size());
                for (Object element : list) {
                    if (element instanceof com.google.gson.JsonArray group) {
                        for (com.google.gson.JsonElement member : group) {
                            strings.add(member.getAsString());
                        }
                    } else {
                        strings.add(String.valueOf(element));
                    }
                }
                return strings;
            }
            return List.of(String.valueOf(value));
        }

        /**
         * Reads an argument that may be a plain string or an object with rules.
         *
         * @param json the element of the array
         * @return the argument
         */
        static Argument from(com.google.gson.JsonElement json) {
            Argument argument = new Argument();
            if (json.isJsonPrimitive()) {
                argument.value = json.getAsString();
                return argument;
            }

            com.google.gson.JsonObject object = json.getAsJsonObject();
            if (object.has("value")) {
                argument.value = object.get("value").isJsonArray() ? object.getAsJsonArray("value") : object.get("value").getAsString();
            }
            if (object.has("rules")) {
                argument.rules = GSON.fromJson(object.getAsJsonArray("rules"), new com.google.gson.reflect.TypeToken<List<Rule>>() {
                }.getType());
            }
            return argument;
        }
    }

    /**
     * The arguments of the game, split into the ones of the JVM and the ones of the program.
     *
     * <p>The lists mix plain strings with objects carrying rules, so they need a deserializer of
     * their own.</p>
     */
    public static final class Arguments {
        /**
         * The JVM arguments that the launcher adds by default.
         */
        @SerializedName("default-user-jvm")
        public List<Argument> defaultUserJvm;

        /**
         * The JVM arguments of the version.
         */
        public List<Argument> jvm;

        /**
         * The arguments of the game itself.
         */
        public List<Argument> game;

        /**
         * Reads a list of arguments, plain strings included.
         *
         * @param json the array to read
         * @return the arguments
         */
        public static List<Argument> listFrom(com.google.gson.JsonArray json) {
            List<Argument> arguments = new ArrayList<>(json.size());
            for (com.google.gson.JsonElement element : json) {
                arguments.add(Argument.from(element));
            }
            return arguments;
        }

        /**
         * Reads the default JVM arguments, which come in groups.
         *
         * <p>Unlike the other arguments, these are objects whose value is a list of arguments
         * that belong together, sometimes wrapped in another list.</p>
         *
         * @param json the array to read
         * @return the arguments, one per group
         */
        public static List<Argument> groupsFrom(com.google.gson.JsonArray json) {
            List<Argument> arguments = new ArrayList<>(json.size());
            for (com.google.gson.JsonElement element : json) {
                arguments.add(Argument.from(element));
            }
            return arguments;
        }
    }

    public String id;
    public String type;

    /**
     * Identifier of the asset index in use, mirrors {@link AssetIndex#id}.
     */
    public String assets;

    @SerializedName("assetIndex")
    public AssetIndex assetIndex;

    @SerializedName("downloads")
    public Downloads downloads;

    @SerializedName("libraries")
    public List<Library> libraries;

    @SerializedName("javaVersion")
    public JavaVersion javaVersion;

    @SerializedName("logging")
    public Logging logging;

    @SerializedName("arguments")
    public Arguments arguments;

    @SerializedName("mainClass")
    public String mainClass;

    @SerializedName("releaseTime")
    public String releaseTime;
}