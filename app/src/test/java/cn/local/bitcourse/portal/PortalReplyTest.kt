package cn.local.bitcourse.portal

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortalReplyTest {
    private val gson = Gson()
    private val page = PortalUrls.page(PortalMode.DIRECT)
    private val token = "one-time-token"
    private fun wrapper(value: Any) = gson.toJson(gson.toJson(value))
    private fun reply(status: Any, body: Any? = null, replyToken: String = token, replyPage: String = page) =
        wrapper(mapOf("status" to status, "token" to replyToken, "page" to replyPage, "body" to body))

    @Test fun acceptsExplicitEmptyCourseResultWithoutTreatingItAsAuthenticationFailure() {
        val body = "{\"datas\":{\"cxxszhxqkb\":{\"rows\":[]}}}"
        val result = PortalReply.decode(reply("complete", body), token, page)
        assertEquals("complete", result?.status)
        assertEquals(body, result?.body)
        assertEquals("auth", PortalReply.decode(reply("auth"), token, page)?.status)
    }

    @Test fun rejectsResultsFromAnotherRequestOrNavigation() {
        assertNull(PortalReply.decode(reply("complete", "{}", replyToken = "old-token"), token, page))
        assertNull(PortalReply.decode(reply("complete", "{}", replyPage = "$page?other"), token, page))
        assertNull(PortalReply.decode(reply("complete", "{}", replyPage = "https://sso.bit.edu.cn/cas/login"), token, page))
    }

    @Test fun malformedPageDataCannotCrashTheCallbackOrBecomeASuccess() {
        listOf(null, "null", "{}", "not json", wrapper(listOf(1)), reply(mapOf("nested" to true)),
            reply("complete"), reply("complete", mapOf("rows" to emptyList<String>())), reply("unknown"))
            .forEach { assertNull(it, PortalReply.decode(it, token, page)) }
        assertNull(PortalReply.decode(reply("complete", "x".repeat(4_000_001)), token, page))
    }

    @Test fun navigationAndPendingStatesDoNotProduceScheduleData() {
        assertEquals("pending", PortalReply.decode(reply("pending"), token, page)?.status)
        assertEquals(PortalReply("changed"), PortalReply.decode(wrapper(mapOf("status" to "changed")), token, page))
        assertEquals(PortalReply("missing"), PortalReply.decode(wrapper(mapOf("status" to "missing")), token, page))
    }
}
