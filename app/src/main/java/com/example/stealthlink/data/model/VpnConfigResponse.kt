package com.example.stealthlink.data.model

data class WireGuardConfig(
    val private_key: String = "",
    val address: String = "",
    val dns: String = "",
    val server_public_key: String = "",
    val endpoint: String = ""
)

data class SubscriptionInfo(
    val is_active: Boolean = false,
    val expires_at: String? = null,
    val remaining_seconds: Long = 0,
    val trial_used: Boolean = false
)

data class VpnConfigResponse(
    val wireguard: WireGuardConfig = WireGuardConfig(),
    val vless_uri: String? = null,
    val subscription: SubscriptionInfo = SubscriptionInfo()
)
