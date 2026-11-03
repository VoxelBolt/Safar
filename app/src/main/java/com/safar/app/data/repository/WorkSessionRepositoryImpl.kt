package com.safar.app.data.repository

import com.safar.app.data.remote.SafarApi
import com.safar.app.domain.model.WorkSession
import com.safar.app.domain.repository.WorkSessionRepository

class WorkSessionRepositoryImpl(
    private val api: SafarApi,
) : WorkSessionRepository {
    override suspend fun getCurrent(): WorkSession? = api.getCurrentWorkSession()
    override suspend fun start(): WorkSession = api.startWorkSession()
    override suspend fun pause(sessionId: String): WorkSession = api.pauseWorkSession(sessionId)
    override suspend fun resume(sessionId: String): WorkSession = api.resumeWorkSession(sessionId)
    override suspend fun finish(sessionId: String): WorkSession = api.finishWorkSession(sessionId)
    override suspend fun getHistory(startEpochMs: Long?, endEpochMs: Long?, offset: Int, limit: Int): List<WorkSession> =
        api.getWorkSessionHistory(startEpochMs, endEpochMs, offset, limit)

    override suspend fun getSummary(startEpochMs: Long?, endEpochMs: Long?) =
        api.getWorkSessionSummary(startEpochMs, endEpochMs)
}
