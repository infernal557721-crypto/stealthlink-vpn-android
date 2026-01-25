package com.example.stealthlink.data.api

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {
    // Real Production Server
    private const val BASE_URL = "http://81.200.154.49:8000/" 

    val api: VpnApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(VpnApi::class.java)
    }
}
