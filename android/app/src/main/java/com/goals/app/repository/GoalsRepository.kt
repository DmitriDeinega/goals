package com.goals.app.repository

import com.goals.app.data.api.GoalsApi
import com.goals.app.data.models.*
import javax.inject.Inject
import javax.inject.Singleton

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val message: String, val code: Int = 0) : ApiResult<Nothing>()
}

@Singleton
class GoalsRepository @Inject constructor(
    private val api: GoalsApi
) {
    suspend fun init(): ApiResult<InitResponse> = safeCall { api.init() }

    suspend fun getWeekData(weekStart: String): ApiResult<WeekDataResponse> =
        safeCall { api.getWeekData(weekStart) }

    suspend fun ensureWeek(): ApiResult<Unit> = safeCall { api.ensureWeek() }

    suspend fun createGoal(req: CreateGoalRequest): ApiResult<GoalChangedPayload> =
        safeCall { api.createGoal(req) }

    suspend fun updateGoal(id: String, req: UpdateGoalRequest): ApiResult<GoalChangedPayload> =
        safeCall { api.updateGoal(id, req) }

    suspend fun deleteGoal(id: String): ApiResult<GoalChangedPayload> =
        safeCall { api.deleteGoal(id) }

    suspend fun reorderGoals(items: List<ReorderItem>): ApiResult<List<GoalChangedPayload>> =
        safeCall { api.reorderGoals(items) }

    suspend fun toggleSlot(req: ToggleSlotRequest): ApiResult<LogChangedPayload> =
        safeCall { api.toggleSlot(req) }

    suspend fun setEnabled(goalId: String, enabled: Boolean): ApiResult<GoalChangedPayload> =
        safeCall { api.setEnabled(goalId, SetEnabledRequest(enabled)) }

    private suspend fun <T> safeCall(call: suspend () -> retrofit2.Response<T>): ApiResult<T> {
        return try {
            val response = call()
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null) ApiResult.Success(body)
                else if (response.code() == 204) @Suppress("UNCHECKED_CAST") ApiResult.Success(Unit as T)
                else ApiResult.Error("Empty response", response.code())
            } else {
                ApiResult.Error(response.message() ?: "Unknown error", response.code())
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancellation is control flow, not a failure: a superseded request must
            // stay cancelled and must never surface as a toast. Swallowing it here
            // also broke structured concurrency by resuming a cancelled scope.
            throw e
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Network error")
        }
    }
}
