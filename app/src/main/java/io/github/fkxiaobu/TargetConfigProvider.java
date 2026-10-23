package io.github.fkxiaobu;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

/** Supplies the local bridge key only to the Xiaobu process for its private config. */
public final class TargetConfigProvider extends ContentProvider {
    public static final String AUTHORITY = "io.github.fkxiaobu.config";

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!"getAuthorizedModelConfig".equals(method)) return super.call(method, arg, extras);
        Context context = getContext();
        int uid = Binder.getCallingUid();
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        boolean allowed = false;
        if (packages != null) {
            for (String name : packages) if ("com.oplus.claw".equals(name)) allowed = true;
        }
        if (!allowed) throw new SecurityException("Only Xiaobu Next may read its local bridge settings");
        Bundle result = new Bundle();
        try {
            OpenAiAuth auth = new OpenAiAuth(context);
            String model = auth.preferredModel();
            boolean ready = auth.connected() && model != null;
            result.putBoolean("ready", ready);
            if (ready) {
                result.putString("baseUrl", BridgeService.BASE_URL);
                result.putString("apiKey", auth.bridgeKey());
                result.putString("model", model);
                result.putString("api", "openai-responses");
            }
        } catch (Exception error) {
            result.putBoolean("ready", false);
        }
        return result;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
