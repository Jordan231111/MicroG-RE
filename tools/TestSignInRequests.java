import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/** Exercise real request factories without launching consent or accessing accounts. */
public final class TestSignInRequests {
    public static void main(String[] args) throws Exception {
        // app_process has no app process record for Android's crash reporter.
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            error.printStackTrace();
            System.exit(1);
        });
        if (Looper.myLooper() == null) Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getDeclaredMethod("systemMain").invoke(null);
        Context system = (Context) activityThread.getDeclaredMethod("getSystemContext").invoke(thread);
        Context context = system.createPackageContext("com.android.shell", 0);
        Class<?> ownerClass = Class.forName("androidx.lifecycle.LifecycleOwner");
        Object owner = Proxy.newProxyInstance(ownerClass.getClassLoader(), new Class<?>[]{ownerClass},
                (proxy, method, values) -> null);
        Object lifecycle = Class.forName("androidx.lifecycle.LifecycleRegistry")
                .getConstructor(ownerClass).newInstance(owner);
        Class<?> service = Class.forName("org.microg.gms.auth.credentials.identity.IdentitySignInServiceImpl");
        Class<?> lifecycleClass = Class.forName("androidx.lifecycle.Lifecycle");
        Object firstClient = service.getConstructor(Context.class, String.class, lifecycleClass)
                .newInstance(context, "test.first", lifecycle);
        Object secondClient = service.getConstructor(Context.class, String.class, lifecycleClass)
                .newInstance(context, "test.second", lifecycle);
        List<PendingIntent> pending = new ArrayList<>();
        List<String> nonces = new ArrayList<>();
        try {
            for (String name : new String[]{"performGoogleSignIn", "performFidoSignIn"}) {
                Method factory = service.getDeclaredMethod(name, Bundle.class);
                factory.setAccessible(true);
                for (Object client : new Object[]{firstClient, secondClient}) {
                    for (int i = 0; i < 3; i++) {
                        Bundle request = new Bundle();
                        String nonce = "synthetic-nonce-" + pending.size();
                        request.putString("nonce", nonce);
                        PendingIntent created = (PendingIntent) factory.invoke(client, request);
                        boolean collides = pending.contains(created);
                        pending.add(created);
                        nonces.add(nonce);
                        if (collides) throw new AssertionError("Concurrent " + name + " requests share a pending intent");
                    }
                }
            }
            Method getIntent = PendingIntent.class.getDeclaredMethod("getIntent");
            getIntent.setAccessible(true);
            for (int i = 0; i < pending.size(); i++) {
                Intent request = (Intent) getIntent.invoke(pending.get(i));
                if (!nonces.get(i).equals(request.getStringExtra("nonce"))) {
                    throw new AssertionError("A pending request's nonce was replaced");
                }
            }
            verifyCallerValidation(context);
            System.out.println("SIGN_IN_REQUESTS=PASS; " + pending.size() + " isolated pending intents");
        } finally {
            for (PendingIntent intent : pending) intent.cancel();
        }
    }

    private static void verifyCallerValidation(Context context) throws Exception {
        Class<?> requestClass = Class.forName("com.google.android.gms.common.internal.GetServiceRequest");
        Class<?> serviceKind = Class.forName("org.microg.gms.common.GmsService");
        Class<?> callbackClass = Class.forName("com.google.android.gms.common.internal.IGmsCallbacks");
        Object callback = Proxy.newProxyInstance(callbackClass.getClassLoader(), new Class<?>[]{callbackClass},
                (proxy, method, values) -> null);
        Method attach = android.content.ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
        attach.setAccessible(true);
        String[][] services = {
            {"org.microg.gms.auth.credentials.identity.IdentitySignInService", "AUTH_API_IDENTITY_SIGNIN"},
            {"org.microg.gms.identitycredentials.IdentityCredentialApiService", "IDENTITY_CREDENTIALS"}
        };
        for (String[] entry : services) {
            Object kind = serviceKind.getField(entry[1]).get(null);
            Object request = requestClass.getConstructor(int.class)
                    .newInstance(serviceKind.getField("SERVICE_ID").getInt(kind));
            requestClass.getField("packageName").set(request, "android");
            Class<?> service = Class.forName(entry[0]);
            Object instance = service.getConstructor().newInstance();
            attach.invoke(instance, context);
            try {
                service.getMethod("handleServiceRequest", callbackClass, requestClass, serviceKind)
                        .invoke(instance, callback, request, kind);
                throw new AssertionError("Foreign caller package accepted by " + entry[0]);
            } catch (java.lang.reflect.InvocationTargetException error) {
                if (!(error.getCause() instanceof SecurityException)) throw error;
            }
        }
    }
}
