/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.auth.credentials.identity

import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import org.microg.gms.auth.credentials.createSignInIntent
import org.microg.gms.auth.credentials.googleIdResultException
import org.microg.gms.auth.credentials.unsupportedGoogleIdOptions
import org.microg.gms.auth.credentials.toGoogleIdCredentialData
import org.microg.gms.auth.credentials.provider.GoogleIdRequestParams.Companion.toGoogleIdRequestParams
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.BundleCompat
import androidx.credentials.PasswordCredential
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.auth.api.identity.SignInCredential
import com.google.android.gms.common.internal.safeparcel.SafeParcelableSerializer
import com.google.android.gms.fido.Fido.FIDO2_KEY_CREDENTIAL_EXTRA
import com.google.android.gms.fido.fido2.api.common.AuthenticatorErrorResponse
import com.google.android.gms.identitycredentials.CreateCredentialRequest
import com.google.android.gms.identitycredentials.CredentialOption
import com.google.android.gms.identitycredentials.GetCredentialRequest
import org.json.JSONObject
import org.microg.gms.auth.AuthConstants
import org.microg.gms.auth.credentials.buildFidoAuthenticateIntent
import org.microg.gms.identitycredentials.EXTRA_CALLING_PACKAGE
import org.microg.gms.identitycredentials.EXTRA_CREATE_REQUEST
import org.microg.gms.identitycredentials.EXTRA_GET_REQUEST
import org.microg.gms.auth.credentials.provider.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import org.microg.gms.auth.credentials.provider.TYPE_MICROG_GOOGLE_ID_TOKEN_CREDENTIAL
import org.microg.gms.auth.credentials.provider.parsePublicKeyCredentialCreationOptions
import org.microg.gms.auth.credentials.provider.parsePublicKeyCredentialRequestOptions
import org.microg.gms.auth.credentials.provider.toJson
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.SOURCE_APP
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.TYPE_REGISTER
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.TYPE_SIGN

private const val TAG = "IdentityCredChooser"

class IdentityCredentialChooserActivity : AppCompatActivity() {

    private var callingPackage: String = ""
    private var isCreatePath = false
    private var googleCredentialType = TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
    private var childLaunched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        callingPackage = intent.getStringExtra(EXTRA_CALLING_PACKAGE).orEmpty()
        childLaunched = savedInstanceState?.getBoolean("credential-child-launched") == true
        isCreatePath = savedInstanceState?.getBoolean("credential-create-path") == true
        googleCredentialType = savedInstanceState?.getString("google-credential-type") ?: TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        // The restored child delivers its result to this instance; do not launch a second consent flow.
        if (childLaunched) return
        runCatching {
            val getReq: GetCredentialRequest? = readNestedParcelable(EXTRA_GET_REQUEST, GetCredentialRequest::class.java)
            val createReq: CreateCredentialRequest? = readNestedParcelable(EXTRA_CREATE_REQUEST, CreateCredentialRequest::class.java)
            when {
                getReq != null -> {
                    isCreatePath = false
                    Log.d(TAG, "onCreate get pkg=$callingPackage options=${getReq.credentialOptions.size}")
                    routeGet(getReq)
                }
                createReq != null -> {
                    isCreatePath = true
                    Log.d(TAG, "onCreate create pkg=$callingPackage type=${createReq.type} origin=${createReq.origin}")
                    routeCreate(createReq)
                }
                else -> finishWithGetException(GetCredentialUnknownException("Missing request payload"))
            }
        }.onFailure { e ->
            Log.e(TAG, "onCreate parse failed", e)
            if (isCreatePath) finishWithCreateException(CreateCredentialUnknownException(e.message ?: "Internal error"))
            else finishWithGetException(GetCredentialUnknownException(e.message ?: "Internal error"))
        }
    }

    private fun routeGet(req: GetCredentialRequest) {
        if (req.origin != null) return finishWithGetException(
            GetCredentialUnsupportedException("Delegated credential origins are not supported"))
        val option = req.credentialOptions.singleOrNull()
            ?: return finishWithGetException(GetCredentialUnsupportedException("Expected one credential option"))
        when (option.type) {
            PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL -> startPasskeyGet(option)
            TYPE_GOOGLE_ID_TOKEN_CREDENTIAL, TYPE_MICROG_GOOGLE_ID_TOKEN_CREDENTIAL -> {
                googleCredentialType = option.type
                startGoogleSignIn(option)
            }
            PasswordCredential.TYPE_PASSWORD_CREDENTIAL ->
                finishWithGetException(NoCredentialException("Password credentials are not stored"))
            else -> finishWithGetException(NoCredentialException("Unsupported type: ${option.type}"))
        }
    }

    private fun routeCreate(req: CreateCredentialRequest) {
        Log.d(TAG, "routeCreate type=${req.type} pkg=$callingPackage")
        when (req.type) {
            PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL -> startPasskeyCreate(req)
            else -> finishWithCreateException(CreateCredentialNoCreateOptionException("Unsupported create type: ${req.type}"))
        }
    }

    private fun startPasskeyGet(option: CredentialOption) {
        val data = option.credentialRetrievalData
        val requestJson = data?.getString(PUBKEY_REQ_JSON_KEY)
            ?: return finishWithGetException(NoCredentialException("Passkey request missing requestJson"))
        val pkOptions = runCatching {
            JSONObject(requestJson).parsePublicKeyCredentialRequestOptions()
        }.getOrElse {
            Log.e(TAG, "Passkey JSON parse failed", it)
            return finishWithGetException(GetCredentialUnknownException("Invalid passkey requestJson"))
        }
        val fidoIntent = buildFidoAuthenticateIntent(SOURCE_APP, pkOptions.serializeToBytes(), callingPackage, TYPE_SIGN)
        launchChild(fidoIntent, REQ_CODE_FIDO)
    }

    private fun startPasskeyCreate(req: CreateCredentialRequest) {
        val requestJson = req.requestJson ?: req.credentialData.getString(PUBKEY_REQ_JSON_KEY)
        if (requestJson.isNullOrBlank()) {
            return finishWithCreateException(CreateCredentialUnknownException("Passkey create missing requestJson"))
        }
        val pkOptions = runCatching {
            JSONObject(requestJson).parsePublicKeyCredentialCreationOptions()
        }.getOrElse {
            Log.e(TAG, "Passkey create JSON parse failed", it)
            return finishWithCreateException(CreateCredentialUnknownException("Invalid passkey requestJson"))
        }
        val fidoIntent = buildFidoAuthenticateIntent(SOURCE_APP, pkOptions.serializeToBytes(), callingPackage, TYPE_REGISTER)
        launchChild(fidoIntent, REQ_CODE_FIDO)
    }

    private fun startGoogleSignIn(option: CredentialOption) {
        val params = (option.credentialRetrievalData ?: Bundle()).toGoogleIdRequestParams()
        unsupportedGoogleIdOptions(params.hostedDomainFilter, params.linkedServiceId,
            params.idTokenDepositionScopes, params.requestVerifiedPhoneNumber)?.let {
            return finishWithGetException(GetCredentialUnsupportedException(it))
        }
        launchChild(params.createSignInIntent(packageName, callingPackage), REQ_CODE_SIGN_IN)
    }

    private fun launchChild(child: Intent, requestCode: Int) {
        startActivityForResult(child, requestCode)
        childLaunched = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("credential-child-launched", childLaunched)
        outState.putBoolean("credential-create-path", isCreatePath)
        outState.putString("google-credential-type", googleCredentialType)
        super.onSaveInstanceState(outState)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d(TAG, "onActivityResult: requestCode: $requestCode resultCode: $resultCode")
        when (requestCode) {
            REQ_CODE_FIDO -> handleFidoResult(resultCode, data)
            REQ_CODE_SIGN_IN -> handleSignInResult(resultCode, data)
            else -> finishWithGetException(GetCredentialUnknownException("Unexpected requestCode=$requestCode"))
        }
    }

    private fun handleFidoResult(resultCode: Int, data: Intent?) {
        if (resultCode != RESULT_OK || data == null) {
            return if (isCreatePath) finishWithCreateException(CreateCredentialUnknownException("Passkey flow canceled"))
            else finishWithGetException(GetCredentialCancellationException("Passkey flow canceled"))
        }
        runCatching {
            val credentialBytes = data.getByteArrayExtra(FIDO2_KEY_CREDENTIAL_EXTRA)
                ?: throw IllegalStateException("FIDO returned no credential")
            val publicKeyCredential = com.google.android.gms.fido.fido2.api.common.PublicKeyCredential
                .deserializeFromBytes(credentialBytes)
            (publicKeyCredential.response as? AuthenticatorErrorResponse)?.let { err ->
                throw IllegalStateException(err.errorMessage ?: err.errorCode.toString())
            }
            val json = publicKeyCredential.toJson()
            val credData = Bundle().apply {
                putString(if (isCreatePath) PUBKEY_RES_REG_JSON_KEY else PUBKEY_RES_AUTH_JSON_KEY, json)
            }
            finishWithCredential(PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL, credData)
        }.onFailure { e ->
            Log.e(TAG, "handleFidoResult failed", e)
            val msg = e.message ?: "FIDO result error"
            if (isCreatePath) finishWithCreateException(CreateCredentialUnknownException(msg))
            else finishWithGetException(GetCredentialUnknownException(msg))
        }
    }

    private fun handleSignInResult(resultCode: Int, data: Intent?) {
        googleIdResultException(resultCode, data)?.let { return finishWithGetException(it) }
        runCatching {
            val bytes = requireNotNull(data).getByteArrayExtra(AuthConstants.SIGN_IN_CREDENTIAL)
                ?: throw IllegalStateException("Sign-in result missing credential")
            val credential = SafeParcelableSerializer.deserializeFromBytes(bytes, SignInCredential.CREATOR)
            finishWithCredential(googleCredentialType, credential.toGoogleIdCredentialData())
        }.onFailure { e ->
            Log.e(TAG, "handleSignInResult failed", e)
            finishWithGetException(GetCredentialUnknownException(e.message ?: "Sign-in result error"))
        }
    }

    private fun finishWithCredential(type: String, credentialData: Bundle) {
        val responseBundle = Bundle().apply {
            if (isCreatePath) {
                putString(PROVIDER_EXTRA_CREATE_RESPONSE_TYPE, type)
                putBundle(PROVIDER_EXTRA_CREATE_REQUEST_DATA, credentialData)
            } else {
                putString(PROVIDER_EXTRA_CREDENTIAL_TYPE, type)
                putBundle(PROVIDER_EXTRA_CREDENTIAL_DATA, credentialData)
            }
        }
        val extraKey = if (isCreatePath) EXTRA_CREATE_RESPONSE_BUNDLE else EXTRA_GET_RESPONSE_BUNDLE
        setResult(RESULT_OK, Intent().putExtra(extraKey, responseBundle))
        finish()
    }

    private fun finishWithGetException(e: androidx.credentials.exceptions.GetCredentialException) {
        finishWithExceptionBundle(EXTRA_GET_EXCEPTION_BUNDLE, e.type, e.message ?: e.javaClass.simpleName)
    }

    private fun finishWithCreateException(e: androidx.credentials.exceptions.CreateCredentialException) {
        finishWithExceptionBundle(EXTRA_CREATE_EXCEPTION_BUNDLE, e.type, e.message ?: e.javaClass.simpleName)
    }

    private fun finishWithExceptionBundle(extraKey: String, type: String, message: String) {
        val bundle = Bundle().apply {
            putString(PROVIDER_EXTRA_EXCEPTION_TYPE, type)
            putString(PROVIDER_EXTRA_EXCEPTION_MESSAGE, message)
        }
        setResult(RESULT_OK, Intent().putExtra(extraKey, bundle))
        finish()
    }

    private fun <T : Parcelable> readNestedParcelable(name: String, clazz: Class<T>): T? =
        intent.getBundleExtra(name)?.let { bundle ->
            bundle.classLoader = clazz.classLoader
            BundleCompat.getParcelable(bundle, name, clazz)
        }

    companion object {
        private const val REQ_CODE_FIDO = 0x1001
        private const val REQ_CODE_SIGN_IN = 0x1002

        private const val EXTRA_GET_RESPONSE_BUNDLE = "android.service.credentials.extra.GET_CREDENTIAL_RESPONSE"
        private const val EXTRA_GET_EXCEPTION_BUNDLE = "android.service.credentials.extra.GET_CREDENTIAL_EXCEPTION"
        private const val EXTRA_CREATE_RESPONSE_BUNDLE = "android.service.credentials.extra.CREATE_CREDENTIAL_RESPONSE"
        private const val EXTRA_CREATE_EXCEPTION_BUNDLE = "android.service.credentials.extra.CREATE_CREDENTIAL_EXCEPTION"
        private const val PROVIDER_EXTRA_CREDENTIAL_TYPE = "androidx.credentials.provider.extra.EXTRA_CREDENTIAL_TYPE"
        private const val PROVIDER_EXTRA_CREDENTIAL_DATA = "androidx.credentials.provider.extra.EXTRA_CREDENTIAL_DATA"
        private const val PROVIDER_EXTRA_CREATE_RESPONSE_TYPE = "androidx.credentials.provider.extra.CREATE_CREDENTIAL_RESPONSE_TYPE"
        private const val PROVIDER_EXTRA_CREATE_REQUEST_DATA = "androidx.credentials.provider.extra.CREATE_CREDENTIAL_REQUEST_DATA"
        private const val PROVIDER_EXTRA_EXCEPTION_TYPE = "androidx.credentials.provider.extra.CREATE_CREDENTIAL_EXCEPTION_TYPE"
        private const val PROVIDER_EXTRA_EXCEPTION_MESSAGE = "androidx.credentials.provider.extra.CREATE_CREDENTIAL_EXCEPTION_MESSAGE"

        private const val PUBKEY_REQ_JSON_KEY = "androidx.credentials.BUNDLE_KEY_REQUEST_JSON"
        private const val PUBKEY_RES_AUTH_JSON_KEY = "androidx.credentials.BUNDLE_KEY_AUTHENTICATION_RESPONSE_JSON"
        private const val PUBKEY_RES_REG_JSON_KEY = "androidx.credentials.BUNDLE_KEY_REGISTRATION_RESPONSE_JSON"
    }
}
