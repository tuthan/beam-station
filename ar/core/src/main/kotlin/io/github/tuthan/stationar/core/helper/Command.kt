package io.github.tuthan.stationar.core.helper

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * What Station AR asks its shell helper (uid 2000, on an abstract local socket only the app's uid may use), one
 * frame per request and one per reply: a 4-byte big-endian length, then that many bytes of UTF-8 text.
 *
 * Requests: [encode]d [Command]s. Replies: `OK` with an optional payload after a space, or `ERR <why>`.
 */
sealed class Command {
    /** Liveness. */
    object Ping : Command()

    /** The root tasks: one `<task> <display> <package> <visible>` line each (see [TaskInfo]). */
    object Tasks : Command()

    /** Starts `component` (`pkg/.Activity` or `pkg/full.Class`) on `display`, moving its existing task there first. */
    data class Launch(val display: Int, val component: String) : Command()

    /**
     * Makes `display` the focused one, so the remote's keys go to it: a touch down at (x, y) on that display,
     * cancelled at once (nothing is clicked). Android 11 moves focus to the display that was touched; it has no
     * other way for an untrusted virtual display (`am task focus` leaves the focus where it was).
     */
    data class Focus(val display: Int, val x: Int, val y: Int) : Command()

    /** A key press (down and up) to whatever display has the focus, and never another (Android 11 drops those). */
    data class Key(val code: Int) : Command()

    /** Moves a root task to another display. */
    data class MoveTask(val task: Int, val display: Int) : Command()

    /** Closes a root task (an app leaving its screen). */
    data class RemoveTask(val task: Int) : Command()

    fun encode(): String = when (this) {
        Ping -> "PING"
        Tasks -> "TASKS"
        is Launch -> "LAUNCH $display $component"
        is Focus -> "FOCUS $display $x $y"
        is Key -> "KEY $code"
        is MoveTask -> "MOVE_TASK $task $display"
        is RemoveTask -> "REMOVE_TASK $task"
    }

    companion object {
        private val COMPONENT = Regex("[A-Za-z0-9_.]{1,200}/[A-Za-z0-9_.$]{1,300}")

        /** The command in [encode]'s form, or null when it isn't one (the helper answers ERR). */
        fun decode(text: String): Command? {
            val p = text.split(' ')
            fun int(i: Int, range: IntRange): Int? = p.getOrNull(i)?.toIntOrNull()?.takeIf { it in range }
            return when (p[0]) {
                "PING" -> if (p.size == 1) Ping else null
                "TASKS" -> if (p.size == 1) Tasks else null
                "LAUNCH" -> {
                    if (p.size != 3 || !COMPONENT.matches(p[2])) return null
                    Launch(int(1, 1..1000) ?: return null, p[2])
                }
                "FOCUS" -> {
                    if (p.size != 4) return null
                    Focus(int(1, 0..1000) ?: return null, int(2, 0..8192) ?: return null, int(3, 0..8192) ?: return null)
                }
                "KEY" -> if (p.size == 2) Key(int(1, 1..1000) ?: return null) else null
                "MOVE_TASK" -> {
                    if (p.size != 3) return null
                    MoveTask(int(1, 0..Int.MAX_VALUE) ?: return null, int(2, 0..1000) ?: return null)
                }
                "REMOVE_TASK" -> if (p.size == 2) RemoveTask(int(1, 0..Int.MAX_VALUE) ?: return null) else null
                else -> null
            }
        }
    }
}

/** A root task (Android 11's "stack") as TASKS reports it: its id, its display, the package of its top task. */
data class TaskInfo(val id: Int, val display: Int, val pkg: String, val visible: Boolean) {
    fun encode() = "$id $display $pkg $visible"

    companion object {
        fun decode(line: String): TaskInfo? {
            val p = line.split(' ')
            if (p.size != 4) return null
            return TaskInfo(p[0].toIntOrNull() ?: return null, p[1].toIntOrNull() ?: return null, p[2], p[3] == "true")
        }

        fun decodeAll(text: String): List<TaskInfo> = text.lines().mapNotNull { if (it.isBlank()) null else decode(it) }

        /**
         * The root tasks in `am stack list`'s output (Android 11): a `Stack id=<n> … displayId=<d> …` line, then one
         * `  taskId=<t>: <package>/<activity> … visible=<bool> …` line per task in it. Each root task is reported
         * once, by its stack id (what `am display move-stack` and `am stack remove` take), with its first task's
         * package.
         */
        fun parseStackList(text: String): List<TaskInfo> {
            val out = ArrayList<TaskInfo>()
            var root = -1
            var display = -1
            var reported = true
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.startsWith("Stack id=") || line.startsWith("RootTask id=")) {
                    root = Regex("^\\w+ id=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                    display = Regex("displayId=(-?\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                    reported = root < 0
                    continue
                }
                if (reported) continue
                val m = Regex("^taskId=(\\d+): ([^/ ]+)/").find(line) ?: continue
                val visible = Regex("visible=(true|false)").find(line)?.groupValues?.get(1) == "true"
                out.add(TaskInfo(root, display, m.groupValues[2], visible))
                reported = true
            }
            return out
        }
    }
}

/** The frames on the helper's socket. */
object Frames {
    const val MAX = 64 * 1024

    @Throws(IOException::class)
    fun write(out: DataOutputStream, text: String) {
        val b = text.toByteArray(Charsets.UTF_8)
        require(b.size <= MAX) { "frame too large" }
        out.writeInt(b.size)
        out.write(b)
        out.flush()
    }

    /** The next frame's text; throws at the end of the stream, or on a frame over [MAX]. */
    @Throws(IOException::class)
    fun read(input: DataInputStream): String {
        val n = input.readInt()
        if (n < 0 || n > MAX) throw IOException("bad frame length $n")
        val b = ByteArray(n)
        input.readFully(b)
        return String(b, Charsets.UTF_8)
    }
}
