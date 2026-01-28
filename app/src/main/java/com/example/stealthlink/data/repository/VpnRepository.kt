package com.example.stealthlink.data.repository

import com.example.stealthlink.data.api.RetrofitClient
import com.example.stealthlink.data.model.Tariff

class VpnRepository {
    private val api = RetrofitClient.api

    suspend fun getTariffs(): List<Tariff> {
        return try {
            // Try to fetch from real server
            // api.getTariffs()
            throw Exception("Use fallback")
        } catch (e: Exception) {
            // Fallback to Mock Data (so the app works without a server)
            listOf(
                Tariff("1_month", "1 Месяц", 100, false),
                Tariff("3_months", "3 Месяца", 250, true),
                Tariff("1_year", "1 Год", 800, false)
            )
        }
    }
}
