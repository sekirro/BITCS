import cn.local.bitcourse.portal.PortalScripts;
import cn.local.bitcourse.portal.PortalUrls;
import cn.local.bitcourse.portal.PortalMode;
import com.google.gson.Gson;
import java.util.Map;
public class DumpScripts {
  public static void main(String[] args) {
    String page=PortalUrls.INSTANCE.page(PortalMode.DIRECT);
    String api=PortalUrls.INSTANCE.courses(PortalMode.DIRECT);
    String token="__offline_test_token";
    System.out.println(new Gson().toJson(Map.of(
      "begin",PortalScripts.INSTANCE.begin(token,page,api,Map.of("XNXQDM","2025-2026-1")),
      "poll",PortalScripts.INSTANCE.poll(token,page),"clear",PortalScripts.INSTANCE.clear(token),
      "page",page,"api",api,"token",token)));
  }
}
