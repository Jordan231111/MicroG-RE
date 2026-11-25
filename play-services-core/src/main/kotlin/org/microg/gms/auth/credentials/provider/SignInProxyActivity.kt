/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.auth.credentials.provider

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.provider.ProviderCreateCredentialRequest
import androidx.credentials.provider.ProviderGetCredentialRequest
import com.google.android.gms.auth.api.identity.GetSignInIntentRequest
import com.google.android.gms.auth.api.identity.BeginSignInRequest
import com.google.android.gms.auth.api.identity.SignInCredential
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.internal.safeparcel.SafeParcelableSerializer
import org.microg.gms.auth.AuthConstants
import org.microg.gms.auth.credentials.buildAssistedSignInIntent
import org.microg.gms.auth.signin.GET_SIGN_IN_INTENT_REQUEST
import org.microg.gms.auth.signin.BEGIN_SIGN_IN_REQUEST
import org.microg.gms.auth.credentials.provider.GoogleIdRequestParams.Companion.toGoogleIdRequestParams

private const val TAG = "SignInProxyActivity"
private const val REQUEST_CODE_SIGN_IN = 100

@RequiresApi(34)
class SignInProxyActivity : CredentialProviderActivity() {

    private var credentialType = TYPE_GOOGLE_ID_TOKEN_CREDENTIAL

    override fun onProviderGetCredentialRequest(request: ProviderGetCredentialRequest) {
        val option = request.credentialOptions.singleOrNull {
            it.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL || it.type == TYPE_MICROG_GOOGLE_ID_TOKEN_CREDENTIAL
        } ?: return finishWithException("Expected one selected Google ID credential option")
        credentialType = option.type
        // The final platform request supplies the verified caller and full request data.
        // Candidate-query data may omit the caller and is insufficient for authentication.
        val params = option.requestData.toGoogleIdRequestParams()
        val serverClientId = params.serverClientId?.takeIf { it.isNotBlank() }
            ?: return finishWithException("Missing Google ID server client ID")
        val signInRequest = if (params.isSignInWithGoogle) {
            GetSignInIntentRequest.builder()
                .setServerClientId(serverClientId)
                .filterByHostedDomain(params.hostedDomainFilter)
                .setNonce(params.nonce)
                .build()
        } else {
            BeginSignInRequest.Builder()
                .setGoogleIdTokenRequestOptions(
                    BeginSignInRequest.GoogleIdTokenRequestOptions.builder()
                        .setSupported(true)
                        .setServerClientId(serverClientId)
                        .setFilterByAuthorizedAccounts(params.filterByAuthorized)
                        .apply { params.nonce?.let { setNonce(it) } }
                        .build()
                )
                .setAutoSelectEnabled(params.isAutoSelectAllowed)
                .build()
        }
        val googleSignInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestIdToken(serverClientId)
            .apply { intent.getStringExtra(GOOGLE_ID_SIWG_ACCOUNT_NAME)?.let { setAccountName(it) } }
            .build()
        val signInIntent = buildAssistedSignInIntent(
            requestExtraKey = if (params.isSignInWithGoogle) GET_SIGN_IN_INTENT_REQUEST else BEGIN_SIGN_IN_REQUEST,
            serializedRequest = SafeParcelableSerializer.serializeToBytes(signInRequest),
            googleSignInOptions = googleSignInOptions,
            callingPackage = request.callingAppInfo.packageName
        )
        startActivityForResult(signInIntent, REQUEST_CODE_SIGN_IN)
    }

    override fun onProviderCreateCredentialRequest(request: ProviderCreateCredentialRequest) {
        finishWithException("Unsupported create credential request")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE_SIGN_IN) return
        if (resultCode != RESULT_OK) {
            val status = try {
                data?.getByteArrayExtra(AuthConstants.STATUS)?.let {
                    SafeParcelableSerializer.deserializeFromBytes(it, Status.CREATOR).statusCode
                }
            } catch (e: RuntimeException) {
                return finishWithException("Invalid sign-in result")
            }
            when {
                status == CommonStatusCodes.SIGN_IN_REQUIRED ->
                    finishWithException("No eligible Google account", getExceptionCreator = ::NoCredentialException)
                resultCode == RESULT_CANCELED && (status == null || status == CommonStatusCodes.CANCELED) ->
                    finishWithException("Sign in canceled", getExceptionCreator = ::GetCredentialCancellationException)
                else -> finishWithException("Sign-in activity failed")
            }
            return
        }
        if (data == null) return finishWithException("Missing Google ID credential")
        Log.d(TAG, "onActivityResult - requestCode: $requestCode, resultCode: $resultCode")
        handleSignInSuccess(data)
    }

    private fun handleSignInSuccess(data: Intent) = runCatching {
        Log.d(TAG, "handleSignInSuccess")

        val credentialBytes = data.getByteArrayExtra(AuthConstants.SIGN_IN_CREDENTIAL)
            ?: run {
                Log.e(TAG, "No credential data in result")
                return@runCatching finishWithException("No credential data")
            }

        val signInCredential = SafeParcelableSerializer.deserializeFromBytes(
            credentialBytes,
            SignInCredential.CREATOR
        )

        val idToken = signInCredential.googleIdToken
        require(signInCredential.id.isNotBlank() && !idToken.isNullOrBlank()) { "Incomplete Google ID credential" }
        val credentialData = Bundle().apply {
            putString(GOOGLE_ID_BUNDLE_KEY_ID, signInCredential.id)
            putString(GOOGLE_ID_BUNDLE_KEY_ID_TOKEN, idToken)
            putString(GOOGLE_ID_BUNDLE_KEY_DISPLAY_NAME, signInCredential.displayName)
            putString(GOOGLE_ID_BUNDLE_KEY_GIVEN_NAME, signInCredential.givenName)
            putString(GOOGLE_ID_BUNDLE_KEY_FAMILY_NAME, signInCredential.familyName)
            putString(GOOGLE_ID_BUNDLE_KEY_PHONE_NUMBER, signInCredential.phoneNumber)
            putParcelable(GOOGLE_ID_BUNDLE_KEY_PROFILE_PICTURE_URI, signInCredential.profilePictureUri)
        }

        finishWithSuccess(GetCredentialResponse(CustomCredential(credentialType, credentialData)))
    }.onFailure { e ->
        Log.e(TAG, "Error processing sign-in result", e)
        finishWithException(e.message)
    }
}
