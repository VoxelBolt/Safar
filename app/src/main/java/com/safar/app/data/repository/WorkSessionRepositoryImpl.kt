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
}
