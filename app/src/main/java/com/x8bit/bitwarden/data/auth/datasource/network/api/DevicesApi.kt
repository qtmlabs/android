package com.x8bit.bitwarden.data.auth.datasource.network.api

import retrofit2.http.GET
import retrofit2.http.Header

/**
 * Defines raw calls under the /devices API.
 */
interface DevicesApi {

    @GET("/devices/knowndevice")
    suspend fun getIsKnownDevice(
        @Header(value = "X-Request-Email") emailAddress: String,
        @Header(value = "X-Device-Identifier") deviceId: String,
    ): Result<Boolean>
}
