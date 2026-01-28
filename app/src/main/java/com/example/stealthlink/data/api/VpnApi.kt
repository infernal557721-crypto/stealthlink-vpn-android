package com.example.stealthlink.data.api

import com.example.stealthlink.data.model.Tariff
import com.example.stealthlink.data.model.PaymentRequest
import com.example.stealthlink.data.model.PaymentResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface VpnApi {
    @GET("tariffs")
    suspend fun getTariffs(): List<Tariff>

    @POST("api/create-payment")
    suspend fun createPayment(@Body request: PaymentRequest): PaymentResponse
}

