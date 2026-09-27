package com.quazaar.synker.klient.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NotificationPush(
    val id: String,
    @SerialName("app_name") val appName: String,
    val title: String,
    val body: String,
    @SerialName("icon_url") val iconUrl: String? = null,
    @SerialName("is_silent") val isSilent: Boolean = false
)
