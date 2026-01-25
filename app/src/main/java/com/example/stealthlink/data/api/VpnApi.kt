package com.example.stealthlink.data.api

import com.example.stealthlink.data.model.Tariff
import retrofit2.http.GET

interface VpnApi {
    @GET("tariffs")
    suspend fun getTariffs(): List<Tariff>
}
