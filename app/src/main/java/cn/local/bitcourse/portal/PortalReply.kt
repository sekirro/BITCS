package cn.local.bitcourse.portal

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Treat even a trusted page's asynchronous result as untrusted input. */
internal data class PortalReply(val status: String, val body: String? = null) {
    companion object {
        fun decode(raw: String?, token: String, page: String): PortalReply? = runCatching {
            require(raw != null && raw.length <= 8_200_000)
            val wrapper = JsonParser.parseString(raw)
            require(wrapper.isJsonPrimitive && wrapper.asJsonPrimitive.isString)
            val text = wrapper.asString
            require(text.length <= 4_100_000)
            val objectValue = JsonParser.parseString(text)
            require(objectValue.isJsonObject)
            val result = objectValue.asJsonObject
            val status = string(result, "status") ?: error("Missing status")
            require(status in setOf("pending", "complete", "auth", "error", "missing", "changed"))
            if (status == "missing" || status == "changed") return@runCatching PortalReply(status)
            require(string(result, "token") == token && string(result, "page") == page)
            val body = string(result, "body")
            if (status == "complete") require(body != null && body.length <= 4_000_000)
            PortalReply(status, body)
        }.getOrNull()

        private fun string(value: JsonObject, name: String): String? {
            val field = value.get(name) ?: return null
            return if (field.isJsonPrimitive && field.asJsonPrimitive.isString) field.asString else null
        }
    }
}
