# 学校网页登录脚本的离线回归测试

这些测试运行生产 `PortalScripts.kt` 生成的 JavaScript，使用 Node.js 内存沙箱与伪造的 `fetch` 响应。没有网络请求，没有真实学校账户、Cookie、密码、课表数据或构建 jar。

先准备 JDK 17+、Node.js、Kotlin 编译器库目录及 Gson 2.11 的 jar。Kotlin 库目录可以使用 Kotlin 编译器发行版的 `lib`，或包含 `kotlin-compiler-embeddable` 的 Gradle 发行版 `lib`。路径由调用者传入，未写入个人机器路径。

在 PowerShell 运行：

```powershell
./tools/portal-tests/run.ps1 `
  -JavaHome 'C:/your-tools/jdk-17' `
  -KotlinLibDirectory 'C:/your-tools/gradle/lib' `
  -GsonJar 'C:/your-tools/gson-2.11.0.jar' `
  -NodeExecutable 'node'
```

脚本每次都会重新编译当前 `PortalUrls.kt` 与 `PortalScripts.kt`，调用 `DumpScripts.java` 导出生成结果，再运行 `check-scripts.cjs`。生成类和 JSON 都位于 `app/build/portal-script-tests/`；不使用过时的脚本快照。

八个场景：

1. 顶层页面不是已确认的课程页时，不发起任何 fetch。
2. 校验 POST 参数、同源会话设置；正常空 `rows` 作为成功数据返回。
3. HTTP 401 被标记为需要登录。
4. 接口重定向到登录页面时不把 HTML 当课程。
5. JSON 的明确 401 错误码被标记为需要登录。
6. 登录表单 HTML 被标记为需要登录。
7. 取消会中止请求并删除一次性内存槽。
8. 请求中途顶层 URL 改变时，旧结果不会被发布。

URL 解析和异步消息验证另有 JUnit 测试，随 `./gradlew testDebugUnitTest` 运行。本脚本不能代替真实 Android WebView 的 UI、学校 SSO 或设备兼容性测试。
