package com.xcloak.qrdine.data

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Reads a claim from a JWT payload. Display/routing only: the server (RLS) is what enforces access. */
object Jwt {
    fun claim(token: String, name: String): String? = runCatching {
        val payload = token.split(".").getOrNull(1) ?: return null
        val json = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        Json.parseToJsonElement(json).jsonObject[name]?.jsonPrimitive?.contentOrNull
    }.getOrNull()
}
