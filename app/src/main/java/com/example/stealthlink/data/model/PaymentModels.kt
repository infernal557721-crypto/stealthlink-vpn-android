package com.example.stealthlink.data.model

data class PaymentRequest(
    val amount: String,
    val description: String
)

data class PaymentResponse(
    val payment_id: String,
    val confirmation_url: String,
    val status: String
)
