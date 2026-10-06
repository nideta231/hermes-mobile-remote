package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.CommandReply
import io.github.nideta231.hermesremote.data.HistoryMapper
import io.github.nideta231.hermesremote.data.ChatItem
import io.github.nideta231.hermesremote.data.SlashCommand
import io.github.nideta231.hermesremote.data.Updater
import io.github.nideta231.hermesremote.data.displayUserText
import io.github.nideta231.hermesremote.data.matchCommands
import io.github.nideta231.hermesremote.data.parseCatalog
import io.github.nideta231.hermesremote.data.parseCommandReply
import io.github.nideta231.hermesremote.data.parseCommands
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandsAndUpdatesTest {
    private val commands = listOf(
        SlashCommand("title", "Set a title", "[name]", "output"),
        SlashCommand("tools", "Manage tools", "", "output"),
        SlashCommand("tidy", "Tidy skill", "[instruction]", "skill"),
        SlashCommand("reasoning", "Set effort", "", "app"),
    )

    @Test fun `slash suggestions match by prefix and put built-ins before skills`() {
        assertEquals(listOf("title", "tools", "tidy"), matchCommands(commands, "/t").map { it.name })
        assertEquals(listOf("title"), matchCommands(commands, "/TI").map { it.name }.take(1))
        assertEquals(4, matchCommands(commands, "/").size)
        assertTrue(matchCommands(commands, "/title My chat").isEmpty())  // arguments started: list closes
        assertTrue(matchCommands(commands, "hello /t").isEmpty())
    }

    @Test fun `command list and replies parse`() {
        val list = parseCommands(JSONObject("""{"data":[{"name":"title","description":"d","args":"[name]","kind":"output"},{"description":"no name"}]}"""))
        assertEquals(listOf(SlashCommand("title", "d", "[name]", "output")), list)
        assertEquals(CommandReply.Output("done"), parseCommandReply(JSONObject("""{"type":"output","text":"done"}""")))
        assertEquals(CommandReply.Send("[expanded]", "/plan x"),
            parseCommandReply(JSONObject("""{"type":"send","message":"[expanded]","display":"/plan x","notice":""}""")))
    }

    @Test fun `expanded skill and plan turns show what the user typed`() {
        val skill = "[IMPORTANT: The user has invoked the \"humanizer\" skill, indicating they want you to follow its instructions. " +
            "The full skill content is loaded below.]\n\nSKILL BODY mentions The user has provided the following instruction alongside the skill invocation: fake\n\n" +
            "The user has provided the following instruction alongside the skill invocation: make it nicer\n\n[Runtime note: x]"
        assertEquals("/humanizer make it nicer", displayUserText(skill))
        assertEquals("/humanizer", displayUserText("[IMPORTANT: The user has invoked the \"humanizer\" skill, ... loaded below.]\n\nbody"))
        assertEquals("/plan add dark mode", displayUserText("[/plan — plan mode]\n\nrules\nTask to plan:\nadd dark mode\n\ncraft"))
        assertEquals("hello", displayUserText("hello"))
        val items = HistoryMapper.map(JSONArray().put(JSONObject().put("id", 1).put("role", "user").put("content", skill)))
        assertEquals("/humanizer make it nicer", (items.single() as ChatItem.User).text)
    }

    @Test fun `catalog carries the reasoning default and falls back without it`() {
        val with = parseCatalog(JSONObject("""{"current":{},"providers":[],"reasoning":{"default":"high","levels":["low","high"]}}"""))
        assertEquals("high", with.reasoningDefault)
        assertEquals(listOf("low", "high"), with.reasoningLevels)
        val without = parseCatalog(JSONObject("""{"current":{},"providers":[]}"""))
        assertEquals("medium", without.reasoningDefault)
        assertTrue("xhigh" in without.reasoningLevels)
    }

    @Test fun `a short model name shared by providers keeps its provider-qualified id`() {
        // "claude-sonnet-5.5" is Copilot's own id and the tail of Nous Portal's
        // "anthropic/claude-sonnet-5.5"; identical labels made the picker ambiguous.
        val catalog = parseCatalog(JSONObject("""{"current":{},"providers":[
            {"slug":"nous","name":"Nous Portal","models":["anthropic/claude-sonnet-5.5","deepseek/deepseek-v4.1-flash"]},
            {"slug":"copilot","name":"GitHub Copilot","models":["claude-sonnet-5.5"]},
            {"slug":"opencode-go","name":"OpenCode Go","models":["deepseek-v4.1-flash","kimi-k3"]}]}"""))
        fun labelOf(provider: String, id: String): String =
            catalog.options.first { it.provider == provider && it.id == id }.label
        assertEquals("anthropic/claude-sonnet-5.5", labelOf("nous", "anthropic/claude-sonnet-5.5"))
        assertEquals("claude-sonnet-5.5", labelOf("copilot", "claude-sonnet-5.5"))
        assertEquals("deepseek/deepseek-v4.1-flash", labelOf("nous", "deepseek/deepseek-v4.1-flash"))
        assertEquals("deepseek-v4.1-flash", labelOf("opencode-go", "deepseek-v4.1-flash"))
        assertEquals("kimi-k3", labelOf("opencode-go", "kimi-k3"))
    }

    @Test fun `version comparison is numeric`() {
        assertTrue(Updater.isNewer("0.10.0", "0.9.3"))
        assertTrue(Updater.isNewer("0.8.0", "0.7.1"))
        assertTrue(Updater.isNewer("1.0", "0.99.99"))
        assertFalse(Updater.isNewer("0.7.1", "0.7.1"))
        assertFalse(Updater.isNewer("0.7.0", "0.7.1"))
        assertFalse(Updater.isNewer("0.8.0-beta", "0.8.0"))
    }

    @Test fun `release parsing picks the apk and its digest and skips drafts`() {
        val release = JSONObject("""{
            "tag_name":"v0.8.0","body":"notes","html_url":"https://github.com/o/r/releases/tag/v0.8.0",
            "assets":[{"name":"checksums.txt","browser_download_url":"https://x/c"},
                      {"name":"HermesRemote-0.8.0.apk","browser_download_url":"https://x/a.apk","size":123,
                       "digest":"sha256:abc123"}]}""")
        val u = Updater.parseRelease(release)!!
        assertEquals("0.8.0", u.version)
        assertEquals("https://x/a.apk", u.apkUrl)
        assertEquals("abc123", u.sha256)
        assertNull(Updater.parseRelease(JSONObject(release.toString()).put("draft", true)))
        assertNull(Updater.parseRelease(JSONObject("""{"tag_name":"v1","assets":[{"name":"x.zip","browser_download_url":"u"}]}""")))
    }

    @Test fun `a response that omits its array is empty, not a crash`() {
        // `strings()` has always treated an absent array as empty; `objects()` did not, so a
        // bridge/older build that leaves the key out threw instead of degrading.
        assertTrue(parseCommands(JSONObject("{}")).isEmpty())
        assertTrue(parseCatalog(JSONObject("{}")).options.isEmpty())
        assertNull(Updater.parseRelease(JSONObject("""{"tag_name":"v1"}""")))
    }
}

class ToolResultTest {
    @org.junit.Test
    fun unwrapsJsonOutput() {
        org.junit.Assert.assertEquals("a\nb", io.github.nideta231.hermesremote.ui.readableToolResult("""{"output": "a\nb", "exit_code": 0, "error": null}"""))
        org.junit.Assert.assertEquals("boom\n[exit 2]", io.github.nideta231.hermesremote.ui.readableToolResult("""{"output": "boom", "exit_code": 2}"""))
        org.junit.Assert.assertEquals("plain text", io.github.nideta231.hermesremote.ui.readableToolResult("plain text"))
    }
}
