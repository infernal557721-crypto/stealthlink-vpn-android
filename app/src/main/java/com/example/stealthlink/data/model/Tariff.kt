package com.example.stealthlink.data.model

import com.google.gson.annotations.SerializedName

data class Tariff(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("price") val price: Int,
    @SerializedName("is_hit") val isHit: Boolean
)
