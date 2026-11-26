import android.accounts.Account;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Account-free checks against the debug APK's real Binder, lifecycle and player parser. */
public final class TestGamesPlayers {
    private static int checks;
    private static final Handler MAIN;
    static {
        Looper.prepareMainLooper();
        MAIN = new Handler(Looper.getMainLooper());
    }

    public static void main(String[] args) {
        new Thread(() -> {
            try {
                run();
                System.out.println("GAMES_PLAYER_CHECKS=" + checks + " PASS");
                System.exit(0);
            } catch (Throwable error) {
                error.printStackTrace();
                System.exit(1);
            }
        }, "player-checks").start();
        Looper.loop();
    }

    private static void run() throws Exception {
        Class<?> extensions = Class.forName("org.microg.gms.games.ExtensionsKt");
        Method parse = extensions.getMethod("toPlayer", JSONObject.class);
        JSONObject minimal = new JSONObject().put("playerId", "synthetic-player").put("displayName", "Synthetic");
        Object player = parse.invoke(null, minimal);
        equal("synthetic-player", player.getClass().getMethod("getPlayerId").invoke(player));
        // These profile fields are optional in the REST response.
        equal(null, player.getClass().getMethod("getLevelInfo").invoke(player));
        for (String optional : new String[]{"title", "experienceInfo", "avatarImageUrl"}) {
            JSONObject value = new JSONObject(minimal.toString()).put(optional, JSONObject.NULL);
            equal("synthetic-player", player.getClass().getMethod("getPlayerId").invoke(parse.invoke(null, value)));
        }

        Object lifecycle = lifecycle("STARTED");
        IBinder service = service(lifecycle, player);
        for (String id : new String[]{null, "me", "synthetic-player"}) {
            Capture response = call(service, 13006, id, false);
            response.expect(0, 1);
        }
        call(service, 5014, "synthetic-player", null).expect(0, 1);
        // Force must reach the authorization path. The context prevents account
        // or network access, so this must fail rather than return fresh data.
        call(service, 13006, null, true).expect(1, 0);
        call(service, 13006, "different-player", false).expect(1, 0);

        setState(lifecycle, "CREATED");
        call(service, 13006, null, false).expect(0, 1);
        setState(lifecycle, "DESTROYED");
        call(service, 13006, null, false).expect(14, 0);
        call(service(lifecycle("DESTROYED"), player), 13006, null, true).expect(14, 0);
        Capture disconnected = new Capture();
        disconnected.disconnect = true;
        call(service(lifecycle("STARTED"), player), 13006, null, false, disconnected);
        equal(1, disconnected.calls);
        fetchRequests();
    }

    private static void fetchRequests() throws Exception {
        Class<?> cache = Class.forName("com.android.volley.Cache");
        Class<?> network = Class.forName("com.android.volley.Network");
        Class<?> delivery = Class.forName("com.android.volley.ResponseDelivery");
        Class<?> queueClass = Class.forName("com.android.volley.RequestQueue");
        AtomicInteger requests = new AtomicInteger();
        Object noCache = Proxy.newProxyInstance(cache.getClassLoader(), new Class<?>[]{cache}, (p, method, args) -> {
            if (!method.getName().equals("initialize")) throw new AssertionError("Player request accessed the shared disk cache");
            return null;
        });
        Object fakeNetwork = Proxy.newProxyInstance(network.getClassLoader(), new Class<?>[]{network}, (p, method, args) -> {
            Object request = args[0];
            equal(false, request.getClass().getMethod("shouldCache").invoke(request));
            equal("https://www.googleapis.com/games/v1/players/synthetic%2Fplayer%3Fquery", request.getClass().getMethod("getUrl").invoke(request));
            Method headers = request.getClass().getMethod("getHeaders");
            headers.setAccessible(true);
            equal("OAuth token-" + requests.incrementAndGet(), ((java.util.Map<?, ?>) headers.invoke(request)).get("Authorization"));
            return Class.forName("com.android.volley.NetworkResponse")
                    .getConstructor(int.class, byte[].class, java.util.Map.class, boolean.class, long.class)
                    .newInstance(200, "{\"playerId\":\"synthetic\",\"displayName\":\"Synthetic\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            java.util.Map.of("Cache-Control", "max-age=3600"), false, 0L);
        });
        Object executorDelivery = Class.forName("com.android.volley.ExecutorDelivery").getConstructor(java.util.concurrent.Executor.class)
                .newInstance((java.util.concurrent.Executor) Runnable::run);
        Object queue = queueClass.getConstructor(cache, network, int.class, delivery).newInstance(noCache, fakeNetwork, 1, executorDelivery);
        Class<?> continuation = Class.forName("kotlin.coroutines.Continuation");
        Method fetch = Class.forName("org.microg.gms.games.ExtensionsKt")
                .getMethod("fetchPlayer", Context.class, String.class, String.class, queueClass, continuation);
        queueClass.getMethod("start").invoke(queue);
        try {
            for (int i = 1; i <= 2; i++) {
                CountDownLatch done = new CountDownLatch(1);
                Object[] result = new Object[1];
                Object completion = Proxy.newProxyInstance(continuation.getClassLoader(), new Class<?>[]{continuation}, (p, method, args) -> {
                    if (method.getName().equals("getContext")) return Class.forName("kotlin.coroutines.EmptyCoroutineContext").getField("INSTANCE").get(null);
                    result[0] = args[0];
                    done.countDown();
                    return null;
                });
                Object immediate = fetch.invoke(null, new ContextWrapper(null), "token-" + i, "synthetic/player?query", queue, completion);
                if (immediate instanceof JSONObject) result[0] = immediate;
                else equal(true, done.await(10, TimeUnit.SECONDS));
                equal("synthetic", ((JSONObject) result[0]).getString("playerId"));
            }
            equal(2, requests.get());
        } finally {
            queueClass.getMethod("stop").invoke(queue);
        }
    }

    private static Object lifecycle(String state) throws Exception {
        Class<?> owner = Class.forName("androidx.lifecycle.LifecycleOwner");
        Object[] lifecycle = new Object[1];
        Object proxy = Proxy.newProxyInstance(owner.getClassLoader(), new Class<?>[]{owner},
                (p, method, args) -> lifecycle[0]);
        lifecycle[0] = onMain(() -> Class.forName("androidx.lifecycle.LifecycleRegistry")
                .getConstructor(owner).newInstance(proxy));
        // Transition through CREATED so destruction is a valid lifecycle event.
        setState(lifecycle[0], "CREATED");
        setState(lifecycle[0], state);
        return lifecycle[0];
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setState(Object lifecycle, String name) throws Exception {
        Class<?> state = Class.forName("androidx.lifecycle.Lifecycle$State");
        onMain(() -> lifecycle.getClass().getMethod("setCurrentState", state)
                .invoke(lifecycle, Enum.valueOf((Class) state, name)));
    }

    private static IBinder service(Object lifecycle, Object player) throws Exception {
        Context context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public String getPackageName() { return "synthetic.caller"; }
            @Override public Object getSystemService(String name) {
                throw new UnsupportedOperationException("Account access disabled in this test");
            }
        };
        return (IBinder) Class.forName("org.microg.gms.games.GamesServiceImpl")
                .getConstructor(Context.class, Class.forName("androidx.lifecycle.Lifecycle"),
                        String.class, Account.class, Class.forName("com.google.android.gms.games.Player"))
                .newInstance(context, lifecycle, "synthetic.caller", new Account("synthetic", "synthetic"), player);
    }

    private static Capture call(IBinder service, int transaction, String id, Boolean force) throws Exception {
        return call(service, transaction, id, force, new Capture());
    }

    private static Capture call(IBinder service, int transaction, String id, Boolean force, Capture result) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.google.android.gms.games.internal.IGamesService");
            data.writeStrongBinder(result);
            data.writeString(id);
            if (force != null) data.writeInt(force ? 1 : 0);
            equal(true, service.transact(transaction, data, reply, 0));
            reply.readException();
            equal(true, result.done.await(10, TimeUnit.SECONDS));
            return result;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static final class Capture extends Binder {
        final CountDownLatch done = new CountDownLatch(1);
        volatile int status = -1, rows = -1, calls;
        boolean disconnect;

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (disconnect) {
                calls++;
                done.countDown();
                throw new RemoteException("Synthetic disconnected client");
            }
            try {
                equal(5007, code);
                data.enforceInterface("com.google.android.gms.games.internal.IGamesCallbacks");
                equal(1, data.readInt());
                Class<?> holder = Class.forName("com.google.android.gms.common.data.DataHolder");
                Object value = ((Parcelable.Creator<?>) holder.getField("CREATOR").get(null)).createFromParcel(data);
                try {
                    status = (int) holder.getMethod("getStatusCode").invoke(value);
                    rows = (int) holder.getMethod("getCount").invoke(value);
                    equal(0, data.dataAvail());
                    calls++;
                } finally {
                    holder.getMethod("close").invoke(value);
                }
                if (reply != null) reply.writeNoException();
                done.countDown();
                return true;
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }

        void expect(int expectedStatus, int expectedRows) {
            equal(expectedStatus, status);
            equal(expectedRows, rows);
            equal(1, calls);
        }
    }

    private static <T> T onMain(java.util.concurrent.Callable<T> call) throws Exception {
        FutureTask<T> result = new FutureTask<>(call);
        MAIN.post(result);
        return result.get(10, TimeUnit.SECONDS);
    }

    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
        checks++;
    }
}
