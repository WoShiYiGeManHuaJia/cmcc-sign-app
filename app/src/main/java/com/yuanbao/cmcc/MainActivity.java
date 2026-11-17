package com.yuanbao.cmcc;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.ConnectionSpec;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends Activity {

    private static final String BASE = "https://wx.10086.cn";
    private static final String ACT_URL = BASE + "/qwhdhub/qwhdmark/1021122301?channelId=P00000109876";
    private static final String UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148/wkwebview leadeon/12.5.2/CMCCIT";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private EditText etToken, etPhone;
    private TextView tvLog;
    private final StringBuilder logBuf = new StringBuilder();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private OkHttpClient http, httpNoFollow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 32, 24, 24);
        root.setBackgroundColor(0xFFF5F7FA);

        TextView title = new TextView(this);
        title.setText("移动签到");
        title.setTextSize(24);
        title.setTextColor(0xFF1A1A1A);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);

        TextView tip = new TextView(this);
        tip.setText("点一下就跑，下面会显示每一步结果");
        tip.setTextSize(13);
        tip.setTextColor(0xFF777777);
        tip.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(tip);

        etPhone = new EditText(this);
        etPhone.setHint("手机号");
        etPhone.setInputType(InputType.TYPE_CLASS_PHONE);
        etPhone.setText("17274079953");
        root.addView(etPhone);

        etToken = new EditText(this);
        etToken.setHint("App 票据 Cookie（抓包得到）");
        etToken.setTextSize(12);
        etToken.setText("JSESSIONID=d57f6da0-6821-473d-a1ad-6cb435ac5c91; "
                + "UID=mhrz2a590420410bbb9f0e51a67780d7; "
                + "Comment=SessionServer-unity; Path=/;HTTPOnly; ticketID=POD9; Secure");
        root.addView(etToken);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        Button bGo = new Button(this);
        bGo.setText("立即签到");
        bGo.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { go(); }
        });
        btns.addView(bGo, new LinearLayout.LayoutParams(0, -2, 1));
        Button bCopy = new Button(this);
        bCopy.setText("复制日志");
        bCopy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("log", logBuf.toString()));
                Toast.makeText(MainActivity.this, "已复制", Toast.LENGTH_SHORT).show();
            }
        });
        btns.addView(bCopy, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(btns);

        tvLog = new TextView(this);
        tvLog.setTextSize(12);
        tvLog.setTextColor(0xFF222222);
        tvLog.setPadding(16, 16, 16, 16);
        tvLog.setBackgroundColor(0xFFFFFFFF);
        ScrollView sv = new ScrollView(this);
        sv.addView(tvLog);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);

        final Map<String, Cookie> store = new HashMap<String, Cookie>();
        CookieJar jar = new CookieJar() {
            @Override
            public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                for (Cookie c : cookies) store.put(c.domain() + "|" + c.name(), c);
            }
            @Override
            public List<Cookie> loadForRequest(HttpUrl url) {
                List<Cookie> out = new ArrayList<Cookie>();
                for (Cookie c : store.values()) if (c.matches(url)) out.add(c);
                return out;
            }
        };
        List<ConnectionSpec> specs = new ArrayList<ConnectionSpec>();
        specs.add(ConnectionSpec.COMPATIBLE_TLS);
        specs.add(ConnectionSpec.MODERN_TLS);
        specs.add(ConnectionSpec.CLEARTEXT);
        OkHttpClient.Builder b = new OkHttpClient.Builder()
                .cookieJar(jar)
                .connectionSpecs(specs)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS);
        http = b.followRedirects(true).followSslRedirects(true).build();
        httpNoFollow = b.followRedirects(false).followSslRedirects(false).build();

        log("准备好，点「立即签到」。");
    }

    private void log(String s) {
        final String line = s + "\n";
        logBuf.append(line);
        ui.post(new Runnable() {
            public void run() {
                tvLog.setText(logBuf.toString());
            }
        });
    }

    private void go() {
        final String token = etToken.getText().toString().trim();
        final String phone = etPhone.getText().toString().trim();
        logBuf.setLength(0);
        pool.execute(new Runnable() {
            public void run() {
                try { sign(token, phone); }
                catch (Exception e) { log("❌ " + e.getClass().getSimpleName() + ": " + e.getMessage()); }
            }
        });
    }

    private String exec(OkHttpClient c, Request req) throws IOException {
        Response r = c.newCall(req).execute();
        try {
            String body = r.body() == null ? "" : r.body().string();
            log("HTTP " + r.code() + "  " + req.url().encodedPath());
            return body;
        } finally { r.close(); }
    }

    private Request.Builder api(String referer) {
        return new Request.Builder()
                .header("User-Agent", UA)
                .header("accept", "*/*")
                .header("origin", BASE)
                .header("referer", referer)
                .header("login-check", "1")
                .header("x-requested-with", "XMLHttpRequest")
                .header("accept-language", "zh-CN,zh-Hans;q=0.9");
    }

    private void sign(String token, String phone) throws Exception {
        if (token.isEmpty()) throw new RuntimeException("票据是空的，先填抓包 Cookie");
        if (!phone.matches("1\\d{10}")) throw new RuntimeException("手机号不对");

        // ① SSO 中转页取 sid
        HttpUrl sso = HttpUrl.parse(BASE + "/qwhdsso/login").newBuilder()
                .addQueryParameter("dlwmh", "true")
                .addQueryParameter("actUrl", ACT_URL).build();
        String html = exec(http, new Request.Builder().url(sso).header("User-Agent", UA).get().build());
        Matcher m = Pattern.compile("loginPath\\s*=\\s*'([^']+)'").matcher(html);
        if (!m.find()) {
            log("中转页片段：" + html.substring(0, Math.min(200, html.length())));
            throw new RuntimeException("没拿到 sid，SSO 入口可能变了");
        }
        String loginUrl = BASE + "/qwhdsso" + m.group(1);
        log("取到 sid 通道 ✓");

        // ② appTokenLogin 换票
        JSONObject body = new JSONObject();
        body.put("provinceCode", "731");
        body.put("cityCode", "0731");
        body.put("userCheckId", Long.toHexString(Long.parseLong(phone)));
        body.put("carrierOperator", "002");
        body.put("appVersionCode", "12.5.2");
        body.put("took", 300);
        body.put("jwtToken", JSONObject.NULL);
        body.put("token", token);
        Request req2 = api(ACT_URL).url(loginUrl)
                .header("Content-Type", "application/json;charset=UTF-8")
                .post(RequestBody.create(body.toString(), JSON)).build();
        String s2 = exec(http, req2);
        JSONObject j2 = new JSONObject(s2);
        log("换票 → " + j2.optString("code") + " " + j2.optString("msg"));
        if (!"SUCCESS".equals(j2.optString("code"))) throw new RuntimeException("换票失败：" + j2.optString("msg"));

        String pageUrl = j2.getJSONObject("data").optString("url");
        if (!pageUrl.startsWith(BASE)) throw new RuntimeException("活动页地址异常");

        // ③ 访问活动页，落 SESSION_TOKEN cookie
        String s3 = exec(httpNoFollow, new Request.Builder().url(pageUrl).header("User-Agent", UA).get().build());
        log("活动会话已建立（长度 " + s3.length() + "）");

        // ④ 查状态
        String s4 = exec(http, api(pageUrl).url(BASE + "/qwhdhub/api/mark/mark31/markstatus")
                .header("Content-Type", "application/json;charset=UTF-8")
                .post(RequestBody.create("{}", JSON)).build());
        log("状态：" + s4.substring(0, Math.min(200, s4.length())));

        // ⑤ 签到
        String date = new SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(new Date());
        JSONObject dm = new JSONObject();
        dm.put("date", date);
        String s5 = exec(http, api(pageUrl).url(BASE + "/qwhdhub/api/mark/mark31/domark")
                .header("Content-Type", "application/json;charset=UTF-8")
                .post(RequestBody.create(dm.toString(), JSON)).build());
        log("签到返回：" + s5.substring(0, Math.min(400, s5.length())));
        JSONObject j5 = new JSONObject(s5);
        String code = j5.optString("code");
        if ("SUCCESS".equals(code)) log("✅ 签到成功 / 今日已签");
        else log("⚠️ 未确认成功：" + code + " " + j5.optString("msg"));
    }
}
