package com.nawaf.mohammel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

enum class JobStatus { QUEUED, RUNNING, DONE, ERROR, CANCELED }

data class Job(
    val id: String,
    val spec: JobSpec,
    val status: JobStatus = JobStatus.QUEUED,
    val progress: Float = -1f, // من 0 إلى 100، أو -1 إذا غير معروف
    val etaSeconds: Long = -1,
    val error: String? = null,
    val saved: SavedFile? = null,
    val created: Long = System.currentTimeMillis(),
) {
    val active: Boolean get() = status == JobStatus.QUEUED || status == JobStatus.RUNNING
    val title: String get() = spec.title ?: spec.url
}

/** طابور التحميلات، يشاركه الـ Service مع الشاشة. التحميل واحد ورا الثاني. */
object JobQueue {
    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs

    fun add(specs: List<JobSpec>): List<Job> {
        val created = specs.map { Job(id = UUID.randomUUID().toString().take(12), spec = it) }
        _jobs.update { it + created }
        return created
    }

    fun get(id: String): Job? = _jobs.value.firstOrNull { it.id == id }

    fun update(id: String, f: (Job) -> Job) {
        _jobs.update { list -> list.map { if (it.id == id) f(it) else it } }
    }

    @Synchronized
    fun takeNext(): Job? {
        val next = _jobs.value.firstOrNull { it.status == JobStatus.QUEUED } ?: return null
        update(next.id) { it.copy(status = JobStatus.RUNNING) }
        return get(next.id)
    }


    /** يرجّع true إذا المهمة كانت شغالة (عشان نوقف العملية). */
    fun cancel(id: String): Boolean {
        val job = get(id) ?: return false
        return when (job.status) {
            JobStatus.QUEUED -> { update(id) { it.copy(status = JobStatus.CANCELED) }; false }
            JobStatus.RUNNING -> true
            else -> false
        }
    }

    fun remove(id: String) {
        _jobs.update { list -> list.filterNot { it.id == id && !it.active } }
    }

    fun clearFinished() {
        _jobs.update { list -> list.filter { it.active } }
    }

    val pendingCount: Int get() = _jobs.value.count { it.active }
}
