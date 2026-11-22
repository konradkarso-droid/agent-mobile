package com.uroboros.search

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import com.uroboros.ProcessObjects
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Системная работа обслуживает индекс. Запрос пользователя и модель здесь не нужны. */
class WebRefreshJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var current: Pair<JobParameters, Job>? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (params.jobId != WEB_REFRESH_JOB_ID || current != null) return false
        val network = params.network ?: return false
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                withContext(Dispatchers.IO) {
                    val app = applicationContext
                    if (WebRefreshSchedule(app).consent() != null) {
                        ProcessObjects.get(app).search.refreshScheduled(network)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "Фоновое обновление остановилось: " + failure.javaClass.simpleName)
            } finally {
                if (current?.first === params) {
                    current = null
                    jobFinished(params, false)
                }
            }
        }
        current = params to task
        task.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        if (current?.first?.jobId == params.jobId) clearCurrent()
        return false
    }

    override fun onDestroy() {
        clearCurrent()
        scope.cancel()
        super.onDestroy()
    }

    private fun clearCurrent() {
        val task = current?.second
        current = null
        task?.cancel()
    }

    private companion object {
        const val TAG = "WebRefreshJob"
    }
}
