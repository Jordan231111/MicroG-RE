/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.auth.credentials.provider

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import org.microg.gms.auth.credentials.createSignInIntent
import org.microg.gms.auth.credentials.googleIdResultException
import org.microg.gms.auth.credentials.unsupportedGoogleIdOptions
import org.microg.gms.auth.credentials.toGoogleIdCredentialData
import androidx.annotation.RequiresApi
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialResponse
import androidx.credentials.provider.ProviderCreateCredentialRequest
import androidx.credentials.provider.ProviderGetCredentialRequest
import com.google.android.gms.auth.api.identity.SignInCredential
import com.google.android.gms.common.internal.safeparcel.SafeParcelableSerializer
import org.microg.gms.auth.AuthConstants
import org.microg.gms.auth.credentials.provider.GoogleIdRequestParams.Companion.toGoogleIdRequestParams

private const val TAG = "SignInProxyActivity"
private const val REQUEST_CODE_SIGN_IN = 100

@RequiresApi(34)
class SignInProxyActivity : CredentialProviderActivity() {

    private var credentialType = TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
    private var signInLaunched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        credentialType = savedInstanceState?.getString("google-id-credential-type") ?: TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        signInLaunched = savedInstanceState?.getBoolean("google-id-sign-in-launched") == true
        super.onCreate(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("google-id-credential-type", credentialType)
        outState.putBoolean("google-id-sign-in-launched", signInLaunched)
        super.onSaveInstanceState(outState)
    }

    override fun onProviderGetCredentialRequest(request: ProviderGetCredentialRequest) {
        val option = request.credentialOptions.singleOrNull {
            it.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL || it.type == TYPE_MICROG_GOOGLE_ID_TOKEN_CREDENTIAL
        } ?: return finishWithException("Expected one selected Google ID credential option")
        credentialType = option.type
        // Android restores the child result after recreation. Starting consent again would
        // create a second request and could deliver a response to the wrong attempt.
        if (signInLaunched) return
        // No delegated web-origin flow is implemented. Never authenticate the browser
        // package as though it were the requested relying party.
        if (runCatching { request.callingAppInfo.getOrigin("{\"apps\":[]}") }.isFailure) {
            return finishWithException("Delegated credential origins are not supported",
                getExceptionCreator = ::GetCredentialUnsupportedException)
        }
        // The final platform request supplies the verified caller and full request data.
        // Candidate-query data may omit the caller and is insufficient for authentication.
        val params = option.requestData.toGoogleIdRequestParams()
        unsupportedGoogleIdOptions(params.hostedDomainFilter, params.linkedServiceId,
            params.idTokenDepositionScopes, params.requestVerifiedPhoneNumber)?.let {
            return finishWithException(it, getExceptionCreator = ::GetCredentialUnsupportedException)
        }
        val signInIntent = params.createSignInIntent(packageName, request.callingAppInfo.packageName,
            intent.getStringExtra(GOOGLE_ID_SIWG_ACCOUNT_NAME))
        signInLaunched = true
        startActivityForResult(signInIntent, REQUEST_CODE_SIGN_IN)
    }

    override fun onProviderCreateCredentialRequest(request: ProviderCreateCredentialRequest) {
        finishWithException("Unsupported create credential request")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE_SIGN_IN) return
        googleIdResultException(resultCode, data)?.let { error ->
            return finishWithException(error.message?.toString(), getExceptionCreator = { error })
        }
        Log.d(TAG, "onActivityResult - requestCode: $requestCode, resultCode: $resultCode")
        handleSignInSuccess(requireNotNull(data))
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

        val credentialData = signInCredential.toGoogleIdCredentialData()
        finishWithSuccess(GetCredentialResponse(CustomCredential(credentialType, credentialData)))
    }.onFailure { e ->
        Log.e(TAG, "Error processing sign-in result", e)
        finishWithException(e.message)
    }
}
