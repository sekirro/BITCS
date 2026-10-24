package cn.local.bitcourse.portal

import java.net.URI
import java.util.Locale

enum class PortalMode { DIRECT, WEBVPN }

/** Compare parsed hosts and complete paths, never a string prefix of a URL. */
object PortalUrls {
    const val DIRECT_ORIGIN = "https://jxzxehallapp.bit.edu.cn"
    const val VPN_ORIGIN = "https://webvpn.bit.edu.cn"
    const val VPN_PREFIX = "/https/77726476706e69737468656265737421faef5b842238695c720999bcd6572a216b231105adc27d"
    const val PAGE_PATH = "/jwapp/sys/wdkbby/*default/index.do"
    const val TERM_PATH = "/jwapp/sys/wdkbby/modules/jshkcb/dqxnxq.do"
    const val COURSE_PATH = "/jwapp/sys/wdkbby/modules/xskcb/cxxszhxqkb.do"
    const val CALENDAR_PATH = "/jwapp/sys/wdkbby/wdkbByController/cxzkbrq.do"

    fun origin(mode: PortalMode) = if (mode == PortalMode.DIRECT) DIRECT_ORIGIN else VPN_ORIGIN
    fun path(mode: PortalMode, path: String) = (if (mode == PortalMode.WEBVPN) VPN_PREFIX else "") + path
    fun page(mode: PortalMode) = origin(mode) + path(mode, PAGE_PATH)
    fun term(mode: PortalMode) = origin(mode) + path(mode, TERM_PATH)
    fun courses(mode: PortalMode) = origin(mode) + path(mode, COURSE_PATH) + "?vpn-12-o2-jxzxehallapp.bit.edu.cn"
    fun calendar(mode: PortalMode) = origin(mode) + path(mode, CALENDAR_PATH)

    internal fun parseSafe(url: String?): URI? {
        if (url.isNullOrEmpty() || url.any { it <= ' ' || it == '\\' || it == '\u007f' }) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.isAbsolute || uri.isOpaque || !uri.scheme.equals("https", true)) return null
        if (uri.rawUserInfo != null || (uri.port != -1 && uri.port != 443)) return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (host != "bit.edu.cn" && !host.endsWith(".bit.edu.cn")) return null
        if (host.endsWith('.') || host.contains('%')) return null
        // Disallow unusual authority spellings, encoded hosts, empty ports, or leading-zero ports.
        val authority = uri.rawAuthority?.lowercase(Locale.ROOT) ?: return null
        if (authority != host && authority != "$host:443") return null
        return uri
    }

    fun isAllowedSchoolUrl(url: String?) = parseSafe(url) != null

    fun isCoursePage(url: String?, mode: PortalMode): Boolean {
        val uri = parseSafe(url) ?: return false
        val expected = URI(page(mode))
        return uri.host.equals(expected.host, true) && uri.rawPath == expected.rawPath
    }

    fun isApiUrl(url: String?, mode: PortalMode): Boolean {
        val uri = parseSafe(url) ?: return false
        if (!uri.host.equals(URI(origin(mode)).host, true)) return false
        return uri.rawPath in listOf(TERM_PATH, COURSE_PATH, CALENDAR_PATH).map { path(mode, it) }
    }

    fun isLoginPage(url: String?, mode: PortalMode): Boolean {
        val uri = parseSafe(url) ?: return false
        if (isCoursePage(url, mode)) return false
        val value = (uri.rawPath ?: "").lowercase(Locale.ROOT)
        return uri.host.equals("sso.bit.edu.cn", true) || value.contains("/login") ||
            value.contains("/cas/") || value.contains("/auth-protocol-core/") ||
            (uri.host.equals("webvpn.bit.edu.cn", true) && (value.isEmpty() || value == "/"))
    }
}
