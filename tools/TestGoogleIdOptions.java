import android.os.Bundle;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Run with app_process and the companion APK on its classpath. Uses no account or network APIs. */
public final class TestGoogleIdOptions {
    private static final String GOOGLE = "com.google.android.libraries.identity.googleid.";
    private static int checks;
    private static Object parser;
    private static Method parse;

    public static void main(String[] args) throws Exception {
        Class<?> params = Class.forName("org.microg.gms.auth.credentials.provider.GoogleIdRequestParams");
        parser = params.getField("Companion").get(null);
        parse = parser.getClass().getMethod("toGoogleIdRequestParams", Bundle.class);

        Object defaults = parse.invoke(parser, new Bundle());
        equal(false, read(defaults, "isSignInWithGoogle"));
        equal(true, read(defaults, "getFilterByAuthorized"));
        equal(false, read(defaults, "isAutoSelectAllowed"));
        equal(null, read(defaults, "getNonce"));

        Bundle google = new Bundle();
        google.putString(GOOGLE + "BUNDLE_KEY_SERVER_CLIENT_ID", "sample-client");
        google.putString(GOOGLE + "BUNDLE_KEY_NONCE", "sample-request-nonce");
        google.putBoolean(GOOGLE + "BUNDLE_KEY_FILTER_BY_AUTHORIZED_ACCOUNTS", false);
        google.putBoolean(GOOGLE + "BUNDLE_KEY_AUTO_SELECT_ENABLED", true);
        google.putString(GOOGLE + "BUNDLE_KEY_LINKED_SERVICE_ID", "sample-service");
        google.putBoolean(GOOGLE + "BUNDLE_KEY_REQUEST_VERIFIED_PHONE_NUMBER", true);
        Object request = parse.invoke(parser, google);
        equal("sample-client", read(request, "getServerClientId"));
        equal("sample-request-nonce", read(request, "getNonce"));
        equal(false, read(request, "getFilterByAuthorized"));
        equal(true, read(request, "isAutoSelectAllowed"));
        equal("sample-service", read(request, "getLinkedServiceId"));
        equal(true, read(request, "getRequestVerifiedPhoneNumber"));

        Bundle button = new Bundle();
        button.putString(GOOGLE + "BUNDLE_KEY_GOOGLE_ID_TOKEN_SUBTYPE", GOOGLE + "TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL");
        button.putString(GOOGLE + "siwg.BUNDLE_KEY_SERVER_CLIENT_ID", "button-client");
        button.putString(GOOGLE + "siwg.BUNDLE_KEY_NONCE", "button-request-nonce");
        button.putString(GOOGLE + "siwg.BUNDLE_KEY_HOSTED_DOMAIN_FILTER", "example.invalid");
        button.putBoolean(GOOGLE + "siwg.BUNDLE_KEY_AUTO_SELECT_ENABLED", true);
        Object selected = parse.invoke(parser, button);
        equal(true, read(selected, "isSignInWithGoogle"));
        equal("button-client", read(selected, "getServerClientId"));
        equal("button-request-nonce", read(selected, "getNonce"));
        equal("example.invalid", read(selected, "getHostedDomainFilter"));
        equal(true, read(selected, "isAutoSelectAllowed"));

        Bundle legacy = new Bundle();
        legacy.putBoolean("androidx.credentials.BUNDLE_KEY_IS_AUTO_SELECT_ALLOWED", true);
        equal(true, read(parse.invoke(parser, legacy), "isAutoSelectAllowed"));
        legacy.putBoolean(GOOGLE + "BUNDLE_KEY_AUTO_SELECT_ENABLED", false);
        equal(false, read(parse.invoke(parser, legacy), "isAutoSelectAllowed"));
        accountSelection();
        diagnosticPrivacy();
        System.out.println("GOOGLE_ID_CHECKS=" + checks + " PASS");
    }

    private static void accountSelection() throws Exception {
        var constructor = Class.forName("org.microg.gms.auth.signin.SignInAccountChoices")
                .getConstructor(Map.class, boolean.class);
        Map<String, Boolean> statuses = new LinkedHashMap<>();
        statuses.put("first", false);
        statuses.put("unavailable", null);
        statuses.put("second", true);
        Object priorGrants = constructor.newInstance(statuses, true);
        equal(Map.of("second", true), read(priorGrants, "getAccounts"));
        equal(Map.entry("second", true), read(priorGrants, "getOnlySelectable"));
        equal(null, read(constructor.newInstance(statuses, false), "getOnlySelectable"));
        statuses.put("third", true);
        equal(null, read(constructor.newInstance(statuses, true), "getOnlySelectable"));
        equal(Map.of(), read(constructor.newInstance(Map.of("first", false), true), "getAccounts"));
        equal(Map.of(), read(constructor.newInstance(java.util.Collections.singletonMap("unknown", null), true), "getAccounts"));
        equal(Map.entry("first", false), read(constructor.newInstance(Map.of("first", false), false), "getOnlySelectable"));
    }

    private static void diagnosticPrivacy() throws Exception {
        var constructor = Class.forName("com.google.android.gms.auth.api.signin.GoogleSignInAccount")
                .getConstructor(String.class, String.class, String.class, String.class, android.net.Uri.class,
                        String.class, long.class, String.class, Set.class, String.class, String.class);
        Object account = constructor.newInstance("private-id", "private-token", "private@example.invalid", "private-name",
                null, "private-auth-code", 42L, "private-obfuscated-id", Set.of(), null, null);
        equal(false, account.toString().contains("private"));
    }

    private static Object read(Object value, String getter) throws Exception {
        return value.getClass().getMethod(getter).invoke(value);
    }

    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("Google ID option was not preserved");
        checks++;
    }
}
