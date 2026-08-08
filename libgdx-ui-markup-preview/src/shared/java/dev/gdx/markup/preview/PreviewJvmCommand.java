package dev.gdx.markup.preview;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Centralized platform-aware construction of the JVM command that launches a preview child
 * process. Every launch site that starts a JVM which may initialize LWJGL/GLFW — the
 * production preview launcher (IntelliJ plugin distribution) and the test/GL-probe child
 * launcher — builds its command through this class so the macOS-only
 * {@code -XstartOnFirstThread} option is applied exactly once, in the same deterministic
 * position, before the classpath and the main class.
 *
 * <p>On macOS, GLFW (via Cocoa/AppKit) requires the Java main thread to be the process's
 * first thread; a child JVM started without {@code -XstartOnFirstThread} fails at GLFW
 * initialization with {@code GLFW may only be used on the main thread ...}. No other
 * platform needs an extra JVM flag, and {@code -XstartOnFirstThread} is a macOS-only
 * HotSpot option that other platforms reject.
 *
 * <p>The command shape is always {@code java <platform-flags> <extra-flags> -cp
 * <classpath> <main-class> <program-args...>}: platform flags (when any) come immediately
 * after the {@code java} executable and always before {@code -cp} and the main class.
 *
 * <p>This class is compiled by both the preview module (Java 25 project toolchain) and the
 * IDEA module (its own Java 21 toolchain) from the same shared source, so it uses only
 * stable JDK APIs available in both.
 */
public final class PreviewJvmCommand {

    private PreviewJvmCommand() {
    }

    /** Whether {@code osName} identifies macOS (any official {@code os.name} spelling). */
    public static boolean isMac(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("mac");
    }

    /**
     * Extra JVM flags a preview/GL child requires on {@code osName} before any other option.
     * macOS requires {@code -XstartOnFirstThread}; every other platform needs nothing.
     */
    public static List<String> platformJvmFlags(String osName) {
        return isMac(osName) ? List.of("-XstartOnFirstThread") : List.of();
    }

    /** The {@code java} executable of the current JVM. */
    public static String javaBin() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /**
     * Builds the full child JVM command: {@code java <platform-flags> <jvmFlags> -cp
     * <classpath> <mainClass> <programArgs...>}. Platform flags (e.g. macOS
     * {@code -XstartOnFirstThread}) are always inserted before {@code -cp} and the main
     * class, so every preview/GL child that initializes LWJGL receives them in the
     * deterministic position the JVM requires.
     */
    public static List<String> build(String javaBin, List<String> jvmFlags, String classpath,
            String mainClass, List<String> programArgs, String osName) {
        List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.addAll(platformJvmFlags(osName));
        command.addAll(jvmFlags);
        command.add("-cp");
        command.add(classpath);
        command.add(mainClass);
        command.addAll(programArgs);
        return command;
    }
}
