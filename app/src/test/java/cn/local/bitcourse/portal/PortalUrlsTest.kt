package cn.local.bitcourse.portal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalUrlsTest {
    @Test fun permitsOnlyParsedSchoolHttpsAuthorities() {
        listOf("https://sso.bit.edu.cn/cas/login", "https://jxzxehall.bit.edu.cn/auth-protocol-core/login",
            "https://webvpn.bit.edu.cn:443/login", "https://SSO.BIT.EDU.CN/cas/login")
            .forEach { assertTrue(it, PortalUrls.isAllowedSchoolUrl(it)) }
        listOf("http://sso.bit.edu.cn/cas/login", "https://bit.edu.cn.evil.example/", "https://evilbit.edu.cn/",
            "https://bit.edu.cn@evil.example/", "https://user@bit.edu.cn/", "https://user:pass@sso.bit.edu.cn/",
            "https://sso.bit.edu.cn:8443/", "https://sso.bit.edu.cn:0443/", "https://sso.bit.edu.cn:/",
            "https://sso.bit.edu.cn./", "https://%73so.bit.edu.cn/", "https://sso.bit.edu.cn\\@evil.example/",
            " https://sso.bit.edu.cn/", "https://sso.bit.edu.cn/\n", "file:///data/data/x", "content://x",
            "javascript:alert(1)", "intent://sso.bit.edu.cn/", "data:text/html,hello", "//sso.bit.edu.cn/")
            .forEach { assertFalse(it, PortalUrls.isAllowedSchoolUrl(it)) }
    }

    @Test fun injectsOnlyIntoExactCoursePageForSelectedMode() {
        for (mode in PortalMode.values()) {
            val page = PortalUrls.page(mode)
            assertTrue(PortalUrls.isCoursePage(page, mode))
            assertTrue(PortalUrls.isCoursePage("$page?foo=bar#tab", mode))
            assertFalse(PortalUrls.isCoursePage("$page/evil", mode))
            assertFalse(PortalUrls.isCoursePage(page.replace("index.do", "index.do.evil"), mode))
            assertFalse(PortalUrls.isCoursePage(page.replace("/jwapp/", "/evil/../jwapp/"), mode))
            assertFalse(PortalUrls.isCoursePage(page.replace("/jwapp/", "/%6awapp/"), mode))
            assertFalse(PortalUrls.isCoursePage(page.replace("https://", "https://user@"), mode))
            assertFalse(PortalUrls.isCoursePage(PortalUrls.term(mode), mode))
        }
        assertFalse(PortalUrls.isCoursePage(PortalUrls.page(PortalMode.WEBVPN), PortalMode.DIRECT))
        assertFalse(PortalUrls.isCoursePage(PortalUrls.page(PortalMode.DIRECT), PortalMode.WEBVPN))
        assertFalse(PortalUrls.isCoursePage("https://webvpn.bit.edu.cn/https/evil" + PortalUrls.PAGE_PATH, PortalMode.WEBVPN))
    }

    @Test fun onlyKnownApiPathsAndLoginDestinationsAreRecognized() {
        for (mode in PortalMode.values()) {
            assertTrue(PortalUrls.isApiUrl(PortalUrls.term(mode), mode))
            assertTrue(PortalUrls.isApiUrl(PortalUrls.courses(mode), mode))
            assertTrue(PortalUrls.isApiUrl(PortalUrls.calendar(mode), mode))
            assertFalse(PortalUrls.isApiUrl(PortalUrls.page(mode), mode))
            assertFalse(PortalUrls.isApiUrl(PortalUrls.term(mode) + "/redirect", mode))
            assertFalse(PortalUrls.isLoginPage(PortalUrls.page(mode), mode))
        }
        assertTrue(PortalUrls.isLoginPage("https://sso.bit.edu.cn/cas/login", PortalMode.DIRECT))
        assertTrue(PortalUrls.isLoginPage("https://webvpn.bit.edu.cn/login", PortalMode.WEBVPN))
        assertFalse(PortalUrls.isLoginPage("https://evil.example/cas/login", PortalMode.DIRECT))
    }
}
