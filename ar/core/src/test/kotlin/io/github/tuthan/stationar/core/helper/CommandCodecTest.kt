package io.github.tuthan.stationar.core.helper

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CommandCodecTest {
    @Test
    fun everyCommandRoundTrips() {
        val all = listOf(
            Command.Ping, Command.Tasks, Command.Launch(2, "org.videolan.vlc/.StartActivity"),
            Command.Launch(3, "org.jellyfin.androidtv/org.jellyfin.androidtv.ui.startup.StartupActivity"),
            Command.Focus(2, 640, 360), Command.Focus(0, 0, 0), Command.Key(4), Command.MoveTask(117, 2),
            Command.RemoveTask(117), Command.Front,
        )
        for (c in all) assertEquals(c, Command.decode(c.encode()), c.encode())
    }

    @Test
    fun badCommandsAreRefused() {
        for (t in listOf(
            "", "ping", "PING x", "LAUNCH 2", "LAUNCH 0 org.videolan.vlc/.StartActivity", "LAUNCH 2 org.videolan.vlc",
            "LAUNCH 2 org.videolan.vlc/.A;reboot", "LAUNCH 2 org.videolan.vlc/.A extra", "FOCUS 2 640", "FOCUS -1 0 0",
            "KEY", "KEY 0", "KEY 99999", "MOVE_TASK x 2", "REMOVE_TASK", "FRONT 1", "SHELL rm -rf /",
        )) assertNull(Command.decode(t), t)
    }

    @Test
    fun framesRoundTripAndAreBounded() {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        Frames.write(out, "OK")
        Frames.write(out, "OK 12 0 com.example true\n13 2 org.videolan.vlc true")
        Frames.write(out, "ERR no such task — été")
        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals("OK", Frames.read(input))
        assertEquals("OK 12 0 com.example true\n13 2 org.videolan.vlc true", Frames.read(input))
        assertEquals("ERR no such task — été", Frames.read(input))
        assertFailsWith<EOFException> { Frames.read(input) }
        // Truncated: says 10 bytes, has 3.
        assertFailsWith<EOFException> { Frames.read(DataInputStream(ByteArrayInputStream(byteArrayOf(0, 0, 0, 10, 65, 66, 67)))) }
        // Oversized.
        assertFailsWith<IOException> { Frames.read(DataInputStream(ByteArrayInputStream(byteArrayOf(0, 2, 0, 0)))) }
        assertFailsWith<IOException> { Frames.read(DataInputStream(ByteArrayInputStream(byteArrayOf(-1, -1, -1, -1)))) }
        assertFailsWith<IllegalArgumentException> { Frames.write(out, "x".repeat(Frames.MAX + 1)) }
    }

    @Test
    fun tasksFromAmStackList() {
        val text = """
            Stack id=1 bounds=[0,0][1920,1080] displayId=0 userId=0
             configuration={1.0 ?mcc?mnc [en_US] ldltr sw540dp w960dp h540dp 320dpi lrg long land television -touch}
              taskId=5: io.github.tuthan.beamstation/.HomeActivity bounds=[0,0][1920,1080] userId=0 visible=true topActivity=ComponentInfo{io.github.tuthan.beamstation/io.github.tuthan.beamstation.HomeActivity}
            Stack id=40 bounds=[0,0][1920,1080] displayId=0 userId=0
              taskId=40: io.github.tuthan.stationar/.ArActivity bounds=[0,0][1920,1080] userId=0 visible=true topActivity=ComponentInfo{io.github.tuthan.stationar/io.github.tuthan.stationar.ArActivity}
            Stack id=41 bounds=[0,0][1280,720] displayId=2 userId=0
              taskId=41: org.videolan.vlc/.StartActivity bounds=[0,0][1280,720] userId=0 visible=false topActivity=ComponentInfo{org.videolan.vlc/org.videolan.vlc.gui.MainActivity}
        """.trimIndent()
        val tasks = TaskInfo.parseStackList(text)
        assertEquals(
            listOf(
                TaskInfo(1, 0, "io.github.tuthan.beamstation", true),
                TaskInfo(40, 0, "io.github.tuthan.stationar", true),
                TaskInfo(41, 2, "org.videolan.vlc", false),
            ),
            tasks,
        )
        assertEquals(tasks, TaskInfo.decodeAll(tasks.joinToString("\n") { it.encode() }))
    }
}
