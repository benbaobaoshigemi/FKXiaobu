package io.github.fkxiaobu;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Opens Xiaobu's local entry and wires custom models to the phone-local Responses bridge. */
public final class LocalEntryHook implements IXposedHookLoadPackage {
    private static final String TARGET = "com.oplus.claw";
    private static final String LOCAL_BASE_URL = "http://127.0.0.1:19081/v1";
    private static final String PREFIX = "FKXiaobu: ";
    private final AtomicBoolean installed = new AtomicBoolean();
    private final AtomicBoolean configSyncStarted = new AtomicBoolean();
    private static volatile Context targetContext;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam pkg) {
        if (!TARGET.equals(pkg.packageName) || !TARGET.equals(pkg.processName)) return;
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!installed.compareAndSet(false, true)) return;
                try {
                    Context context = (Context) param.args[0];
                    targetContext = context.getApplicationContext();
                    installIndependently("presentation", () -> installPresentationHook(pkg.classLoader));
                    installIndependently("local provider template", () -> installLoopbackSupport(pkg.classLoader));
                    installIndependently("provider diagnostics", () -> installProviderDiagnostics(pkg.classLoader));
                    installIndependently("local readiness", () -> installLocalModelAccessGate(pkg.classLoader));
                    installIndependently("local provider dispatch", () -> installProviderDispatchGate(pkg.classLoader));
                    installIndependently("model config sync", () -> startAuthorizedConfigSync(context, pkg.classLoader));
                } catch (Throwable error) {
                    XposedBridge.log(PREFIX + "module startup failed: " + error);
                }
            }
        });
    }

    private interface HookSetup { void run() throws Throwable; }

    private void installIndependently(String name, HookSetup setup) {
        try { setup.run(); }
        catch (Throwable error) { XposedBridge.log(PREFIX + name + " hook setup failed: " + error); }
    }

    private void installProviderDiagnostics(ClassLoader loader) {
        try {
            Class<?> provider = XposedHelpers.findClass("com.openclaw.breeno.provider.j", loader);
            XposedBridge.hookAllMethods(provider, "d", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    XposedBridge.log(PREFIX + "custom provider sendTurn entered");
                }
            });
            XposedBridge.hookAllMethods(provider, "e", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    XposedBridge.log(PREFIX + "custom provider openAgentStream entered");
                }
            });
            XposedBridge.log(PREFIX + "custom provider send diagnostics installed");
        } catch (Throwable error) {
            XposedBridge.log(PREFIX + "provider pipeline diagnostic unavailable: " + error.getClass().getSimpleName());
        }
    }

    /**
     * Bypass Xiaobu's local background entitlement gate for custom models routed
     * to the phone-local Responses bridge. The bridge still validates the model
     * against the account's official model catalog before forwarding.
     */
    private void installLocalModelAccessGate(ClassLoader loader) {
        XposedHelpers.findAndHookMethod("com.oplus.claw.chat.ui.e0", loader, "invoke", Object.class, Object.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            int gate = -1;
                            for (Field field : param.thisObject.getClass().getDeclaredFields()) {
                                if (field.getType() == int.class) {
                                    field.setAccessible(true);
                                    gate = field.getInt(param.thisObject);
                                    break;
                                }
                            }
                            if (gate != 28) return;
                            if (!"com.oplus.claw".equals(((Context) param.args[0]).getPackageName())) return;
                            Object current = param.getResult();
                            if (current == null || !"com.oplus.claw.welcome.d2".equals(current.getClass().getName())) return;
                            if (!localCustomProviderConfigured((Context) param.args[0])) return;
                            Class<?> source = XposedHelpers.findClass(
                                    "com.oplus.claw.welcome.BackgroundModelAccessReadiness$Source", loader);
                            Object granted = source.getMethod("valueOf", String.class).invoke(null, "IN_MEMORY_GRANTED");
                            Class<?> readyClass = XposedHelpers.findClass("com.oplus.claw.welcome.e2", loader);
                            param.setResult(readyClass.getConstructor(source).newInstance(granted));
                            XposedBridge.log(PREFIX + "local app readiness gate bypassed for custom loopback Responses route");
                        } catch (Throwable error) {
                            XposedBridge.log(PREFIX + "local access gate not bypassed: " + error);
                        }
                    }
                });
        XposedBridge.log(PREFIX + "conditional local model access gate hook installed");
    }

    private boolean localCustomProviderConfigured(Context context) {
        try {
            if (context == null) return false;
            Bundle config = context.getContentResolver().call(
                    Uri.parse("content://io.github.fkxiaobu.config"), "getAuthorizedModelConfig", null, null);
            return config != null && "openai-responses".equals(config.getString("api"))
                    && LOCAL_BASE_URL.equals(config.getString("baseUrl"))
                    && config.getString("model") != null && !config.getString("model").isEmpty();
        } catch (Throwable error) {
            XposedBridge.log(PREFIX + "local route config lookup failed: " + error);
            return false;
        }
    }

    /** Dispatches any custom model using this phone's Responses bridge, skipping only Xiaobu's local account gates. */
    private void installProviderDispatchGate(ClassLoader loader) {
        Class<?> dispatcher = XposedHelpers.findClass("com.openclaw.ai.q1", loader);
        java.util.Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(dispatcher, "c0", new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object model = param.args[0];
                            Set<String> modelValues = modelStringValues(model);
                            String api = modelValues.contains("openai-responses") ? "openai-responses" : null;
                            String provider = modelValues.contains("custom") ? "custom" : null;
                            boolean local = modelValues.contains(LOCAL_BASE_URL);
                            if ("custom".equals(provider) || local) {
                                XposedBridge.log(PREFIX + "q1 dispatch reached provider=" + provider + " api=" + api + " local=" + local);
                            }
                            if (!"custom".equals(provider) || !"openai-responses".equals(api) || !local) {
                                return;
                            }
                            Object result = invokeRegisteredApiProvider(loader, api, param.args);
                            param.setResult(result);
                            XposedBridge.log(PREFIX + "local q1 access gates skipped; dispatched custom model to loopback Responses provider");
                        } catch (Throwable error) {
                            XposedBridge.log(PREFIX + "loopback API provider dispatch failed: " + error);
                        }
                    }
                });
        if (hooks.isEmpty()) throw new IllegalStateException("OpenClaw API dispatcher c0 not found");
        XposedBridge.log(PREFIX + "scoped provider dispatch gate installed on " + hooks.size() + " q1.c0 method(s)");
    }

    private Object invokeRegisteredApiProvider(ClassLoader loader, String api, Object[] args) throws Exception {
        Class<?> registry = XposedHelpers.findClass("com.openclaw.ai.c", loader);
        Object map = null;
        for (Field field : registry.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && Map.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                Object candidate = field.get(null);
                if (candidate instanceof Map) {
                    map = candidate;
                    break;
                }
            }
        }
        if (map == null) throw new IllegalStateException("API provider registry map not found");
        Object registration = ((Map<?, ?>) map).get(api);
        if (registration == null) throw new IllegalStateException("API provider is not registered: " + api);
        Object apiProvider = firstFieldValueOfClass(registration, "com.openclaw.ai.a");
        if (apiProvider == null) throw new IllegalStateException("registered API provider implementation not found");
        Object streamSimple = null;
        for (Field field : apiProvider.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            Object candidate = field.get(apiProvider);
            if (candidate == null || !implementsNamedInterface(candidate.getClass(), "xs.q")) continue;
            if (intFieldEquals(candidate, 1)) {
                streamSimple = candidate;
                break;
            }
        }
        if (streamSimple == null) throw new IllegalStateException("simple-stream API provider implementation not found");
        Method invoke = streamSimple.getClass().getMethod("invoke", Object.class, Object.class, Object.class);
        return invoke.invoke(streamSimple, args[0], args[1], args[2]);
    }

    private Set<String> modelStringValues(Object model) throws IllegalAccessException {
        Set<String> values = new HashSet<>();
        for (Field field : model.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
            field.setAccessible(true);
            Object value = field.get(model);
            if (value instanceof String) values.add((String) value);
        }
        return values;
    }

    private Object firstFieldValueOfClass(Object owner, String className) throws IllegalAccessException {
        for (Field field : owner.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            Object value = field.get(owner);
            if (value != null && className.equals(value.getClass().getName())) return value;
        }
        return null;
    }

    private boolean implementsNamedInterface(Class<?> type, String interfaceName) {
        for (Class<?> implemented : type.getInterfaces()) {
            if (interfaceName.equals(implemented.getName()) || implementsNamedInterface(implemented, interfaceName)) return true;
        }
        Class<?> parent = type.getSuperclass();
        return parent != null && implementsNamedInterface(parent, interfaceName);
    }

    private boolean intFieldEquals(Object owner, int expected) throws IllegalAccessException {
        for (Field field : owner.getClass().getDeclaredFields()) {
            if (field.getType() != int.class || Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            if (field.getInt(owner) == expected) return true;
        }
        return false;
    }

    private void installPresentationHook(ClassLoader loader) throws Exception {
        Class<?> state = XposedHelpers.findClass("com.oplus.claw.welcome.r", loader);
        Class<?> route = XposedHelpers.findClass("com.oplus.claw.welcome.k", loader);
        Class<?> failed = XposedHelpers.findClass("com.oplus.claw.welcome.j", loader);
        Class<?> main = XposedHelpers.findClass("com.oplus.claw.welcome.g", loader);
        Object foundMainRoute = null;
        for (Field field : main.getDeclaredFields()) {
            if (field.getType() == main && Modifier.isStatic(field.getModifiers())) {
                if (foundMainRoute != null) throw new IllegalStateException("ambiguous main route singleton");
                field.setAccessible(true);
                foundMainRoute = field.get(null);
            }
        }
        if (foundMainRoute == null) throw new IllegalStateException("main route singleton missing");
        final Object mainRoute = foundMainRoute;
        Constructor<?> selected = null;
        for (Constructor<?> constructor : state.getDeclaredConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length == 14 && types[9] == route && types[10] == boolean.class
                    && types[12] == boolean.class && types[13] == boolean.class) {
                if (selected != null) throw new IllegalStateException("ambiguous presentation constructor");
                selected = constructor;
            }
        }
        if (selected == null) throw new IllegalStateException("presentation signature mismatch");
        AtomicBoolean logged = new AtomicBoolean();
        XposedBridge.hookMethod(selected, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                // This changes only the local landing route; inference still goes through the
                // custom loopback provider and the account's official model authorization.
                if (!failed.isInstance(param.args[9])) return;
                param.args[9] = mainRoute;
                param.args[11] = null; // Hide the presentation-only cached failure panel.
                param.args[13] = false; // Release the UI splash, without recording qualification.
                if (logged.compareAndSet(false, true))
                    XposedBridge.log(PREFIX + "local UI route opened; server/model authorization unchanged");
            }
        });
        XposedBridge.log(PREFIX + "local presentation route hook ready");
    }
    private void installLoopbackSupport(ClassLoader loader) {
        // The vendor UI exposes a fixed set of provider templates. Add a named local
        // template backed by its existing generic custom-provider implementation.
        // This changes only the on-device model editor; inference still requires the
        // user's separately authorized ChatGPT subscription token in our bridge.
        XposedHelpers.findAndHookMethod("com.oplus.claw.settings.e4", loader, "b", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    ArrayList<Object> providers = new ArrayList<>((List<?>) param.getResult());
                    Object template = openAiTemplate(loader);
                    boolean present = false;
                    for (Object provider : providers) {
                        if ("custom".equals(XposedHelpers.getObjectField(provider, "f50496a"))) {
                            present = true;
                            break;
                        }
                    }
                    if (!present) providers.add(template);
                    param.setResult(providers);
                } catch (Throwable error) {
                    XposedBridge.log(PREFIX + "could not add local OpenAI provider template: " + error);
                }
            }
        });
        XposedHelpers.findAndHookMethod("com.oplus.claw.settings.e4", loader, "l", String.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!"custom".equals(param.args[0])) return;
                        try { param.setResult(openAiTemplate(loader)); }
                        catch (Throwable error) { XposedBridge.log(PREFIX + "could not resolve local OpenAI provider template: " + error); }
                    }
                });
        XposedHelpers.findAndHookMethod("com.oplus.claw.settings.e4", loader, "h", String.class, String.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if ("custom".equals(param.args[0]) && "http://127.0.0.1:19081/v1".equals(param.args[1]))
                            param.setResult(true);
                    }
                });
        XposedHelpers.findAndHookMethod("android.security.NetworkSecurityPolicy", loader,
                "isCleartextTrafficPermitted", String.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if ("127.0.0.1".equals(param.args[0])) param.setResult(true);
                    }
                });
        XposedBridge.log(PREFIX + "named local OpenAI template and loopback enabled; upstream TLS unchanged");
    }

    private Object openAiTemplate(ClassLoader loader) throws Exception {
        Class<?> template = XposedHelpers.findClass("com.oplus.claw.settings.h4", loader);
        return template.getDeclaredConstructor(String.class, String.class, String.class,
                        String.class, String.class, boolean.class)
                .newInstance("custom", "OpenAI（ChatGPT 订阅）", "http://127.0.0.1:19081/v1",
                        "openai-responses", null, false);
    }

    /** Sync the preferred account-visible model into Xiaobu's custom-provider config. */
    private void startAuthorizedConfigSync(Context context, ClassLoader loader) {
        if (!configSyncStarted.compareAndSet(false, true)) return;
        Thread sync = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Bundle config = context.getContentResolver().call(
                            Uri.parse("content://io.github.fkxiaobu.config"), "getAuthorizedModelConfig", null, null);
                    if (config != null && config.getBoolean("ready", false)) {
                        applyConfig(context, loader, config);
                        return;
                    }
                } catch (Throwable error) {
                    XposedBridge.log(PREFIX + "waiting for authorized local model config: " + error);
                }
                try { Thread.sleep(15000L); }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return; }
            }
        }, "FKXiaobu-model-config-sync");
        sync.setDaemon(true);
        sync.start();
    }

    private void applyConfig(Context context, ClassLoader loader, Bundle config) throws Exception {
        String modelId = config.getString("model");
        if (modelId == null || modelId.trim().isEmpty()) throw new IllegalStateException("account model catalog is empty");
        String entryId = "fkxiaobu-" + modelId.replaceAll("[^A-Za-z0-9._-]", "-");
        Class<?> mClass = XposedHelpers.findClass("bu.m", loader);
        Class<?> json = XposedHelpers.findClass("bu.n", loader);
        Method stringValue = json.getDeclaredMethod("c", String.class);
        Method boolValue = json.getDeclaredMethod("a", Boolean.class);
        Class<?> objectBuilderClass = XposedHelpers.findClass("bu.a0", loader);
        Method put = objectBuilderClass.getDeclaredMethod("b", mClass, String.class);
        Object formBuilder = objectBuilderClass.getDeclaredConstructor().newInstance();
        put.invoke(formBuilder, stringValue.invoke(null, entryId), "entryId");
        put.invoke(formBuilder, stringValue.invoke(null, modelId), "displayName");
        put.invoke(formBuilder, stringValue.invoke(null, "custom"), "providerId");
        put.invoke(formBuilder, stringValue.invoke(null, modelId), "modelId");
        put.invoke(formBuilder, stringValue.invoke(null, ""), "originalProviderId");
        put.invoke(formBuilder, stringValue.invoke(null, ""), "originalModelId");
        put.invoke(formBuilder, stringValue.invoke(null, ""), "currentPrimaryKey");
        put.invoke(formBuilder, stringValue.invoke(null, config.getString("baseUrl")), "apiBaseUrl");
        put.invoke(formBuilder, stringValue.invoke(null, config.getString("apiKey")), "apiKey");
        put.invoke(formBuilder, stringValue.invoke(null, config.getString("api")), "api");
        put.invoke(formBuilder, boolValue.invoke(null, Boolean.FALSE), "authHeader");
        put.invoke(formBuilder, boolValue.invoke(null, Boolean.TRUE), "reasoning");
        put.invoke(formBuilder, boolValue.invoke(null, Boolean.TRUE), "vision");
        Object form = objectBuilderClass.getDeclaredMethod("a").invoke(formBuilder);
        Class<?> configStore = XposedHelpers.findClass("com.openclaw.agents.config.o0", loader);
        Method update = configStore.getDeclaredMethod("o", Context.class, String.class, mClass, String.class);
        update.setAccessible(true);
        Object providerResult = update.invoke(null, context, "settings.models.custom.upsertAndSelect", form, "FKXiaobu.accountModel");
        if (!successfulConfigWrite(providerResult)) {
            String detail = String.valueOf(providerResult).replace(config.getString("apiKey"), "[redacted]");
            throw new IllegalStateException("Xiaobu rejected native custom-model save: " + detail);
        }
        XposedBridge.log(PREFIX + "account-visible custom model added to Xiaobu config and selected as primary: " + modelId);
    }

    private boolean successfulConfigWrite(Object result) throws Exception {
        for (Field field : result.getClass().getDeclaredFields()) {
            if (field.getType() == boolean.class) {
                field.setAccessible(true);
                return field.getBoolean(result);
            }
        }
        throw new NoSuchFieldException("config write result has no boolean success field");
    }

}
