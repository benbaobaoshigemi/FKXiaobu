package io.github.fkxiaobu;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.widget.*;
import org.json.*;

public final class MainActivity extends Activity {
    private BridgeService service;
    private TextView status;
    private Spinner picker;
    private boolean bound;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((BridgeService.LocalBinder) binder).service(); refresh();
        }
        @Override public void onServiceDisconnected(ComponentName name) { service = null; refresh(); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density); layout.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(this); scroll.addView(layout); setContentView(scroll);
        TextView title = new TextView(this); title.setText("FK小布 · ChatGPT 连接"); title.setTextSize(24); layout.addView(title);
        status = new TextView(this); status.setTextSize(16); layout.addView(status);
        addButton(layout, "启动手机本机服务", () -> {
            Intent intent = new Intent(this, BridgeService.class); startForegroundService(intent);
            if (!bound) bound = bindService(intent, connection, BIND_AUTO_CREATE);
        });
        addButton(layout, "Continue with ChatGPT", () -> {
            if (!ready()) return;
            status.setText("正在准备官方授权流程…");
            service.beginLogin(url -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                catch (Exception error) { status.setText("无法打开浏览器，请检查默认浏览器设置"); }
            }, error -> status.setText(error));
        });
        addButton(layout, "刷新登录状态", this::refresh);
        addButton(layout, "获取账户模型列表", () -> {
            if (!ready()) return;
            status.setText("正在获取账户可用模型…");
            service.loadModels(text -> { refresh(); populateModels(); });
        });
        picker = new Spinner(this); layout.addView(picker);
        addButton(layout, "验证所选模型（发送一条简短测试）", () -> {
            if (!ready() || picker.getSelectedItem() == null) { toast("请先获取并选择账户模型"); return; }
            String model = picker.getSelectedItem().toString();
            status.setText("正在验证，请等待完整响应…"); service.testModel(model, text -> status.setText(text));
        });
        TextView instructions = new TextView(this);
        instructions.setText("\n小布 Next 自定义模型配置\n地址：" + BridgeService.BASE_URL +
                "\n协议：openai-responses（Responses API）\n模型 ID：使用 ChatGPT 账户模型目录返回的任意模型 ID\nAPI Key：使用下方复制的本机连接密钥\n\n本机密钥用于小布连接本手机，不是 OpenAI token。服务只监听 127.0.0.1；不会监听局域网。\n\n需要先在 LSPosed 启用本模块，作用域仅选小布 Next，然后重启小布。仅小布自定义本机模型走该桥；内置模型及云端资格保持原样。\n");
        layout.addView(instructions);
        addButton(layout, "复制本机连接密钥", () -> { if (ready()) copy("本机连接密钥", service.bridgeKey()); });
        addButton(layout, "复制服务地址", () -> copy("服务地址", BridgeService.BASE_URL));
        addButton(layout, "复制所选模型 ID", () -> { if (picker.getSelectedItem() != null) copy("模型 ID", picker.getSelectedItem().toString()); });
        addButton(layout, "打开小布 Next", () -> {
            Intent intent = getPackageManager().getLaunchIntentForPackage("com.oplus.claw");
            if (intent != null) startActivity(intent); else toast("未找到小布 Next");
        });
        addButton(layout, "退出 ChatGPT 登录并清除本机凭据", () -> {
            if (ready()) service.logout(text -> { status.setText(text); populateModels(); });
        });
        addButton(layout, "停止本机服务", () -> {
            if (bound) { unbindService(connection); bound = false; }
            stopService(new Intent(this, BridgeService.class)); service = null; refresh();
        });
    }
    @Override public void onStart() {
        super.onStart();
        if (!bound) bound = bindService(new Intent(this, BridgeService.class), connection, 0);
    }
    @Override public void onResume() { super.onResume(); if (status != null) refresh(); }
    @Override public void onDestroy() { if (bound) unbindService(connection); super.onDestroy(); }
    private void refresh() { status.setText(service == null ? "服务未连接。请点击启动手机本机服务。" : service.status()); }
    private boolean ready() { if (service != null) return true; toast("请先启动手机本机服务"); return false; }
    private void populateModels() {
        if (service == null) return;
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        JSONArray models = service.modelList();
        for (int i = 0; i < models.length(); i++) values.add(models.optJSONObject(i).optString("id"));
        picker.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values));
        int preferred = values.indexOf(BridgeService.PREFERRED_MODEL);
        if (preferred >= 0) picker.setSelection(preferred);
    }
    private void copy(String label, String value) {
        ClipData clip = ClipData.newPlainText(label, value);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.os.PersistableBundle extras = new android.os.PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true); clip.getDescription().setExtras(extras);
        }
        getSystemService(ClipboardManager.class).setPrimaryClip(clip); toast("已复制" + label);
    }
    private void addButton(LinearLayout layout, String text, Runnable action) {
        Button button = new Button(this); button.setText(text); button.setOnClickListener(view -> action.run()); layout.addView(button);
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
}
