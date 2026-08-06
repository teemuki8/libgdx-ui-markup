package dev.gdx.markup.preview;

import java.nio.file.Path;
import java.util.Objects;

/** Strict, bounded CLI options for the preview application. */
public record CliOptions(
        Path ui,
        Path css,
        Path skin,
        int frames,
        Path screenshot,
        boolean exit,
        boolean mcp) {
    /** Validates the immutable shape. */
    public CliOptions {
        Objects.requireNonNull(ui, "ui");
        Objects.requireNonNull(css, "css");
        if (frames < 0 || frames > 1_000_000) {
            throw new IllegalArgumentException("--frames must be between 0 and 1000000");
        }
    }

    /** Parses the argument vector; unknown or malformed flags fail with a usage message. */
    public static CliOptions parse(String[] args) {
        Path ui = null;
        Path css = null;
        Path skin = null;
        Path screenshot = null;
        int frames = 0;
        boolean exit = false;
        boolean mcp = false;
        for (int index = 0; index < args.length; index++) {
            switch (args[index]) {
                case "--ui" -> ui = Path.of(requireValue(args, ++index, "--ui"));
                case "--css" -> css = Path.of(requireValue(args, ++index, "--css"));
                case "--skin" -> skin = Path.of(requireValue(args, ++index, "--skin"));
                case "--screenshot" -> screenshot =
                        Path.of(requireValue(args, ++index, "--screenshot"));
                case "--frames" -> {
                    String value = requireValue(args, ++index, "--frames");
                    try {
                        frames = Integer.parseInt(value);
                    } catch (NumberFormatException failure) {
                        throw new IllegalArgumentException(
                                "--frames expects an integer, got \"" + value + "\"");
                    }
                }
                case "--exit" -> exit = true;
                case "--mcp" -> mcp = true;
                default -> throw new IllegalArgumentException("unknown option \"" + args[index]
                        + "\"");
            }
        }
        if (ui == null || css == null) {
            throw new IllegalArgumentException("--ui and --css are required");
        }
        return new CliOptions(ui, css, skin, frames, screenshot, exit, mcp);
    }

    private static String requireValue(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException(flag + " requires a value");
        }
        return args[index];
    }

    /** Prints the bounded usage summary. */
    public static String usage() {
        return """
                Usage: gdx-ui-markup-preview --ui <file.xml> --css <file.css> [options]

                Options:
                  --ui <file.xml>        markup document (required)
                  --css <file.css>       stylesheet (required)
                  --skin <file.json>     optional libGDX skin JSON
                  --frames <n>           render n frames then stop (CI)
                  --screenshot <path>    write a PNG after the requested frames (CI)
                  --exit                 exit 0 after frames; exit 2 on build error (CI)
                  --mcp                  serve the harness MCP protocol over stdio
                """;
    }
}
