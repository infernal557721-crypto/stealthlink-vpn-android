package com.example.stealthlink.data.api

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {
    // Placeholder URL until we have a real VPS IP
    private const val BASE_URL = "http://10.0.2.2:8000/" 

    val api: VpnApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(VpnApi::class.java)
    }
}
