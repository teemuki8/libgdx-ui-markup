package dev.gdx.markup.idea

import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.SwingUtilities

/**
 * "Markup Preview" tool window: launches the preview process for the XML file open in the
 * editor, streams bounded {@code markup-status} lines to a status label, and offers
 * Launch / Reload / Watch. All process and file work happens off the EDT; only the status
 * label updates on it. The child process is owned by [owner], which the tool-window factory
 * registers with the project's `Disposer`; [dispose] stops the watcher so project close
 * leaves no watcher, child, or reader thread behind.
 */
class MarkupPreviewPanel(private val project: Project, private val toolWindow: ToolWindow) :
    JPanel(BorderLayout()), Disposable {
    private val status = JLabel("not launched", JLabel.LEFT)
    private val launchButton = JButton("Launch")
    private val reloadButton = JButton("Reload").apply { isEnabled = false }
    private val watchToggle = JToggleButton("Watch")
    private val debouncer = WatchDebouncer(300_000_000L)

    /**
     * Owns the preview child process for this panel. Created by the panel (it knows the status
     * consumers) and registered with the project by [MarkupPreviewToolWindowFactory].
     */
    val owner: PreviewProcessOwner = PreviewProcessOwner(
        onStatus = { parsed ->
            SwingUtilities.invokeLater { showStatus(parsed) }
        },
        onProse = {
            // non-status stdout (LWJGL noise etc.) is not shown
        },
        onStderr = { line ->
            SwingUtilities.invokeLater { setStatus(line.take(MAX_STATUS_LENGTH), false) }
        },
        onExit = { cause ->
            if (cause == ExitCause.SELF) {
                SwingUtilities.invokeLater { setStatus("preview exited", false) }
            }
        },
    )

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
        setStatus("launching ${ui.fileName}…", false)
        owner.replace(PreviewProcessLauncher.buildCommand(distribution, ui, css))
        reloadButton.isEnabled = true
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

    /** Stops the file watcher; the process owner is disposed through its own registration. */
    override fun dispose() {
        stopWatcher()
    }

    private companion object {
        const val MAX_STATUS_LENGTH = 300
    }
}
