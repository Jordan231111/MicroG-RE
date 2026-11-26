import android.os.Bundle;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        Method candidates = params.getMethod("candidateAccounts", List.class);
        equal(List.of(), candidates.invoke(defaults, List.of()));
        equal(java.util.Collections.singletonList(null), candidates.invoke(defaults, List.of("first", "second")));

        Bundle google = new Bundle();
        google.putString(GOOGLE + "BUNDLE_KEY_SERVER_CLIENT_ID", "sample-client");
        google.putString(GOOGLE + "BUNDLE_KEY_NONCE", "sample-request-nonce");
        google.putBoolean(GOOGLE + "BUNDLE_KEY_FILTER_BY_AUTHORIZED_ACCOUNTS", false);
        google.putBoolean(GOOGLE + "BUNDLE_KEY_AUTO_SELECT_ENABLED", true);
        google.putString(GOOGLE + "BUNDLE_KEY_LINKED_SERVICE_ID", "sample-service");
        google.putStringArrayList(GOOGLE + "BUNDLE_KEY_ID_TOKEN_DEPOSITION_SCOPES", new ArrayList<>(List.of("sample-scope")));
        google.putBoolean(GOOGLE + "BUNDLE_KEY_REQUEST_VERIFIED_PHONE_NUMBER", true);
        Object request = parse.invoke(parser, google);
        equal("sample-client", read(request, "getServerClientId"));
        equal("sample-request-nonce", read(request, "getNonce"));
        equal(false, read(request, "getFilterByAuthorized"));
        equal(true, read(request, "isAutoSelectAllowed"));
        equal("sample-service", read(request, "getLinkedServiceId"));
        equal(List.of("sample-scope"), read(request, "getIdTokenDepositionScopes"));
        equal(true, read(request, "getRequestVerifiedPhoneNumber"));
        equal(java.util.Collections.singletonList(null), candidates.invoke(request, List.of()));
        equal(List.of("first"), candidates.invoke(request, List.of("first")));
        equal(List.of("first", "second"), candidates.invoke(request, List.of("first", "second")));

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
        equal(java.util.Collections.singletonList(null), candidates.invoke(selected, List.of()));
        equal(List.of("first", "second"), candidates.invoke(selected, List.of("first", "second")));

        Bundle legacy = new Bundle();
        legacy.putBoolean("androidx.credentials.BUNDLE_KEY_IS_AUTO_SELECT_ALLOWED", true);
        equal(true, read(parse.invoke(parser, legacy), "isAutoSelectAllowed"));
        legacy.putBoolean(GOOGLE + "BUNDLE_KEY_AUTO_SELECT_ENABLED", false);
        equal(false, read(parse.invoke(parser, legacy), "isAutoSelectAllowed"));
        accountSelection();
        signInResultStatus();
        signInFailures();
        legacyCredentialErrors();
        credentialBundle();
        diagnosticPrivacy();
        System.out.println("GOOGLE_ID_CHECKS=" + checks + " PASS");
    }

    private static void legacyCredentialErrors() throws Exception {
        Class<?> helpers = Class.forName("org.microg.gms.auth.credentials.GoogleIdSignInKt");
        Method handle = helpers.getMethod("googleIdResultException", int.class, android.content.Intent.class);
        String statusKey = (String) Class.forName("org.microg.gms.auth.AuthConstants").getField("STATUS").get(null);
        Class<?> statusClass = Class.forName("com.google.android.gms.common.api.Status");
        for (int code : new int[]{0, 4, 8, 10, 16}) {
            Object status = statusClass.getConstructor(int.class, String.class).newInstance(code, null);
            android.os.Parcel parcel = android.os.Parcel.obtain();
            ((android.os.Parcelable) status).writeToParcel(parcel, 0);
            android.content.Intent data = new android.content.Intent().putExtra(statusKey, parcel.marshall());
            parcel.recycle();
            for (int resultCode : new int[]{0, -1}) {
                Object error = handle.invoke(null, resultCode, data);
                if (code == 0 && resultCode == -1) equal(null, error);
                else {
                    String suffix = code == 4 ? "TYPE_NO_CREDENTIAL" : code == 16 ? "TYPE_USER_CANCELED" : "TYPE_UNKNOWN";
                    equal(true, ((String) read(error, "getType")).endsWith(suffix));
                }
            }
        }
        equal(true, ((String) read(handle.invoke(null, 0, null), "getType")).endsWith("TYPE_USER_CANCELED"));
        equal(true, ((String) read(handle.invoke(null, -1, null), "getType")).endsWith("TYPE_UNKNOWN"));
        Method unsupported = helpers.getMethod("unsupportedGoogleIdOptions", String.class, String.class, List.class, boolean.class);
        equal(null, unsupported.invoke(null, null, null, null, false));
        for (Object[] options : new Object[][]{{"example.invalid", null, null, false},
                {null, "linked", null, false}, {null, null, List.of("scope"), false}, {null, null, null, true}}) {
            equal(true, unsupported.invoke(null, options) != null);
        }
        Class<?> params = Class.forName("org.microg.gms.auth.credentials.provider.GoogleIdRequestParams");
        Method create = helpers.getMethod("createSignInIntent", params, String.class, String.class, String.class);
        for (boolean button : new boolean[]{false, true}) {
            Bundle bundle = new Bundle();
            String prefix = GOOGLE + (button ? "siwg." : "");
            if (button) bundle.putString(GOOGLE + "BUNDLE_KEY_GOOGLE_ID_TOKEN_SUBTYPE", GOOGLE + "TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL");
            bundle.putString(prefix + "BUNDLE_KEY_SERVER_CLIENT_ID", "sample-client");
            bundle.putString(prefix + "BUNDLE_KEY_NONCE", "sample-nonce");
            bundle.putBoolean(prefix + "BUNDLE_KEY_AUTO_SELECT_ENABLED", true);
            bundle.putBoolean(GOOGLE + "BUNDLE_KEY_FILTER_BY_AUTHORIZED_ACCOUNTS", false);
            android.content.Intent intent = (android.content.Intent) create.invoke(null, parse.invoke(parser, bundle), "example.provider", "example.client", null);
            equal("example.provider", intent.getPackage());
            String className = "com.google.android.gms.auth.api.identity." + (button ? "GetSignInIntentRequest" : "BeginSignInRequest");
            android.os.Parcelable.Creator<?> creator = (android.os.Parcelable.Creator<?>) Class.forName(className).getField("CREATOR").get(null);
            byte[] bytes = intent.getByteArrayExtra(button ? "get_sign_in_intent_request" : "begin_sign_in_request");
            android.os.Parcel parcel = android.os.Parcel.obtain();
            parcel.unmarshall(bytes, 0, bytes.length); parcel.setDataPosition(0);
            Object request = creator.createFromParcel(parcel); parcel.recycle();
            Object options = button ? request : read(request, "getGoogleIdTokenRequestOptions");
            equal("sample-client", read(options, "getServerClientId"));
            equal("sample-nonce", read(options, "getNonce"));
            if (!button) {
                equal(false, read(options, "filterByAuthorizedAccounts"));
                equal(true, read(request, "isAutoSelectEnabled"));
            }
        }
    }

    private static void signInResultStatus() throws Exception {
        Class<?> status = Class.forName("com.google.android.gms.common.api.Status");
        Method mapping = Class.forName("org.microg.gms.auth.signin.AssistedSignInActivityKt")
                .getMethod("signInActivityResultStatus", int.class, status, boolean.class);
        Method code = status.getMethod("getStatusCode");
        var constructor = status.getConstructor(int.class, String.class);
        for (int failure : new int[]{4, 8, 10, 16}) {
            equal(failure, code.invoke(mapping.invoke(null, -1, constructor.newInstance(failure, null), false)));
        }
        equal(0, code.invoke(mapping.invoke(null, -1, constructor.newInstance(0, null), true)));
        equal(8, code.invoke(mapping.invoke(null, -1, constructor.newInstance(0, null), false)));
        equal(16, code.invoke(mapping.invoke(null, 0, null, false)));
    }

    private static void credentialBundle() throws Exception {
        Class<?> credential = Class.forName("com.google.android.gms.auth.api.identity.SignInCredential");
        var constructor = credential.getConstructor(String.class, String.class, String.class, String.class,
                android.net.Uri.class, String.class, String.class, String.class,
                Class.forName("com.google.android.gms.fido.fido2.api.common.PublicKeyCredential"));
        Method convert = Class.forName("org.microg.gms.auth.credentials.GoogleIdSignInKt")
                .getMethod("toGoogleIdCredentialData", credential);
        android.net.Uri picture = android.net.Uri.parse("https://example.invalid/picture");
        Object value = constructor.newInstance("synthetic-id", "Display", "Given", "Family", picture,
                null, "synthetic-token", "synthetic-phone", null);
        Bundle result = (Bundle) convert.invoke(null, value);
        equal("synthetic-id", result.getString(GOOGLE + "BUNDLE_KEY_ID"));
        equal("synthetic-token", result.getString(GOOGLE + "BUNDLE_KEY_ID_TOKEN"));
        equal(picture, result.getParcelable(GOOGLE + "BUNDLE_KEY_PROFILE_PICTURE_URI"));
        equal("synthetic-phone", result.getString(GOOGLE + "BUNDLE_KEY_PHONE_NUMBER"));
        for (String token : new String[]{null, "", " "}) {
            value = constructor.newInstance("synthetic-id", null, null, null, null, null, token, null, null);
            try {
                convert.invoke(null, value);
                throw new AssertionError("Incomplete credential accepted");
            } catch (java.lang.reflect.InvocationTargetException error) {
                equal(true, error.getCause() instanceof IllegalArgumentException);
            }
        }
    }

    private static void signInFailures() throws Exception {
        Method mapping = Class.forName("org.microg.gms.auth.signin.SignInErrorsKt")
                .getMethod("signInFailureStatus", Throwable.class);
        Throwable consent = (Throwable) Class.forName("org.microg.gms.auth.signin.ConsentCanceledException")
                .getConstructor().newInstance();
        equal(16, read(mapping.invoke(null, consent), "getStatusCode"));
        equal(7, read(mapping.invoke(null, new java.io.IOException()), "getStatusCode"));
        equal(10, read(mapping.invoke(null, new IllegalArgumentException()), "getStatusCode"));
        equal(8, read(mapping.invoke(null, new IllegalStateException()), "getStatusCode"));
        var cancellation = new java.util.concurrent.CancellationException("Synthetic lifecycle cancellation");
        try {
            mapping.invoke(null, cancellation);
            throw new AssertionError("Lifecycle cancellation became an activity result");
        } catch (java.lang.reflect.InvocationTargetException error) {
            equal(cancellation, error.getCause());
        }
    }

    private static void accountSelection() throws Exception {
        var constructor = Class.forName("org.microg.gms.auth.signin.SignInAccountChoices")
                .getConstructor(Map.class, boolean.class);
        Map<String, Boolean> statuses = new LinkedHashMap<>();
        statuses.put("first", false);
        statuses.put("unavailable", null);
        statuses.put("second", true);
        Object priorGrants = constructor.newInstance(statuses, true);
        equal(true, read(priorGrants, "getHasFailedChecks"));
        equal(false, read(constructor.newInstance(Map.of("first", false), true), "getHasFailedChecks"));
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
