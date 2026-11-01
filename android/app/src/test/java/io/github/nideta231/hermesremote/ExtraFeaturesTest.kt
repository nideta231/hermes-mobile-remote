package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.Extra
import io.github.nideta231.hermesremote.data.ExtraFeatures
import org.junit.Assert.assertEquals
import org.junit.Test

class ExtraFeaturesTest {
    private val bridge = setOf("monitor", "terminal", "notes", "library", "blueprints", "admin", "folders")

    @Test fun everythingOptionalIsHiddenByDefault() {
        assertEquals(setOf("admin", "folders"), ExtraFeatures.gate(bridge, emptySet()))
    }

    @Test fun switchingOneOnKeepsOnlyThatOne() {
        assertEquals(setOf("admin", "folders", "monitor"), ExtraFeatures.gate(bridge, setOf(Extra.PC_MONITOR)))
        assertEquals(bridge, ExtraFeatures.gate(bridge, Extra.entries.toSet()))
    }

    @Test fun switchingOnNeverAddsWhatTheBridgeLacks() {
        assertEquals(setOf("admin"), ExtraFeatures.gate(setOf("admin"), Extra.entries.toSet()))
    }

    @Test fun servedFollowsTheBridgeGraphAlwaysAndMonitorOnGateways() {
        assertEquals(setOf(Extra.GRAPH), ExtraFeatures.served(emptySet(), gateway = false))
        assertEquals(setOf(Extra.GRAPH, Extra.PC_MONITOR), ExtraFeatures.served(emptySet(), gateway = true))
        assertEquals(Extra.entries.toSet(), ExtraFeatures.served(bridge, gateway = false))
    }

    @Test fun storedKeysParseAndUnknownOnesAreDropped() {
        assertEquals(setOf(Extra.NOTES, Extra.GRAPH), Extra.parse(setOf("notes", "graph", "gone")))
        assertEquals(emptySet<Extra>(), Extra.parse(null))
    }
}
