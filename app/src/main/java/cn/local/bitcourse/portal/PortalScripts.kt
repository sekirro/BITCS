package cn.local.bitcourse.portal

import com.google.gson.Gson

/** Scripts only fetch course APIs. They never inspect forms, passwords, or cookies. */
internal object PortalScripts {
    private val gson = Gson()
    private fun json(value: String) = gson.toJson(value)

    fun begin(token: String, pageUrl: String, requestUrl: String, form: Map<String, String>?): String {
        val method = if (form == null) "GET" else "POST"
        return """
            (function() {
              'use strict';
              const key = ${json(token)}, expectedPage = ${json(pageUrl)}, endpoint = ${json(requestUrl)};
              if (window !== window.top || location.href !== expectedPage || document.readyState === 'loading') return 'not_ready';
              const page = new URL(expectedPage), api = new URL(endpoint);
              if (page.origin !== api.origin || page.protocol !== 'https:') return 'bad_page';
              const abort = new AbortController();
              const state = {token:key, page:expectedPage, status:'pending', abort:abort};
              Object.defineProperty(window, key, {value:state, configurable:true});
              const timeout = setTimeout(function(){ abort.abort(); }, 25000);
              (async function() {
                try {
                  const options = {method:${json(method)}, credentials:'same-origin', cache:'no-store', signal:abort.signal,
                    headers:{'Accept':'application/json, text/javascript, */*; q=0.01','X-Requested-With':'XMLHttpRequest'}};
                  const form = ${gson.toJson(form)};
                  if (form !== null) {
                    options.headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8';
                    options.body = new URLSearchParams(form).toString();
                  }
                  const response = await fetch(endpoint, options);
                  if (window[key] !== state || location.href !== expectedPage) return;
                  const finalUrl = new URL(response.url);
                  if (response.status === 401 || response.status === 403 || finalUrl.origin !== api.origin || finalUrl.pathname !== api.pathname) {
                    state.status = 'auth'; return;
                  }
                  if (!response.ok) { state.status='error'; state.message='学校接口暂时不可用，请稍后重试'; return; }
                  const body = await response.text();
                  if (window[key] !== state || location.href !== expectedPage) return;
                  if (body.length > 4000000) { state.status='error'; state.message='学校接口响应过大'; return; }
                  if (/^\s*</.test(body)) {
                    state.status = /type\s*=\s*["']?password|cas\/login|auth-protocol-core\/login/i.test(body) ? 'auth' : 'error';
                    state.message='学校接口没有返回课表数据，请稍后重试'; return;
                  }
                  let parsed;
                  try { parsed=JSON.parse(body); } catch (_) { state.status='error'; state.message='学校接口返回了无法识别的数据'; return; }
                  if (parsed && typeof parsed === 'object' && [401,403,'401','403'].includes(parsed.code === undefined ? parsed.status : parsed.code)) {
                    state.status='auth'; return;
                  }
                  state.status='complete'; state.body=body;
                } catch (_) {
                  if (window[key] === state && location.href === expectedPage) {
                    state.status='error'; state.message=abort.signal.aborted ? '学校接口响应超时，请重试' : '无法连接学校接口，请检查网络后重试';
                  }
                } finally { clearTimeout(timeout); }
              })();
              return 'started';
            })();
        """.trimIndent()
    }

    fun poll(token: String, pageUrl: String) = """
        (function() {
          'use strict';
          const key=${json(token)}, expectedPage=${json(pageUrl)};
          if (window !== window.top || location.href !== expectedPage) return JSON.stringify({status:'changed'});
          const state=window[key];
          if (!state || state.token !== key || state.page !== expectedPage) return JSON.stringify({status:'missing'});
          return JSON.stringify({token:key,page:state.page,status:state.status,body:state.body,message:state.message});
        })();
    """.trimIndent()

    fun clear(token: String) = """
        (function(){ const key=${json(token)}; const state=window[key];
          if(state && state.abort) state.abort.abort(); delete window[key]; })();
    """.trimIndent()
}
