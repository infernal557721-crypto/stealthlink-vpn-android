package com.example.stealthlink.data.api

import com.example.stealthlink.data.model.Tariff
import com.example.stealthlink.data.model.PaymentRequest
import com.example.stealthlink.data.model.PaymentResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST


data class ConfigRequest(
    val device_id: String
)

interface VpnApi {
    @GET("/")
    suspend fun checkHealth(): Map<String, String>

    @POST("/api/get-config")
    suspend fun getVpnConfig(@Body request: ConfigRequest): com.example.stealthlink.data.model.VpnConfigResponse
    
    @POST("/api/create-payment")
    suspend fun createPayment(@Body request: com.example.stealthlink.data.model.PaymentRequest): com.example.stealthlink.data.model.PaymentResponse
}
