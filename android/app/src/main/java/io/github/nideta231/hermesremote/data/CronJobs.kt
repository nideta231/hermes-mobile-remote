package io.github.nideta231.hermesremote.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CronState(
    val jobs: List<CronJob> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** The bridge has no cron routes (404): the page says to update it instead of showing an error. */
    val unsupported: Boolean = false,
    /** Job id → its recent runs, once the user opened them. */
    val runs: Map<String, List<CronRun>> = emptyMap(),
    /** Jobs with an action in flight, so their buttons can't be pressed twice. */
    val busy: Set<String> = emptySet(),
)

/**
 * The Scheduled page: the cron jobs of the Hermes profile in use, their recent runs, and pause /
 * resume / run now. Kept out of the ViewModel; it only needs the current client and profile.
 *
 * Every answer is checked against the PC and profile it was asked for, so switching either while a
 * request is in flight never shows one's jobs under another's name.
 */
class CronJobs(
    private val scope: CoroutineScope,
    private val client: () -> BridgeClient?,
    /** The PC and Hermes profile in use now. */
    private val target: () -> Target,
    private val describe: (Throwable) -> String,
    private val say: (String) -> Unit,
) {
    private val _state = MutableStateFlow(CronState())
    val state: StateFlow<CronState> = _state.asStateFlow()

    /** Forget the shown jobs (another profile or PC is now in use). */
    fun clear() { _state.value = CronState() }

    fun refresh() {
        val c = client() ?: return
        val asked = target()
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            try {
                val jobs = c.cronJobs(asked.profile)
                if (asked == target()) _state.update { it.copy(jobs = jobs, loading = false, unsupported = false) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (asked != target()) return@launch
                val old = t is BridgeException && t.httpCode == 404 && t.code != "job_not_found"
                _state.update { it.copy(loading = false, unsupported = old, error = if (old) null else describe(t)) }
            }
        }
    }

    fun loadRuns(jobId: String) {
        val c = client() ?: return
        val asked = target()
        scope.launch {
            try {
                val runs = c.cronRuns(jobId, asked.profile)
                if (asked == target()) _state.update { it.copy(runs = it.runs + (jobId to runs)) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (asked == target()) say(describe(t))
            }
        }
    }

    /** Create a job; [onDone] gets null on success or the error to show in the form. */
    fun create(name: String, schedule: String, prompt: String, onDone: (String?) -> Unit) {
        val c = client() ?: return onDone("Not connected to your PC")
        val asked = target()
        scope.launch {
            try {
                c.createCronJob(name.trim(), schedule.trim(), prompt.trim(), asked.profile)
                onDone(null)
                say("Scheduled ${name.trim().ifEmpty { "job" }}")
                if (asked == target()) refresh()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                onDone(describe(t))
            }
        }
    }

    fun pause(job: CronJob) = act(job, "pause", "Paused ${job.name}")
    fun resume(job: CronJob) = act(job, "resume", "Resumed ${job.name}")
    fun runNow(job: CronJob) = act(job, "trigger", "Started ${job.name}")

    private fun act(job: CronJob, action: String, done: String) {
        val c = client() ?: return
        if (job.id in _state.value.busy) return
        val asked = target()
        _state.update { it.copy(busy = it.busy + job.id) }
        scope.launch {
            try {
                val updated = c.cronAction(job.id, action, asked.profile)
                if (asked != target()) return@launch
                _state.update { st ->
                    st.copy(jobs = st.jobs.map { if (it.id == job.id && updated != null) updated else it },
                        runs = st.runs - job.id)  // a run may have been added; re-read when opened
                }
                say(done)
                if (action == "trigger") refresh()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (asked == target()) say(describe(t))
            } finally {
                _state.update { it.copy(busy = it.busy - job.id) }
            }
        }
    }
}
