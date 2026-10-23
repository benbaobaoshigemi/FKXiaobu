package io.github.fkxiaobu;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import org.json.*;

final class OpenAiAuth {
    static final String ISSUER = "https://auth.openai.com";
    static final String RESOURCE = "https://api.openai.com/v1";
    static final String CALLBACK = "http://127.0.0.1:19081/auth/callback";
    private static final String SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
    private final SecureStore store;
    private JSONObject record;
    private volatile Pending pending;
    private volatile String visibleIdentity;
    private volatile String visibleBridgeKey;
    private static final class Pending {
        String state, nonce, verifier, clientId, subject;
        long created;
    }
    OpenAiAuth(Context context) throws Exception {
        store = new SecureStore(context);
        record = store.read();
        boolean initialize = !record.has("host") || !record.has("bridgeKey");
        if (!record.has("host")) record.put("host", "urn:uuid:" + UUID.randomUUID());
        if (!record.has("bridgeKey")) record.put("bridgeKey", random());
        if (initialize) store.write(record);
        visibleBridgeKey = record.getString("bridgeKey");
        updateVisibleIdentity();
    }
    static String random() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    String bridgeKey() { return visibleBridgeKey; }
    String status() {
        Pending attempt = pending;
        if (attempt != null) return System.currentTimeMillis() - attempt.created < 600000 ? "等待浏览器授权" : "授权已超时，请重新登录";
        return visibleIdentity;
    }
    private void updateVisibleIdentity() {
        visibleIdentity = record.has("access_token") ? "已登录：" + record.optString("email", record.optString("subject")) : "尚未登录";
    }
    synchronized boolean connected() { return record.has("access_token"); }
    synchronized Set<String> authorizedModels() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        JSONArray saved = record.optJSONArray("authorized_models");
        if (saved != null) for (int i = 0; i < saved.length(); i++) {
            String model = saved.optString(i, "");
            if (!model.isEmpty()) result.add(model);
        }
        return result;
    }
    synchronized String preferredModel() {
        Set<String> models = authorizedModels();
        if (models.contains("gpt-6-luna")) return "gpt-6-luna";
        return models.isEmpty() ? null : models.iterator().next();
    }
    synchronized boolean hasAuthorizedModel(String model) {
        return model != null && authorizedModels().contains(model);
    }
    synchronized void setAuthorizedModels(Collection<String> models) throws Exception {
        JSONArray array = new JSONArray();
        for (String model : models) if (model != null && !model.isEmpty()) array.put(model);
        JSONObject replacement = new JSONObject(record.toString());
        replacement.put("authorized_models", array);
        store.write(replacement);
        record = replacement;
    }
    synchronized String beginLogin() throws Exception {
        JSONObject discovery = getJson(ISSUER + "/.well-known/openid-configuration", null);
        IdentityVerifier.require(ISSUER.equals(discovery.getString("issuer")), "OIDC issuer 不匹配");
        IdentityVerifier.require((ISSUER + "/api/accounts/authorize").equals(discovery.getString("authorization_endpoint")), "授权地址不匹配");
        IdentityVerifier.require((ISSUER + "/api/accounts/oauth/token").equals(discovery.getString("token_endpoint")), "令牌地址不匹配");
        IdentityVerifier.require((ISSUER + "/.well-known/jwks.json").equals(discovery.getString("jwks_uri")), "签名密钥地址不匹配");
        Pending attempt = new Pending();
        attempt.state = random(); attempt.nonce = random(); attempt.verifier = random();
        attempt.created = System.currentTimeMillis();
        attempt.clientId = record.optString("client_id", record.optString("registration_client_id", "dynamic_agent_client"));
        attempt.subject = record.optString("subject", null);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(attempt.verifier.getBytes(StandardCharsets.US_ASCII)));
        Uri.Builder url = Uri.parse(discovery.getString("authorization_endpoint")).buildUpon()
                .appendQueryParameter("client_id", attempt.clientId)
                .appendQueryParameter("ext_agent_host_id", record.getString("host"))
                .appendQueryParameter("response_type", "code").appendQueryParameter("redirect_uri", CALLBACK)
                .appendQueryParameter("scope", SCOPES).appendQueryParameter("resource", RESOURCE)
                .appendQueryParameter("state", attempt.state).appendQueryParameter("nonce", attempt.nonce)
                .appendQueryParameter("code_challenge_method", "S256").appendQueryParameter("code_challenge", challenge);
        if ("dynamic_agent_client".equals(attempt.clientId)) url.appendQueryParameter("agent_name_hint", "FKXiaobu");
        else if (record.has("id_token")) url.appendQueryParameter("id_token_hint", record.getString("id_token"));
        pending = attempt;
        return url.build().toString();
    }
    synchronized void callback(Map<String, String> parameters) throws Exception {
        Pending attempt = pending;
        IdentityVerifier.require(attempt != null && System.currentTimeMillis() - attempt.created < 600000, "授权尝试已过期，请重新登录");
        IdentityVerifier.require(MessageDigest.isEqual(attempt.state.getBytes(StandardCharsets.UTF_8),
                parameters.getOrDefault("state", "").getBytes(StandardCharsets.UTF_8)), "OAuth state 验证失败");
        pending = null; // One-use callback, including denial/failure.
        IdentityVerifier.require(!parameters.containsKey("error"), "授权未完成或已被拒绝，请返回应用查看");
        String issued = parameters.getOrDefault("client_id", attempt.clientId);
        IdentityVerifier.require(!"dynamic_agent_client".equals(issued) && !issued.isEmpty(), "注册未返回正式 client_id");
        if (!"dynamic_agent_client".equals(attempt.clientId))
            IdentityVerifier.require(attempt.clientId.equals(issued), "返回 client_id 不匹配");
        String code = parameters.get("code");
        IdentityVerifier.require(code != null && !code.isEmpty(), "授权回调缺少 code");
        record.put("registration_client_id", issued); store.write(record);
        android.util.Log.i("FKXiaobuBridge", "OAuth callback accepted; exchanging code with official token endpoint");
        JSONObject tokens = tokenRequest(new String[][]{{"grant_type", "authorization_code"}, {"client_id", issued},
                {"code", code}, {"code_verifier", attempt.verifier}, {"redirect_uri", CALLBACK}, {"resource", RESOURCE}});
        JSONObject claims = IdentityVerifier.verify(tokens.getString("id_token"),
                getJson(ISSUER + "/.well-known/jwks.json", null), ISSUER, issued, attempt.nonce,
                attempt.subject, System.currentTimeMillis() / 1000);
        validateScopes(tokens.getString("scope"));
        JSONObject replacement = new JSONObject(record.toString());
        replacement.put("client_id", issued).put("subject", claims.getString("sub"))
                .put("email", claims.optString("email", claims.getString("sub")));
        saveTokens(replacement, tokens);
        android.util.Log.i("FKXiaobuBridge", "Official OAuth identity and plan scopes validated; credentials saved privately");
    }
    private static void validateScopes(String scope) {
        Set<String> granted = new HashSet<>(Arrays.asList(scope.trim().split("\\s+")));
        IdentityVerifier.require(granted.contains("chatgpt.tokens.use.direct") && granted.contains("resource.invoke"),
                "账户未授权 ChatGPT 方案推理权限");
    }
    private void saveTokens(JSONObject replacement, JSONObject tokens) throws Exception {
        IdentityVerifier.require(!tokens.getString("access_token").isEmpty(), "缺少 access token");
        IdentityVerifier.require("Bearer".equalsIgnoreCase(tokens.getString("token_type")), "不支持的 token type");
        replacement.put("access_token", tokens.getString("access_token"));
        if (tokens.has("refresh_token")) replacement.put("refresh_token", tokens.getString("refresh_token"));
        if (tokens.has("id_token")) replacement.put("id_token", tokens.getString("id_token"));
        if (tokens.has("scope")) { validateScopes(tokens.getString("scope")); replacement.put("scope", tokens.getString("scope")); }
        replacement.put("expires_at", System.currentTimeMillis() + tokens.getLong("expires_in") * 1000L);
        store.write(replacement); record = replacement; updateVisibleIdentity();
    }
    synchronized String accessToken() throws Exception {
        IdentityVerifier.require(record.has("access_token"), "请先使用 ChatGPT 登录");
        if (record.optLong("expires_at") - System.currentTimeMillis() > 60000) return record.getString("access_token");
        IdentityVerifier.require(record.has("refresh_token"), "登录已过期，请重新授权");
        JSONObject tokens = tokenRequest(new String[][]{{"grant_type", "refresh_token"},
                {"client_id", record.getString("client_id")}, {"refresh_token", record.getString("refresh_token")}, {"resource", RESOURCE}});
        if (tokens.has("id_token")) IdentityVerifier.verify(tokens.getString("id_token"),
                getJson(ISSUER + "/.well-known/jwks.json", null), ISSUER, record.getString("client_id"), null,
                record.getString("subject"), System.currentTimeMillis() / 1000);
        saveTokens(new JSONObject(record.toString()), tokens);
        return record.getString("access_token");
    }
    synchronized void logout() throws Exception {
        JSONObject replacement = new JSONObject().put("host", record.getString("host")).put("bridgeKey", random());
        store.write(replacement); record = replacement; updateVisibleIdentity(); visibleBridgeKey = record.getString("bridgeKey"); pending = null;
    }
    private static JSONObject tokenRequest(String[][] fields) throws Exception {
        StringBuilder form = new StringBuilder();
        for (String[] field : fields) {
            if (form.length() > 0) form.append('&');
            form.append(URLEncoder.encode(field[0], "UTF-8")).append('=').append(URLEncoder.encode(field[1], "UTF-8"));
        }
        return requestJson(ISSUER + "/api/accounts/oauth/token", null, form.toString());
    }
    static JSONObject getJson(String url, String token) throws Exception { return requestJson(url, token, null); }
    private static JSONObject requestJson(String url, String token, String form) throws Exception {
        HttpURLConnection connection = connection(url, token);
        try {
            if (form != null) {
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                try (OutputStream out = connection.getOutputStream()) { out.write(form.getBytes(StandardCharsets.UTF_8)); }
            }
            int status = connection.getResponseCode();
            android.util.Log.i("FKXiaobuBridge", (form == null ? "Official metadata" : "Official token endpoint") + " HTTP " + status);
            if (status < 200 || status >= 300) {
                String reason = "";
                try (InputStream errors = connection.getErrorStream()) {
                    if (errors != null) {
                        JSONObject failure = new JSONObject(new String(readBounded(errors, 8192), StandardCharsets.UTF_8));
                        Object detail = failure.opt("error");
                        String code = detail instanceof JSONObject ? ((JSONObject) detail).optString("code") : detail instanceof String ? (String) detail : "";
                        if (code.matches("[a-zA-Z0-9_]{1,100}")) reason = "（" + code + "）";
                    }
                } catch (Exception ignored) { }
                throw new IOException("OpenAI 返回 HTTP " + status + reason + "，请求已停止");
            }
            try (InputStream input = connection.getInputStream()) { return new JSONObject(new String(readBounded(input, 2 * 1024 * 1024), StandardCharsets.UTF_8)); }
        } finally { connection.disconnect(); }
    }
    static HttpURLConnection connection(String url, String token) throws Exception {
        URL target = new URL(url);
        IdentityVerifier.require("https".equals(target.getProtocol()) &&
                ("auth.openai.com".equals(target.getHost()) || "api.openai.com".equals(target.getHost())), "上游地址不在官方地址范围内");
        HttpURLConnection connection = (HttpURLConnection) target.openConnection();
        connection.setConnectTimeout(15000); connection.setReadTimeout(90000); connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        return connection;
    }
    static byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > limit) throw new IOException("响应或请求体过大");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
