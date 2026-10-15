package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.BridgeClient
import io.github.nideta231.hermesremote.data.CronJobs
import io.github.nideta231.hermesremote.data.Pairing
import io.github.nideta231.hermesremote.data.Target
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Switching PC while a request to the old one is in flight: its late answer must not land on the
 * new PC. (The bug: after pairing a second PC and switching back, the first PC's profile dropdown
 * was gone, because a reply meant for the other PC overwrote it.)
 */
class PcSwitchTest {
    private val server = MockWebServer()
    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun client() = BridgeClient(Pairing("http://127.0.0.1:${server.port}", "t", "hrb_abcdefghijklmnopqrstuvwxyz"),
        hostGuard = { _, _ -> true })

    private fun jobs(vararg names: String) = MockResponse().setHeader("Content-Type", "application/json")
        .setBody("""{"jobs": [${names.joinToString { """{"id": "${it.lowercase()}1", "name": "$it", "state": "scheduled"}""" }}]}""")

    @Test fun aLateAnswerFromTheOtherPcIsDropped() = runBlocking {
        var now = Target(pc = "little-boy", profile = "default")
        val c = client()
        val cron = CronJobs(scope, client = { c }, target = { now }, describe = { it.message ?: "?" }, say = {})

        // little-boy answers slowly; the user switches to the other PC before it does.
        server.enqueue(jobs("LittleBoyJob").setBodyDelay(600, TimeUnit.MILLISECONDS))
        cron.refresh()
        delay(100)
        now = Target(pc = "fat-man", profile = "default")
        cron.clear()
        server.enqueue(jobs("FatManJob"))
        cron.refresh()

        withTimeout(5_000) { while (cron.state.value.jobs.isEmpty() || cron.state.value.loading) delay(20) }
        delay(800)  // let little-boy's late answer arrive
        assertEquals(listOf("FatManJob"), cron.state.value.jobs.map { it.name })
    }

    @Test fun theSameProfileNameOnTwoPcsIsTwoTargets() {
        // Both PCs have a "developer" profile: a profile-only check would let one PC's answer through.
        assertNotEquals(Target("little-boy", "developer"), Target("fat-man", "developer"))
        assertEquals(Target("little-boy", "developer"), Target("little-boy", "developer"))
    }

    @Test fun anAnswerForTheCurrentPcStillLands() = runBlocking {
        val now = Target("little-boy", "developer")
        val c = client()
        val cron = CronJobs(scope, client = { c }, target = { now }, describe = { it.message ?: "?" }, say = {})
        server.enqueue(jobs("A", "B"))
        cron.refresh()
        withTimeout(5_000) { while (cron.state.value.jobs.isEmpty()) delay(20) }
        assertEquals(listOf("A", "B"), cron.state.value.jobs.map { it.name })
        assertTrue(server.takeRequest().path!!.contains("profile=developer"))
    }
}
