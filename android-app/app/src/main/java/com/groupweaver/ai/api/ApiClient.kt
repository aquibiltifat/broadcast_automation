package com.groupweaver.ai.api

import com.groupweaver.ai.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    
    private var baseUrl: String = BuildConfig.BACKEND_URL
    private var retrofit: Retrofit? = null
    private var apiService: ApiService? = null
    
    private fun createOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        
        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    
    private fun createRetrofit(): Retrofit {
        return Retrofit.Builder()
            .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            .client(createOkHttpClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
    
    fun getApiService(): ApiService {
        if (apiService == null || retrofit == null) {
            retrofit = createRetrofit()
            apiService = retrofit!!.create(ApiService::class.java)
        }
        return apiService!!
    }
    
    fun setBaseUrl(url: String) {
        if (url != baseUrl) {
            baseUrl = url
            retrofit = null
            apiService = null
        }
    }
    
    fun getBaseUrl(): String = baseUrl
}
