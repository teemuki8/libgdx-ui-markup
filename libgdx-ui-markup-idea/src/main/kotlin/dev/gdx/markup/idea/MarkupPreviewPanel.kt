package dev.gdx.markup.idea

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.SwingUtilities

/**
 * "Markup Preview" tool window: launches the preview process for the XML file open in the
 * editor, streams bounded {@code markup-status} lines to a status label, and offers
 * Launch / Reload / Watch. All process and file work happens off the EDT; only the status
 * label updates on it.
 */
class MarkupPreviewPanel(private val project: Project, private val toolWindow: ToolWindow) :
    JPanel(BorderLayout()) {
    private val status = JLabel("not launched", JLabel.LEFT)
    private val launchButton = JButton("Launch")
    private val reloadButton = JButton("Reload").apply { isEnabled = false }
    private val watchToggle = JToggleButton("Watch")
    private val debouncer = WatchDebouncer(300_000_000L)
    private val generation = AtomicLong()

    init {
        preferredSize = Dimension(360, 220)
        status.border = javax.swing.BorderFactory.createEmptyBorder(6, 6, 6, 6)
        status.isOpaque = true
        status.background = java.awt.Color.WHITE
        add(status, BorderLayout.CENTER)

        val controls = JPanel(FlowLayout(FlowLayout.LEFT))
        controls.add(launchButton)
        controls.add(reloadButton)
        controls.add(watchToggle)
        add(controls, BorderLayout.SOUTH)

        launchButton.addActionListener { launchForCurrentFile() }
        reloadButton.addActionListener { launchForCurrentFile() }
        watchToggle.addActionListener {
            if (watchToggle.isSelected) {
                startWatcher()
            } else {
                stopWatcher()
            }
        }
    }

    /** Launches (or relaunches) the preview for the XML file selected in the editor. */
    fun launchForCurrentFile() {
        val ui = currentXmlFile() ?: run {
            setStatus("open an .xml markup file first", false)
            return
        }
        val distribution = PreviewProcessLauncher.resolveDistribution() ?: run {
            setStatus("preview distribution not found (run the Gradle build first)", false)
            return
        }
        val css = PreviewProcessLauncher.siblingCss(ui) ?: run {
            setStatus("no sibling .css file for ${ui.fileName}", false)
            return
        }
        stopProcess()
        val current = generation.incrementAndGet()
        setStatus("launching ${ui.fileName}…", false)
        try {
            val process = PreviewProcessLauncher.launch(distribution, ui, css)
            activeProcess = process
            reloadButton.isEnabled = true
            Thread.ofVirtual().name("markup-status-reader").start {
                try {
                    BufferedReader(InputStreamReader(
                        process.inputStream, StandardCharsets.UTF_8)).useLines { lines ->
                        for (line in lines) {
                            if (generation.get() != current) {
                                return@useLines
                            }
                            val parsed = MarkupStatusLineParser.parse(line) ?: continue
                            SwingUtilities.invokeLater { showStatus(parsed) }
                        }
                    }
                } catch (ignored: IOException) {
                    // process died; the watcher or user relaunches
                }
            }
        } catch (failure: IOException) {
            setStatus("failed to launch preview: ${failure.message}", false)
        }
    }

    private fun showStatus(parsed: MarkupStatusLine) {
        val text = when {
            parsed.ok -> "ok (${parsed.nodes} actors)"
            else -> {
                val location = if (parsed.line != null && parsed.line > 0) {
                    " at ${parsed.line}:${parsed.column ?: 0}"
                } else {
                    ""
                }
                (parsed.message ?: "build failed") + location
            }
        }
        setStatus(text.take(MAX_STATUS_LENGTH), parsed.ok)
    }

    private fun setStatus(text: String, ok: Boolean) {
        SwingUtilities.invokeLater {
            status.text = text
            status.foreground =
                if (ok) java.awt.Color(0, 128, 0) else java.awt.Color(180, 0, 0)
        }
    }

    private fun currentXmlFile(): Path? {
        val files = FileEditorManager.getInstance(project).selectedFiles
        val selected = files.firstOrNull { it.name.endsWith(".xml") }
        return selected?.let { Path.of(it.path) }
    }

    private var watcherThread: Thread? = null

    private fun startWatcher() {
        stopWatcher()
        watcherThread = Thread.ofPlatform().name("markup-watcher").daemon().start {
            var lastModified = 0L
            while (!Thread.currentThread().isInterrupted) {
                val ui = currentXmlFile()
                if (ui != null) {
                    val stamp = try {
                        Files.getLastModifiedTime(ui).toMillis()
                    } catch (ignored: IOException) {
                        -1L
                    }
                    if (stamp > lastModified) {
                        lastModified = stamp
                        debouncer.noteChange(System.nanoTime())
                    }
                }
                if (debouncer.takeDue(System.nanoTime())) {
                    SwingUtilities.invokeLater { launchForCurrentFile() }
                }
                try {
                    Thread.sleep(100)
                } catch (interrupted: InterruptedException) {
                    return@start
                }
            }
        }
    }

    private fun stopWatcher() {
        watcherThread?.interrupt()
        watcherThread = null
    }

    private var activeProcess: Process? = null

    private fun stopProcess() {
        val process = activeProcess ?: return
        activeProcess = null
        try {
            process.outputStream.close()
        } catch (ignored: IOException) {
            // stream already closed
        }
        if (process.isAlive) {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroy()
            }
        }
    }

    private companion object {
        const val MAX_STATUS_LENGTH = 300
    }
}
