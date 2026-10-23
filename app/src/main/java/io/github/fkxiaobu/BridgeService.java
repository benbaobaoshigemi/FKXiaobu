package io.github.fkxiaobu;

import android.app.*;
import android.content.Intent;
import android.os.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.json.*;
import fi.iki.elonen.NanoHTTPD;

public final class BridgeService extends Service {
    static final String PREFERRED_MODEL = "gpt-6-luna";
    static final String BASE_URL = "http://127.0.0.1:19081/v1";
    private static final String CHANNEL = "chatgpt_bridge";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LocalBinder binder = new LocalBinder();
    private OpenAiAuth auth;
    private NanoHTTPD server;
    private volatile String message = "正在启动";
    private volatile JSONArray models = new JSONArray();
    private volatile Set<String> accountModels = Collections.emptySet();
    public final class LocalBinder extends Binder { BridgeService service() { return BridgeService.this; } }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "ChatGPT 本机连接", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("FK小布 ChatGPT 本机服务").setContentText("仅监听手机 127.0.0.1；点击管理登录")
                .setContentIntent(open).setOngoing(true).build();
        startForeground(1, notification);
        try {
            auth = new OpenAiAuth(this);
            server = new NanoHTTPD("127.0.0.1", 19081) {
                @Override public Response serve(IHTTPSession session) {
                    android.util.Log.i("FKXiaobuBridge", session.getMethod() + " " + session.getUri());
                    return handle(session);
                }
            };
            server.start(30000, false);
            message = "本机服务已启动";
            if (auth.connected()) worker.execute(() -> {
                try { catalog(); message = "已验证账户模型目录，可同步账户可用模型到小布"; }
                catch (Exception error) { message = safeError(error); }
            });
        } catch (Exception error) { message = safeError(error); }
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) { return START_NOT_STICKY; }
    @Override public void onDestroy() {
        if (server != null) server.stop(); worker.shutdownNow(); super.onDestroy();
    }
    String status() { return message + "\n" + (auth == null ? "凭据存储不可用" : auth.status()); }
    String bridgeKey() { return auth == null ? "" : auth.bridgeKey(); }
    JSONArray modelList() { return models; }
    void beginLogin(Consumer<String> success, Consumer<String> failure) {
        if (server == null || !server.isAlive()) { failure.accept("本机监听未启动"); return; }
        worker.execute(() -> {
            try { String url = auth.beginLogin(); message = "等待浏览器授权"; main.post(() -> success.accept(url)); }
            catch (Exception error) { message = safeError(error); main.post(() -> failure.accept(message)); }
        });
    }
    void loadModels(Consumer<String> done) {
        worker.execute(() -> {
            try {
                JSONObject catalog = catalog();
                models = catalog.getJSONArray("data");
                message = "已获取账户模型列表：" + models.length() + " 个";
            } catch (Exception error) { message = safeError(error); }
            main.post(() -> done.accept(status()));
        });
    }
    void logout(Consumer<String> done) {
        worker.execute(() -> {
            try { auth.logout(); models = new JSONArray(); message = "已清除本机登录；本机连接密钥已轮换"; }
            catch (Exception error) { message = safeError(error); }
            main.post(() -> done.accept(status()));
        });
    }
    void testModel(String model, Consumer<String> done) {
        worker.execute(() -> {
            HttpURLConnection upstream = null;
            try {
                JSONArray content = new JSONArray().put(new JSONObject().put("type", "input_text")
                        .put("text", "Reply with exactly: FKXiaobu OK"));
                JSONArray input = new JSONArray().put(new JSONObject().put("role", "user").put("content", content));
                upstream = responseRequest(new JSONObject().put("model", model).put("input", input));
                int status = upstream.getResponseCode();
                if (status != 200) throw new IOException("OpenAI 返回 HTTP " + status + "，验证已停止");
                boolean completed = false;
                StringBuilder text = new StringBuilder();
                int bytes = 0;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(upstream.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        bytes += line.length();
                        if (bytes > 1024 * 1024) throw new IOException("测试响应过大");
                        if (!line.startsWith("data: ") || "data: [DONE]".equals(line)) continue;
                        JSONObject event = new JSONObject(line.substring(6));
                        String type = event.optString("type");
                        if ("response.output_text.delta".equals(type)) text.append(event.optString("delta"));
                        if ("response.completed".equals(type)) completed = true;
                        if ("response.failed".equals(type) || "response.incomplete".equals(type) || "error".equals(type))
                            throw new IOException("OpenAI 未完成测试响应（" + type + "），验证已停止");
                    }
                }
                IdentityVerifier.require(completed, "流未出现 response.completed，不能认定成功");
                message = "真实推理已完成：" + text.toString().substring(0, Math.min(text.length(), 200));
            } catch (Exception error) { message = safeError(error); }
            finally { if (upstream != null) upstream.disconnect(); }
            main.post(() -> done.accept(status()));
        });
    }
    private JSONObject catalog() throws Exception {
        JSONObject original = OpenAiAuth.getJson(OpenAiAuth.RESOURCE + "/models", auth.accessToken());
        JSONArray source = original.optJSONArray("models");
        if (source == null) source = original.getJSONArray("data");
        JSONArray result = new JSONArray();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < source.length(); i++) {
            JSONObject model = source.getJSONObject(i);
            String id = model.optString("slug", model.optString("id"));
            if (id.isEmpty()) continue;
            if (!ids.add(id)) continue;
            result.put(new JSONObject().put("id", id).put("object", "model")
                    .put("owned_by", "openai").put("display_name", model.optString("display_name", id)));
        }
        accountModels = Collections.unmodifiableSet(ids);
        models = result;
        auth.setAuthorizedModels(ids);
        return new JSONObject().put("object", "list").put("data", result);
    }
    private HttpURLConnection responseRequest(JSONObject request) throws Exception {
        String model = request.optString("model", "");
        IdentityVerifier.require(!model.trim().isEmpty(), "Responses 请求缺少 model");
        if (!accountModels.contains(model)) catalog();
        IdentityVerifier.require(accountModels.contains(model), "所选模型不在 ChatGPT 账户返回的模型目录中");
        request.put("store", false).put("stream", true);
        IdentityVerifier.require(!request.optBoolean("background", false), "本机服务不支持后台 Responses 请求");
        HttpURLConnection upstream = OpenAiAuth.connection(OpenAiAuth.RESOURCE + "/responses", auth.accessToken());
        try {
            upstream.setReadTimeout(20000);
            upstream.setRequestMethod("POST"); upstream.setDoOutput(true);
            upstream.setRequestProperty("Content-Type", "application/json");
            upstream.setRequestProperty("Accept", "text/event-stream");
            byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
            upstream.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = upstream.getOutputStream()) { out.write(body); }
            return upstream;
        } catch (Exception error) { upstream.disconnect(); throw error; }
    }
    private NanoHTTPD.Response handle(NanoHTTPD.IHTTPSession session) {
        try {
            String host = session.getHeaders().getOrDefault("host", "");
            if (!"127.0.0.1:19081".equals(host)) return error(NanoHTTPD.Response.Status.BAD_REQUEST, "invalid_host");
            if ("/auth/callback".equals(session.getUri()) && session.getMethod() == NanoHTTPD.Method.GET) {
                Map<String, String> parameters = new HashMap<>();
                for (Map.Entry<String, List<String>> item : session.getParameters().entrySet()) {
                    IdentityVerifier.require(item.getValue().size() == 1, "重复的授权回调参数");
                    parameters.put(item.getKey(), item.getValue().get(0));
                }
                try {
                    auth.callback(parameters); message = "官方登录完成，请返回 FK小布 获取模型列表";
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/html; charset=utf-8",
                            "<!doctype html><meta charset=utf-8><title>FKXiaobu</title><p>登录已完成。请返回 FK小布，点击获取模型列表。</p>");
                } catch (Exception failure) {
                    message = safeError(failure);
                    return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "text/html; charset=utf-8",
                            "<!doctype html><meta charset=utf-8><title>FKXiaobu</title><p>授权未完成。请返回 FK小布 查看状态并重新登录。</p>");
                }
            }
            if (session.getHeaders().containsKey("origin")) return error(NanoHTTPD.Response.Status.FORBIDDEN, "browser_requests_not_allowed");
            String supplied = session.getHeaders().getOrDefault("authorization", "");
            if (auth == null || !MessageDigest.isEqual(("Bearer " + auth.bridgeKey()).getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
                return error(NanoHTTPD.Response.Status.UNAUTHORIZED, "local_bridge_key_required");
            if ("/v1/models".equals(session.getUri()) && session.getMethod() == NanoHTTPD.Method.GET)
                return json(NanoHTTPD.Response.Status.OK, catalog().toString());
            if (!"/v1/responses".equals(session.getUri()) || session.getMethod() != NanoHTTPD.Method.POST)
                return error(NanoHTTPD.Response.Status.NOT_FOUND, "use_responses_protocol");
            String type = session.getHeaders().getOrDefault("content-type", "");
            IdentityVerifier.require(type.toLowerCase(Locale.ROOT).startsWith("application/json"), "请求类型必须为 JSON");
            IdentityVerifier.require(!session.getHeaders().containsKey("transfer-encoding"), "请使用固定长度 JSON 请求");
            int length = Integer.parseInt(session.getHeaders().getOrDefault("content-length", "-1"));
            IdentityVerifier.require(length >= 0 && length <= 8 * 1024 * 1024, "请求长度超限或未指定");
            byte[] body = new byte[length]; int offset = 0;
            while (offset < length) {
                int count = session.getInputStream().read(body, offset, length - offset);
                if (count < 0) throw new IOException("请求体被截断");
                offset += count;
            }
            HttpURLConnection upstream = responseRequest(new JSONObject(new String(body, StandardCharsets.UTF_8)));
            try {
                int status = upstream.getResponseCode();
                if (status != 200) {
                    message = "OpenAI 返回 HTTP " + status + "，请求已停止";
                    String result;
                    try (InputStream input = upstream.getErrorStream()) {
                        result = input == null ? "{\"error\":{\"message\":\"upstream_rejected\"}}" : new String(OpenAiAuth.readBounded(input, 65536), StandardCharsets.UTF_8);
                    } finally { upstream.disconnect(); }
                    final int code = status;
                    return json(new NanoHTTPD.Response.IStatus() {
                        public int getRequestStatus() { return code; }
                        public String getDescription() { return code + " Upstream Rejected"; }
                    }, result);
                }
                InputStream stream = new FilterInputStream(upstream.getInputStream()) {
                    @Override public void close() throws IOException { try { super.close(); } finally { upstream.disconnect(); } }
                };
                NanoHTTPD.Response response = NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "text/event-stream", stream);
                response.addHeader("Cache-Control", "no-store"); response.setKeepAlive(false);
                return response;
            } catch (Exception error) { upstream.disconnect(); throw error; }
        } catch (Exception failure) {
            message = safeError(failure);
            return error(NanoHTTPD.Response.Status.BAD_REQUEST, message);
        }
    }
    private static NanoHTTPD.Response json(NanoHTTPD.Response.IStatus status, String body) {
        NanoHTTPD.Response response = NanoHTTPD.newFixedLengthResponse(status, "application/json", body);
        response.addHeader("Cache-Control", "no-store"); response.setKeepAlive(false); return response;
    }
    private static NanoHTTPD.Response error(NanoHTTPD.Response.IStatus status, String code) {
        JSONObject object = new JSONObject();
        try { object.put("error", new JSONObject().put("message", code).put("type", "fkxiaobu_bridge_error")); } catch (JSONException ignored) { }
        return json(status, object.toString());
    }
    private static String safeError(Exception error) {
        if (error instanceof IllegalArgumentException) return error.getMessage();
        if (error instanceof IOException && error.getMessage() != null && !error.getMessage().contains("http")) return error.getMessage();
        return "操作失败（" + error.getClass().getSimpleName() + "），请求已停止";
    }
}
