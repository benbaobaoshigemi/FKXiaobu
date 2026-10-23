package io.github.fkxiaobu;

import android.content.Context;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Run on the phone; never prints or exports OpenAI credentials. */
@RunWith(AndroidJUnit4.class)
public class PhoneBridgeTest {
    private static android.content.ServiceConnection binding;
    @BeforeClass public static void startServiceForTests() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
        binding = new android.content.ServiceConnection() {
            public void onServiceConnected(android.content.ComponentName name, android.os.IBinder service) { ready.countDown(); }
            public void onServiceDisconnected(android.content.ComponentName name) { }
        };
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            android.content.Intent intent = new android.content.Intent(context, BridgeService.class);
            context.startForegroundService(intent);
            context.bindService(intent, binding, Context.BIND_AUTO_CREATE);
        });
        assertTrue("Local service did not become ready", ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
    }
    @AfterClass public static void releaseBinding() {
        if (binding != null) InstrumentationRegistry.getInstrumentation().getTargetContext().unbindService(binding);
    }
    private HttpURLConnection local(String path, String key) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:19081" + path).openConnection();
        connection.setConnectTimeout(5000); connection.setReadTimeout(90000); connection.setInstanceFollowRedirects(false);
        if (key != null) connection.setRequestProperty("Authorization", "Bearer " + key);
        return connection;
    }
    @Test public void rejectsUnauthenticatedLocalClient() throws Exception {
        HttpURLConnection connection = local("/v1/models", null);
        try { assertEquals(401, connection.getResponseCode()); } finally { connection.disconnect(); }
    }
    @Test public void rejectsIncorrectLocalCredential() throws Exception {
        HttpURLConnection connection = local("/v1/models", "incorrect-key");
        try { assertEquals(401, connection.getResponseCode()); } finally { connection.disconnect(); }
    }
    @Test public void configKeyIsNotDisclosedToNonTargetPackages() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try {
            context.getContentResolver().call(Uri.parse("content://io.github.fkxiaobu.config"),
                    "getAuthorizedModelConfig", null, null);
            fail("Bridge settings were exposed to a non-Xiaobu caller");
        } catch (SecurityException expected) {
            // Only com.oplus.claw's Android UID may receive the bridge credential.
        }
    }
    @Test public void authorizedSubscriptionCompletesOneRealResponse() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        OpenAiAuth auth = new OpenAiAuth(context);
        Assume.assumeTrue("Awaiting user-completed official OAuth authorization", auth.connected());
        String localKey = auth.bridgeKey();
        String model;
        HttpURLConnection catalog = local("/v1/models", localKey);
        try {
            assertEquals("Official model catalog rejected", 200, catalog.getResponseCode());
            JSONObject json;
            try (InputStream input = catalog.getInputStream()) {
                json = new JSONObject(new String(OpenAiAuth.readBounded(input, 2 * 1024 * 1024), StandardCharsets.UTF_8));
            }
            JSONArray models = json.getJSONArray("data");
            assertTrue("No account-visible models returned", models.length() > 0);
            model = null;
            for (int i = 0; i < models.length(); i++) {
                if (BridgeService.REQUESTED_MODEL.equals(models.getJSONObject(i).getString("id"))) model = BridgeService.REQUESTED_MODEL;
            }
            assertNotNull("Account catalog does not include requested GPT-6 Luna; no fallback attempted", model);
        } finally { catalog.disconnect(); }
        HttpURLConnection inference = local("/v1/responses", localKey);
        inference.setReadTimeout(25000);
        inference.setRequestMethod("POST"); inference.setDoOutput(true);
        inference.setRequestProperty("Content-Type", "application/json");
        JSONArray content = new JSONArray().put(new JSONObject().put("type", "input_text")
                .put("text", "Reply with exactly: FKXiaobu OK"));
        JSONArray input = new JSONArray().put(new JSONObject().put("role", "user").put("content", content));
        byte[] body = new JSONObject().put("model", model).put("input", input)
                .put("store", false).put("stream", true).toString().getBytes(StandardCharsets.UTF_8);
        inference.setFixedLengthStreamingMode(body.length);
        try {
            try (OutputStream output = inference.getOutputStream()) { output.write(body); }
            int responseCode = inference.getResponseCode();
            if (responseCode != 200) {
                InputStream error = inference.getErrorStream();
                String detail = error == null ? "no error body" : new String(OpenAiAuth.readBounded(error, 16 * 1024), StandardCharsets.UTF_8);
                assertEquals("GPT-6 Luna request rejected (HTTP " + responseCode + "): " + detail, 200, responseCode);
            }
            boolean completed = false; int chars = 0;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inference.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    chars += line.length(); assertTrue("Test stream unexpectedly large", chars < 1024 * 1024);
                    if (!line.startsWith("data: ") || "data: [DONE]".equals(line)) continue;
                    String type = new JSONObject(line.substring(6)).optString("type");
                    assertFalse("Official response failed; no retry attempted", "response.failed".equals(type) || "response.incomplete".equals(type) || "error".equals(type));
                    completed |= "response.completed".equals(type);
                }
            }
            assertTrue("Stream did not reach response.completed", completed);
        } finally { inference.disconnect(); }
    }
}
