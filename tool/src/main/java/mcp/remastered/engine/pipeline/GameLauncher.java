package mcp.remastered.engine.pipeline;

import com.google.gson.Gson;
import mcp.remastered.engine.mojang.VersionJson;
import mcp.remastered.engine.workspace.Workspace;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Starts the game on the current IDE classpath, so its compiled sources remain debuggable.
 */
public final class GameLauncher {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    private GameLauncher() {
    }

    public static void launch(Workspace workspace, String versionId, String username, String uuid, String token, Path gameDir) throws Exception {
        Path metadata = workspace.metadata(versionId);
        if (!Files.isRegularFile(metadata))
            throw new IOException("Version metadata not found: " + metadata + "; prepare the version first.");
        VersionJson version = new Gson().fromJson(Files.readString(metadata), VersionJson.class);
        if (version == null || version.mainClass == null || !versionId.equals(version.id))
            throw new IOException("Invalid version metadata: " + metadata);
        Path natives = workspace.natives(versionId).toAbsolutePath();
        if (!Files.isDirectory(natives)) throw new IOException("Native libraries not found: " + natives);
        Files.createDirectories(gameDir);
        applySystemProperties(version, natives);
        List<String> args = gameArguments(version, workspace, username, uuid, token, gameDir);
        Class<?> main;
        try {
            main = Class.forName(version.mainClass);
        } catch (ClassNotFoundException e) {
            throw new IOException("Game main class is not on the current classpath. Compile the game sources in the IDE first: " + version.mainClass, e);
        }
        invokeMain(main, args.toArray(String[]::new));
    }

    static void invokeMain(Class<?> main, String[] args) throws Exception {
        MethodHandle entry = MethodHandles.publicLookup().findStatic(main, "main", MethodType.methodType(void.class, String[].class));
        try {
            entry.invokeExact(args);
        } catch (Throwable cause) {
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new RuntimeException(cause);
        }
    }

    static void applySystemProperties(VersionJson version, Path natives) throws IOException {
        if (version.arguments == null || version.arguments.jvm == null) return;
        String launcherVersion = GameLauncher.class.getPackage().getImplementationVersion();
        Map<String, String> values = Map.of("natives_directory", natives.toAbsolutePath().toString(), "launcher_name", "MCP Remastered", "launcher_version", launcherVersion == null ? "development" : launcherVersion);
        for (VersionJson.Argument argument : version.arguments.jvm) {
            if (!allowed(argument.rules)) continue;
            for (String value : argument.asList()) {
                if (!value.startsWith("-D")) continue;
                int equals = value.indexOf('=');
                String name = equals < 0 ? value.substring(2) : value.substring(2, equals);
                if (name.isBlank()) throw new IOException("Invalid JVM property in version metadata: " + value);
                String property = equals < 0 ? "" : resolve(value.substring(equals + 1), values);
                System.setProperty(name, property);
            }
        }
    }

    static List<String> gameArguments(VersionJson version, Workspace workspace, String username, String uuid, String token, Path gameDir) throws IOException {
        if (version.arguments == null || version.arguments.game == null || version.assetIndex == null)
            throw new IOException("Game arguments or asset index are missing from version metadata");
        if (username == null || username.isBlank() || token == null || token.isBlank())
            throw new IOException("Username and token must not be empty");
        if (uuid == null || !uuid.matches("(?i)([0-9a-f]{32}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"))
            throw new IOException("UUID must contain 32 hexadecimal characters, with optional standard hyphens");
        Map<String, String> values = Map.ofEntries(Map.entry("auth_player_name", username), Map.entry("version_name", version.id), Map.entry("game_directory", gameDir.toAbsolutePath().toString()), Map.entry("assets_root", workspace.assets().toAbsolutePath().toString()), Map.entry("assets_index_name", version.assetIndex.id), Map.entry("auth_uuid", uuid.replace("-", "")), Map.entry("auth_access_token", token), Map.entry("clientid", "0"), Map.entry("auth_xuid", "0"), Map.entry("version_type", version.type == null ? "release" : version.type), Map.entry("user_type", "legacy"), Map.entry("user_properties", "{}"));
        List<String> result = new ArrayList<>();
        for (VersionJson.Argument argument : version.arguments.game) {
            if (!allowed(argument.rules)) continue;
            for (String value : argument.asList()) {
                result.add(resolve(value, values));
            }
        }
        result.add("--offlineDeveloperMode");
        return result;
    }

    private static String resolve(String value, Map<String, String> values) throws IOException {
        Matcher matcher = PLACEHOLDER.matcher(value);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            String replacement = values.get(matcher.group(1));
            if (replacement == null) throw new IOException("Unsupported launcher placeholder: " + matcher.group());
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private static boolean allowed(List<VersionJson.Rule> rules) {
        if (rules == null || rules.isEmpty()) return true;
        boolean allowed = false;
        for (VersionJson.Rule rule : rules) {
            if (rule.features != null && rule.features.values().stream().anyMatch(Boolean.TRUE::equals)) continue;
            VersionJson.Rule probe = new VersionJson.Rule();
            probe.action = "allow";
            probe.os = rule.os;
            if (Pipeline.appliesToCurrentPlatform(List.of(probe))) allowed = "allow".equals(rule.action);
        }
        return allowed;
    }
}
