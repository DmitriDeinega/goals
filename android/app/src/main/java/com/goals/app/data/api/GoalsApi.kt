package com.goals.app.data.api

import com.goals.app.data.models.*
import retrofit2.Response
import retrofit2.http.*

interface GoalsApi {

    @GET("api/init")
    suspend fun init(): Response<InitResponse>

    @GET("api/week-data")
    suspend fun getWeekData(@Query("week_start") weekStart: String): Response<WeekDataResponse>

    @POST("api/weeks/ensure")
    suspend fun ensureWeek(): Response<Unit>

    @PUT("api/weeks/{goalId}/enabled")
    suspend fun setEnabled(
        @Path("goalId") goalId: String,
        @Body body: SetEnabledRequest
    ): Response<GoalChangedPayload>

    @POST("api/goals/")
    suspend fun createGoal(@Body body: CreateGoalRequest): Response<GoalChangedPayload>

    @PUT("api/goals/{id}")
    suspend fun updateGoal(
        @Path("id") id: String,
        @Body body: UpdateGoalRequest
    ): Response<GoalChangedPayload>

    @DELETE("api/goals/{id}")
    suspend fun deleteGoal(@Path("id") id: String): Response<GoalChangedPayload>

    @PUT("api/goals/reorder/batch")
    suspend fun reorderGoals(@Body body: List<ReorderItem>): Response<List<GoalChangedPayload>>

    @POST("api/logs/")
    suspend fun toggleSlot(@Body body: ToggleSlotRequest): Response<LogChangedPayload>
}
