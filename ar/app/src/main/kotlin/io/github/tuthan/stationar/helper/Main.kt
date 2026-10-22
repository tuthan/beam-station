package io.github.tuthan.stationar.helper

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import io.github.tuthan.stationar.core.helper.Command
import io.github.tuthan.stationar.core.helper.Frames
import io.github.tuthan.stationar.core.helper.TaskInfo
import io.github.tuthan.stationar.keys.KeyFilterSetting
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Station AR's shell helper: runs as the shell user (uid 2000) through `app_process`, started over the app's own
 * ADB connection only while an app screen exists, and does the few things only the shell may: start an app on one of
 * AR's virtual displays, move the focus to a display, send a key to the focused display, list, move and close tasks.
 *
 * It listens on one abstract local socket (`args[0]`, random per session), never on the network, and serves the first
 * client whose uid is `args[1]` (Station AR's); anyone else is closed. When Station AR goes away (it left AR, or
 * crashed) the helper closes the tasks it put on AR's displays and exits. Started with hang-ups ignored, so that clean-up
 * runs even when the app's ADB connection died with it; with no client within 15 s, it exits.
 */
object Main {
    /** Root tasks this helper launched or moved onto AR's displays: closed when the app goes away. */
    private val ours = HashSet<Int>()
    @Volatile private var connected = false

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 2) {
            System.err.println("usage: Main <socket-name> <app-uid>")
            System.exit(2)
        }
        val uid = args[1].toInt()
        try {
            Looper.prepareMainLooper()
        } catch (_: RuntimeException) {
        }
        val server = LocalServerSocket(args[0])
        Thread({
            Thread.sleep(15_000)
            if (!connected) {
                System.err.println("no client")
                System.exit(1)
            }
        }, "watchdog").apply { isDaemon = true }.start()
        println("READY")
        System.out.flush()
        var client: LocalSocket? = null
        for (attempt in 0 until 5) {
            val c = server.accept()
            if (c.peerCredentials.uid == uid) {
                client = c
                connected = true
                break
            }
            System.err.println("refused uid ${c.peerCredentials.uid}")
            c.close()
        }
        server.close()
        if (client == null) System.exit(1)
        serve(client!!)
        cleanUp()
        System.exit(0)
    }

    private fun serve(c: LocalSocket) {
        val input = DataInputStream(c.inputStream)
        val out = DataOutputStream(c.outputStream)
        try {
            while (true) {
                val text = Frames.read(input)
                val reply = try {
                    val cmd = Command.decode(text)
                    if (cmd == null) "ERR unknown command" else handle(cmd)
                } catch (e: Exception) {
                    "ERR " + (e.message ?: e.javaClass.simpleName).replace('\n', ' ')
                }
                Frames.write(out, reply)
            }
        } catch (_: IOException) {
            // The app closed the socket.
        } finally {
            try {
                c.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun handle(cmd: Command): String = when (cmd) {
        Command.Ping -> "OK"
        Command.Tasks -> "OK " + tasks().joinToString("\n") { it.encode() }
        is Command.Launch -> launch(cmd.display, cmd.component)
        is Command.Focus -> {
            touchDownAndCancel(cmd.display, cmd.x.toFloat(), cmd.y.toFloat())
            "OK"
        }
        is Command.Key -> {
            pressKey(cmd.code)
            "OK"
        }
        is Command.MoveTask -> {
            run("am", "display", "move-stack", cmd.task.toString(), cmd.display.toString())
            if (cmd.display != 0) ours.add(cmd.task) else ours.remove(cmd.task)
            "OK"
        }
        is Command.RemoveTask -> {
            run("am", "stack", "remove", cmd.task.toString())
            ours.remove(cmd.task)
            "OK"
        }
        Command.Front -> {
            // AR's own entry (single task): its task comes to the front, with an intent that asks for nothing.
            val started = run("am", "start", "--display", "0", "-n", AR_ENTRY, "-f", "0x10000000")
            if (started.contains("Error")) "ERR " + started.lines().firstOrNull { it.contains("Error") }.orEmpty() else "OK"
        }
    }

    private const val AR_ENTRY = "io.github.tuthan.stationar/.EnterAr"

    // MARK: - Tasks

    private fun tasks(): List<TaskInfo> = TaskInfo.parseStackList(run("am", "stack", "list"))

    /**
     * Starts `component` on `display`. An app that already has a task would start on display 0 (where its task is),
     * so its task is moved to `display` first. Answers `OK <root task>`.
     */
    private fun launch(display: Int, component: String): String {
        val pkg = component.substringBefore('/')
        tasks().firstOrNull { it.pkg == pkg && it.display != display }?.let {
            run("am", "display", "move-stack", it.id.toString(), display.toString())
        }
        val started = run("am", "start", "--display", display.toString(), "-n", component, "-f", "0x10200000")
        if (started.contains("Error")) return "ERR " + started.lines().firstOrNull { it.contains("Error") }.orEmpty()
        val task = tasks().firstOrNull { it.pkg == pkg && it.display == display }
            ?: return "ERR $pkg didn't start on display $display"
        ours.add(task.id)
        return "OK ${task.id}"
    }

    private fun cleanUp() {
        for (id in ours.toList()) {
            try {
                run("am", "stack", "remove", id.toString())
            } catch (_: Exception) {
            }
        }
        ours.clear()
        // The key filter's entry, which the app takes out itself unless it was killed; every other entry stays.
        try {
            val next = KeyFilterSetting.next(run("settings", "get", "secure", KeyFilterSetting.KEY).trim(), false)
            if (next != null) {
                if (next.isEmpty()) run("settings", "delete", "secure", KeyFilterSetting.KEY)
                else run("settings", "put", "secure", KeyFilterSetting.KEY, next.joinToString(":"))
            }
        } catch (_: Exception) {
        }
    }

    private fun run(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return text
    }

    // MARK: - Input (InputManager.injectInputEvent: the shell holds INJECT_EVENTS)

    private val inputManager: Any by lazy {
        Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)!!
    }
    private val inject by lazy {
        inputManager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    }
    private val setDisplayId by lazy {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }

    private fun inject(e: InputEvent) {
        // 1: INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT, until it's queued for its window (not until the app has handled it).
        val ok = inject.invoke(inputManager, e, 1) as Boolean
        if (!ok) throw IllegalStateException("the input event wasn't taken")
    }

    /**
     * A touch down at (x, y) on `display`, cancelled at once: Android moves the focus to the display a touch lands
     * on, and a cancel clicks nothing.
     */
    private fun touchDownAndCancel(display: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL)) {
            val e = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x, y, 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            setDisplayId.invoke(e, display)
            try {
                inject(e)
            } finally {
                e.recycle()
            }
        }
    }

    /**
     * A whole key press for the focused display: no display is set, so Android delivers it to the focused one, and
     * never to another (keys aimed at an unfocused display are dropped on Android 11). Marked as the virtual keyboard's,
     * which is how Station AR's key filter knows its own keys and lets them through.
     */
    private fun pressKey(code: Int) {
        val now = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            inject(KeyEvent(now, SystemClock.uptimeMillis(), action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD))
        }
    }
}
