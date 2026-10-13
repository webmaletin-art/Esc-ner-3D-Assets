package app.universal3d.core.tasks

import app.universal3d.core.model.ModelFormat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicLong

enum class TaskState { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

data class TaskInfo(
    val id: Long,
    val title: String,
    val format: ModelFormat?,
    val totalBytes: Long?,
    val doneBytes: Long,
    val state: TaskState,
    val location: String? = null,
    val error: String? = null,
    val phase: String? = null,
    val retryable: Boolean = true,
) {
    /** Progreso real [0..1] o null si se desconoce el total. */
    val progress: Float? get() = if (state == TaskState.SUCCEEDED) 1f else totalBytes?.takeIf { it > 0 }?.let { (doneBytes.toFloat() / it).coerceIn(0f, 1f) }
    val isFinished get() = state == TaskState.SUCCEEDED || state == TaskState.FAILED || state == TaskState.CANCELLED
}

class TaskReporter internal constructor(private val update: ((TaskInfo) -> TaskInfo) -> Unit) {
    fun progress(done: Long, total: Long?) = update { it.copy(doneBytes = done, totalBytes = total ?: it.totalBytes) }
    fun phase(text: String) = update { it.copy(phase = text) }
}

/** Resultado de un trabajo: ubicación final opcional. */
class TaskOutcome(val location: String? = null)

/**
 * Gestor de tareas con concurrencia limitada, cancelación y reintentos. Es independiente de Android.
 */
class TaskManager(private val scope: CoroutineScope, maxConcurrent: Int = 2, private val dispatcher: CoroutineDispatcher = Dispatchers.Default) {
    private val sem = Semaphore(maxConcurrent)
    private val ids = AtomicLong(1)
    private val _tasks = MutableStateFlow<List<TaskInfo>>(emptyList())
    val tasks: StateFlow<List<TaskInfo>> = _tasks.asStateFlow()
    private val jobs = HashMap<Long, Job>()
    private val work = HashMap<Long, suspend (TaskReporter) -> TaskOutcome>()

    fun submit(title: String, format: ModelFormat? = null, totalBytes: Long? = null, retryable: Boolean = true, block: suspend (TaskReporter) -> TaskOutcome): Long {
        val id = ids.getAndIncrement()
        _tasks.update { it + TaskInfo(id, title, format, totalBytes, 0, TaskState.QUEUED, retryable = retryable) }
        work[id] = block
        start(id)
        return id
    }

    private fun mutate(id: Long, f: (TaskInfo) -> TaskInfo) = _tasks.update { list -> list.map { if (it.id == id) f(it) else it } }

    private fun start(id: Long) {
        val block = work[id] ?: return
        val job = scope.launch(dispatcher, start = CoroutineStart.LAZY) {
            try {
                sem.withPermit {
                    mutate(id) { it.copy(state = TaskState.RUNNING, error = null, doneBytes = 0) }
                    val reporter = TaskReporter { f -> mutate(id) { cur -> if (cur.state == TaskState.RUNNING) f(cur) else cur } }
                    val outcome = block(reporter)
                    mutate(id) { it.copy(state = TaskState.SUCCEEDED, location = outcome.location) }
                }
            } catch (e: CancellationException) {
                mutate(id) { if (it.isFinished) it else it.copy(state = TaskState.CANCELLED) }
                throw e
            } catch (e: Throwable) {
                mutate(id) { it.copy(state = TaskState.FAILED, error = friendly(e)) }
            }
        }
        jobs[id] = job
        job.start()
    }

    fun cancel(id: Long) {
        val job = jobs[id]
        if (job != null && job.isActive) job.cancel()
        mutate(id) { if (it.isFinished) it else it.copy(state = TaskState.CANCELLED) }
    }

    fun cancelAll() = _tasks.value.filter { !it.isFinished }.forEach { cancel(it.id) }

    /** Reintenta una tarea fallida o cancelada. */
    fun retry(id: Long): Boolean {
        val info = _tasks.value.firstOrNull { it.id == id } ?: return false
        if (!info.retryable || (info.state != TaskState.FAILED && info.state != TaskState.CANCELLED)) return false
        mutate(id) { it.copy(state = TaskState.QUEUED, error = null, doneBytes = 0) }
        start(id)
        return true
    }

    fun remove(id: Long) {
        val info = _tasks.value.firstOrNull { it.id == id } ?: return
        if (!info.isFinished) cancel(id)
        jobs.remove(id); work.remove(id)
        _tasks.update { l -> l.filter { it.id != id } }
    }

    fun clearFinished() = _tasks.value.filter { it.isFinished }.forEach { remove(it.id) }

    companion object {
        fun friendly(e: Throwable): String = when (e) {
            is OutOfMemoryError -> "Memoria insuficiente para procesar este archivo."
            else -> e.message?.takeIf { it.isNotBlank() }?.take(300) ?: e::class.simpleName ?: "Error desconocido"
        }
    }
}
