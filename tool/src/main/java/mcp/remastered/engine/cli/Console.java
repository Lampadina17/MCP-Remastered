package mcp.remastered.engine.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A tiny helper around {@link System#in} and {@link System#out} used by the interactive parts of
 * the pipeline.
 */
public final class Console {

    private final BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    /**
     * Prints a line to the console.
     */
    public void println(String message) {
        System.out.println(message);
    }

    /**
     * Prints an empty line to the console.
     */
    public void println() {
        System.out.println();
    }

    /**
     * Prints a line to the console, prefixed with a bullet.
     */
    public void item(String message) {
        System.out.println("  " + message);
    }

    /**
     * Reads a single line from the user.
     *
     * @param prompt the prompt shown to the user
     * @return the trimmed answer, or an empty string when the input is closed
     * @throws IOException when the input cannot be read
     */
    public String readLine(String prompt) throws IOException {
        System.out.print(prompt);
        System.out.flush();
        String line = reader.readLine();
        if (line == null) {
            return "";
        }
        return line.trim();
    }

    /**
     * Asks the user to pick one entry of a list.
     *
     * <p>The list is numbered starting at zero, so the first entry is the newest one. The user
     * can answer with the number of the entry, or with the identifier of the version.</p>
     *
     * @param prompt  the question shown to the user
     * @param options the entries to pick from, each one paired with the text to display
     * @return the index of the chosen entry
     * @throws IOException when the input cannot be read
     */
    public int choose(String prompt, List<Option> options) throws IOException {
        for (int i = 0; i < options.size(); i++) {
            item("[" + i + "] " + options.get(i).display());
        }
        println();

        while (true) {
            String answer = readLine(prompt);
            if (answer.isEmpty()) {
                continue;
            }
            try {
                int index = Integer.parseInt(answer);
                if (index >= 0 && index < options.size()) {
                    return index;
                }
            } catch (NumberFormatException ignored) {
                // The user may also type the version identifier.
            }
            for (int i = 0; i < options.size(); i++) {
                if (options.get(i).value().equalsIgnoreCase(answer)) {
                    return i;
                }
            }
            System.out.println("  Please choose a number between 0 and " + (options.size() - 1) + ".");
        }
    }

    /**
     * One entry of a list the user can pick from.
     */
    public record Option(String value, String display) {
    }
}
