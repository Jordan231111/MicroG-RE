import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import android.os.Looper;
import android.os.Process;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Exercise the production logging path with a canary; prohibit every account-service access. */
public final class TestAuthDiagnostics {
    private static final class StopBeforeAccounts extends RuntimeException {}

    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            error.printStackTrace();
            System.exit(1);
        });
        if (args.length != 1) throw new IllegalArgumentException("Expected a synthetic canary");
        if (Looper.myLooper() == null) Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getDeclaredMethod("systemMain").invoke(null);
        Context system = (Context) activityThread.getDeclaredMethod("getSystemContext").invoke(thread);
        Context context = new ContextWrapper(system) {
            @Override public Object getSystemService(String name) {
                if (Context.ACCOUNT_SERVICE.equals(name)) throw new StopBeforeAccounts();
                return super.getSystemService(name);
            }
        };
        Class<?> service = Class.forName("org.microg.gms.auth.AuthManagerServiceImpl");
        Object implementation = service.getConstructor(Context.class).newInstance(context);
        Bundle extras = new Bundle();
        extras.putString("androidPackageName", "com.android.shell");
        extras.putInt("callerUid", Process.myUid());
        extras.putInt("callerPid", Process.myPid());
        extras.putString("nonce", args[0]);
        try {
            service.getMethod("clearToken", String.class, Bundle.class).invoke(implementation, args[0], extras);
            throw new AssertionError("Account service access was not intercepted");
        } catch (InvocationTargetException error) {
            if (!(error.getCause() instanceof StopBeforeAccounts)) throw error;
        }
        verifyClearTokenFailure(context);
        System.out.println("AUTH_DIAGNOSTICS_PID=" + Process.myPid());
    }

    private static void verifyClearTokenFailure(Context context) throws Exception {
        Class<?> ownerClass = Class.forName("androidx.lifecycle.LifecycleOwner");
        AtomicReference<Object> ownerLifecycle = new AtomicReference<>();
        Object owner = Proxy.newProxyInstance(ownerClass.getClassLoader(), new Class<?>[]{ownerClass},
                (proxy, method, values) -> method.getName().equals("getLifecycle") ? ownerLifecycle.get() : null);
        Class<?> registryClass = Class.forName("androidx.lifecycle.LifecycleRegistry");
        Object lifecycle = registryClass.getConstructor(ownerClass).newInstance(owner);
        ownerLifecycle.set(lifecycle);
        Class<?> stateClass = Class.forName("androidx.lifecycle.Lifecycle$State");
        registryClass.getMethod("setCurrentState", stateClass).invoke(lifecycle, stateClass.getField("STARTED").get(null));
        Class<?> service = Class.forName("org.microg.gms.auth.credentials.identity.AuthorizationServiceImpl");
        Object implementation = service.getConstructor(Context.class, String.class, Class.forName("androidx.lifecycle.Lifecycle"))
                .newInstance(context, "com.android.shell", lifecycle);
        Class<?> requestClass = Class.forName("com.google.android.gms.auth.api.identity.ClearTokenRequest");
        var constructor = requestClass.getDeclaredConstructor(String.class, String.class);
        constructor.setAccessible(true);
        Object request = constructor.newInstance("synthetic-clear-token", "synthetic-session");
        AtomicInteger result = new AtomicInteger(-1);
        Class<?> callbackClass = Class.forName("com.google.android.gms.common.api.internal.IStatusCallback");
        Object callback = Proxy.newProxyInstance(callbackClass.getClassLoader(), new Class<?>[]{callbackClass},
                (proxy, method, values) -> {
                    if (method.getName().equals("onResult")) {
                        result.set((Integer) values[0].getClass().getMethod("getStatusCode").invoke(values[0]));
                    }
                    return null;
                });
        service.getMethod("clearToken", callbackClass, requestClass).invoke(implementation, callback, request);
        if (result.get() != 8) throw new AssertionError("Failed token clearing did not return INTERNAL_ERROR: " + result.get());
        result.set(-1);
        service.getMethod("clearToken", callbackClass, requestClass).invoke(implementation, callback,
                constructor.newInstance("", "synthetic-session"));
        if (result.get() != 0) throw new AssertionError("Empty token clearing did not remain an idempotent success");
    }
}
