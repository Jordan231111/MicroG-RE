/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.auth.credentials

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.auth.api.identity.BeginSignInRequest
import com.google.android.gms.auth.api.identity.GetSignInIntentRequest
import com.google.android.gms.auth.api.identity.SignInCredential
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.internal.safeparcel.SafeParcelableSerializer
import org.microg.gms.auth.AuthConstants
import org.microg.gms.auth.credentials.provider.GoogleIdRequestParams
import org.microg.gms.auth.credentials.provider.GOOGLE_ID_BUNDLE_KEY_ID
import org.microg.gms.auth.credentials.provider.GOOGLE_ID_BUNDLE_KEY_ID_TOKEN
import org.microg.gms.auth.credentials.provider.GOOGLE_ID_BUNDLE_KEY_DISPLAY_NAME
import org.microg.gms.auth.credentials.provider.GOOGLE_ID_BUNDLE_KEY_GIVEN_NAME
import org.microg.gms.auth.credentials.provider.GOOGLE_ID_BUNDLE_KEY_FAMILY_NAME
import org.microg.gms.auth.credentials.provider.GOOGLE_ID_BUNDLE_KEY_PHONE_NUMBER
import org.microg.gms.auth.credentials.provider.GOOGLE_ID_BUNDLE_KEY_PROFILE_PICTURE_URI
import org.microg.gms.auth.signin.BEGIN_SIGN_IN_REQUEST
import org.microg.gms.auth.signin.GET_SIGN_IN_INTENT_REQUEST
import org.microg.gms.auth.signin.ACTION_ASSISTED_SIGN_IN
import org.microg.gms.auth.signin.CLIENT_PACKAGE_NAME
import org.microg.gms.auth.signin.GOOGLE_SIGN_IN_OPTIONS

/** These options require provider/backend work which the current sign-in engine does not perform. */
fun unsupportedGoogleIdOptions(hostedDomain: String?, linkedService: String?,
                              depositionScopes: List<String>?, verifiedPhone: Boolean): String? = when {
    !hostedDomain.isNullOrEmpty() -> "Hosted-domain filtering is not supported"
    linkedService != null || !depositionScopes.isNullOrEmpty() -> "Linked-account token deposition is not supported"
    verifiedPhone -> "Verified phone numbers are not supported"
    else -> null
}

/** Both Credential Manager transports must preserve the same Google ID request semantics. */
fun GoogleIdRequestParams.createSignInIntent(providerPackage: String, callingPackage: String, accountName: String? = null): Intent {
    require(!serverClientId.isNullOrBlank()) { "Missing Google ID server client ID" }
    require(unsupportedGoogleIdOptions(hostedDomainFilter, linkedServiceId,
        idTokenDepositionScopes, requestVerifiedPhoneNumber) == null) { "Unsupported Google ID options" }
    val request = if (isSignInWithGoogle) {
        GetSignInIntentRequest.builder().setServerClientId(serverClientId).setNonce(nonce).build()
    } else {
        BeginSignInRequest.Builder().setGoogleIdTokenRequestOptions(
            BeginSignInRequest.GoogleIdTokenRequestOptions.builder()
                .setSupported(true).setServerClientId(serverClientId)
                .setFilterByAuthorizedAccounts(filterByAuthorized)
                .apply { nonce?.let { setNonce(it) } }.build()
        ).setAutoSelectEnabled(isAutoSelectAllowed).build()
    }
    val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail().requestIdToken(serverClientId)
        .apply { accountName?.let { setAccountName(it) } }.build()
    return Intent(ACTION_ASSISTED_SIGN_IN).setPackage(providerPackage)
        .putExtra(if (isSignInWithGoogle) GET_SIGN_IN_INTENT_REQUEST else BEGIN_SIGN_IN_REQUEST,
            SafeParcelableSerializer.serializeToBytes(request))
        .putExtra(GOOGLE_SIGN_IN_OPTIONS, SafeParcelableSerializer.serializeToBytes(options))
        .putExtra(CLIENT_PACKAGE_NAME, callingPackage)
}

fun googleIdResultException(resultCode: Int, data: Intent?): GetCredentialException? {
    val status = try {
        data?.getByteArrayExtra(AuthConstants.STATUS)?.let {
            SafeParcelableSerializer.deserializeFromBytes(it, Status.CREATOR).statusCode
        }
    } catch (_: RuntimeException) {
        return GetCredentialUnknownException("Invalid sign-in result")
    }
    return when {
        status == CommonStatusCodes.SIGN_IN_REQUIRED -> NoCredentialException("No eligible Google account")
        status == CommonStatusCodes.CANCELED ||
            (resultCode == Activity.RESULT_CANCELED && status == null) ->
            GetCredentialCancellationException("Sign in canceled")
        status != null && status != CommonStatusCodes.SUCCESS -> GetCredentialUnknownException("Sign-in activity failed")
        resultCode != Activity.RESULT_OK || data == null -> GetCredentialUnknownException("Missing Google ID credential")
        else -> null
    }
}

/** Google ID's Bundle contract uses a Parcelable Uri, including on the legacy transport. */
fun SignInCredential.toGoogleIdCredentialData(): Bundle {
    require(id.isNotBlank() && !googleIdToken.isNullOrBlank()) { "Incomplete Google ID credential" }
    return Bundle().apply {
        putString(GOOGLE_ID_BUNDLE_KEY_ID, id)
        putString(GOOGLE_ID_BUNDLE_KEY_ID_TOKEN, googleIdToken)
        displayName?.let { putString(GOOGLE_ID_BUNDLE_KEY_DISPLAY_NAME, it) }
        givenName?.let { putString(GOOGLE_ID_BUNDLE_KEY_GIVEN_NAME, it) }
        familyName?.let { putString(GOOGLE_ID_BUNDLE_KEY_FAMILY_NAME, it) }
        phoneNumber?.let { putString(GOOGLE_ID_BUNDLE_KEY_PHONE_NUMBER, it) }
        profilePictureUri?.let { putParcelable(GOOGLE_ID_BUNDLE_KEY_PROFILE_PICTURE_URI, it) }
    }
}
