package com.example.stealthlink.data.model

data class VpnConfigResponse(
    val private_key: String,
    val address: String,
    val dns: String,
    val public_key: String,
    val endpoint: String
)
