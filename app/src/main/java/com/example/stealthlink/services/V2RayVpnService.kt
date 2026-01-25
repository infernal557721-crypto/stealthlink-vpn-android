package com.example.stealthlink.services

import android.net.VpnService
import android.content.Intent

class V2RayVpnService : VpnService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
}
