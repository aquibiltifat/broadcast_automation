package com.groupweaver.ai.api

import com.groupweaver.ai.models.ApiResponse
import com.groupweaver.ai.models.BroadcastList
import com.groupweaver.ai.models.SyncData
import com.groupweaver.ai.models.SyncRequest
import retrofit2.Response
import retrofit2.http.*

interface ApiService {
    
    @GET("api/health")
    suspend fun healthCheck(): Response<Map<String, Any>>
    
    @POST("api/sync")
    suspend fun syncBroadcasts(@Body request: SyncRequest): Response<ApiResponse<SyncData>>
    
    @GET("api/lists")
    suspend fun getLists(): Response<ApiResponse<List<BroadcastList>>>
    
    @POST("api/lists")
    suspend fun createList(@Body list: BroadcastList): Response<ApiResponse<BroadcastList>>
    
    @DELETE("api/lists/{listId}")
    suspend fun deleteList(@Path("listId") listId: String): Response<ApiResponse<Any>>
}
