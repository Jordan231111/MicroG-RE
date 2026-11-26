/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.auth.credentials.provider

import android.accounts.AccountManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginGetCredentialOption
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import androidx.credentials.provider.CredentialEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.CustomCredentialEntry
import com.google.android.gms.R
import org.microg.gms.auth.AuthConstants
import org.microg.gms.base.core.BuildConfig
import java.util.UUID
import org.microg.gms.auth.credentials.provider.GoogleIdRequestParams.Companion.toGoogleIdRequestParams

private const val TAG = "GoogleIdService"

/**
 * Google ID Credential Provider Service
 * Handles Google Sign-In and Google ID Token credentials
 * Note: This service only handles GET operations (sign-in), not CREATE operations
 */
@RequiresApi(34)
open class GoogleIdService : CredentialProviderService() {

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>
    ) {
        if (cancellationSignal.isCanceled) return
        val response = try {
            val options = request.beginGetCredentialOptions.filter {
                it.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL || it.type == TYPE_MICROG_GOOGLE_ID_TOKEN_CREDENTIAL
            }
            val entries = if (options.isEmpty()) emptyList() else {
                val accounts = AccountManager.get(this).getAccountsByType(AuthConstants.DEFAULT_ACCOUNT_TYPE).map { it.name }
                options.flatMap { option ->
                    option.candidateQueryData.toGoogleIdRequestParams().candidateAccounts(accounts)
                        .map { createCredentialEntry(option, it) }
                }
            }
            BeginGetCredentialResponse.Builder().setCredentialEntries(entries).build()
        } catch (e: Exception) {
            Log.w(TAG, "Credential lookup failed: ${e.javaClass.simpleName}")
            if (!cancellationSignal.isCanceled) callback.onError(GetCredentialUnknownException("Credential lookup failed"))
            return
        }
        if (!cancellationSignal.isCanceled) callback.onResult(response)
    }

    private fun createCredentialEntry(
        option: BeginGetCredentialOption,
        accountName: String?
    ): CredentialEntry {
        // PendingIntent identity ignores extras. Give each chooser entry its own identity
        // so another request cannot update the account associated with a displayed row.
        val intent = Intent(this, SignInProxyActivity::class.java).apply {
            identifier = UUID.randomUUID().toString()
            accountName?.let { putExtra(GOOGLE_ID_SIWG_ACCOUNT_NAME, it) }
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_MUTABLE
        )

        return CustomCredentialEntry(
            this,
            accountName ?: getString(R.string.credentials_service_sign_in_with_google_label),
            pendingIntent,
            option,
            null,
            null,
            null,
            Icon.createWithResource(this, R.drawable.ic_google_logo)
        )
    }

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>
    ) {
        // Google ID Service only handles GET (sign-in), not CREATE
        callback.onResult(BeginCreateCredentialResponse.Builder().build())
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>
    ) {
        callback.onResult(null)
    }
}

const val GOOGLE_ID_SUBTYPE = "com.google.android.libraries.identity.googleid.BUNDLE_KEY_GOOGLE_ID_TOKEN_SUBTYPE"
const val GOOGLE_ID_TYPE_SIWG = "com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL"
const val GOOGLE_ID_SERVER_CLIENT_ID = "com.google.android.libraries.identity.googleid.BUNDLE_KEY_SERVER_CLIENT_ID"
const val GOOGLE_ID_NONCE = "com.google.android.libraries.identity.googleid.BUNDLE_KEY_NONCE"
const val GOOGLE_ID_LINKED_SERVICE_ID = "com.google.android.libraries.identity.googleid.BUNDLE_KEY_LINKED_SERVICE_ID"
const val GOOGLE_ID_DEPOSITION_SCOPES = "com.google.android.libraries.identity.googleid.BUNDLE_KEY_ID_TOKEN_DEPOSITION_SCOPES"
const val GOOGLE_ID_AUTO_SELECT = "com.google.android.libraries.identity.googleid.BUNDLE_KEY_AUTO_SELECT_ENABLED"
const val GOOGLE_ID_SIWG_AUTO_SELECT = "com.google.android.libraries.identity.googleid.siwg.BUNDLE_KEY_AUTO_SELECT_ENABLED"
const val GOOGLE_ID_REQUEST_VERIFIED_PHONE = "com.google.android.libraries.identity.googleid.BUNDLE_KEY_REQUEST_VERIFIED_PHONE_NUMBER"
const val GOOGLE_ID_SIWG_ACCOUNT_NAME = "com.google.android.libraries.identity.googleid.siwg.BUNDLE_KEY_ACCOUNT_NAME"
const val GOOGLE_ID_SIWG_SERVER_CLIENT_ID = "com.google.android.libraries.identity.googleid.siwg.BUNDLE_KEY_SERVER_CLIENT_ID"
const val GOOGLE_ID_SIWG_NONCE = "com.google.android.libraries.identity.googleid.siwg.BUNDLE_KEY_NONCE"
const val GOOGLE_ID_SIWG_HOSTED_DOMAIN = "com.google.android.libraries.identity.googleid.siwg.BUNDLE_KEY_HOSTED_DOMAIN_FILTER"
const val GOOGLE_ID_ANDROIDX_AUTO_SELECT = "androidx.credentials.BUNDLE_KEY_IS_AUTO_SELECT_ALLOWED"
const val GOOGLE_ID_BUNDLE_KEY_ID = "com.google.android.libraries.identity.googleid.BUNDLE_KEY_ID"
const val GOOGLE_ID_BUNDLE_KEY_ID_TOKEN = "com.google.android.libraries.identity.googleid.BUNDLE_KEY_ID_TOKEN"
const val GOOGLE_ID_BUNDLE_KEY_DISPLAY_NAME = "com.google.android.libraries.identity.googleid.BUNDLE_KEY_DISPLAY_NAME"
const val GOOGLE_ID_BUNDLE_KEY_GIVEN_NAME = "com.google.android.libraries.identity.googleid.BUNDLE_KEY_GIVEN_NAME"
const val GOOGLE_ID_BUNDLE_KEY_FAMILY_NAME = "com.google.android.libraries.identity.googleid.BUNDLE_KEY_FAMILY_NAME"
const val GOOGLE_ID_BUNDLE_KEY_PHONE_NUMBER = "com.google.android.libraries.identity.googleid.BUNDLE_KEY_PHONE_NUMBER"
const val GOOGLE_ID_BUNDLE_KEY_PROFILE_PICTURE_URI = "com.google.android.libraries.identity.googleid.BUNDLE_KEY_PROFILE_PICTURE_URI"
const val GOOGLE_ID_FILTER_BY_AUTHORIZED_ACCOUNTS = "com.google.android.libraries.identity.googleid.BUNDLE_KEY_FILTER_BY_AUTHORIZED_ACCOUNTS"

// Credential types
const val TYPE_GOOGLE_ID_TOKEN_CREDENTIAL = "com.google.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL"
const val TYPE_MICROG_GOOGLE_ID_TOKEN_CREDENTIAL = "${BuildConfig.BASE_PACKAGE_NAME}.android.libraries.identity.googleid.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL"

data class GoogleIdRequestParams(
    val isSignInWithGoogle: Boolean,
    val serverClientId: String?,
    val nonce: String?,
    val hostedDomainFilter: String?,
    val isAutoSelectAllowed: Boolean = false,
    val filterByAuthorized: Boolean = false,
    val linkedServiceId: String?,
    val requestVerifiedPhoneNumber: Boolean = false,
    val idTokenDepositionScopes: List<String>? = null
) {
    fun candidateAccounts(accounts: List<String>): List<String?> {
        if (!isSignInWithGoogle && filterByAuthorized) {
            // Candidate requests can omit the caller and complete OAuth parameters.
            // Defer authorization filtering to the final authenticated request instead
            // of pinning a chooser row to an account whose grant is still unknown.
            return if (accounts.isEmpty()) emptyList() else listOf(null)
        }
        return if (accounts.isEmpty()) listOf(null) else accounts
    }

    companion object {
        fun Bundle.toGoogleIdRequestParams(): GoogleIdRequestParams {
            val isSignInWithGoogle = getString(GOOGLE_ID_SUBTYPE) == GOOGLE_ID_TYPE_SIWG
            return GoogleIdRequestParams(
                isSignInWithGoogle = isSignInWithGoogle,
                serverClientId = getString(if (isSignInWithGoogle) GOOGLE_ID_SIWG_SERVER_CLIENT_ID else GOOGLE_ID_SERVER_CLIENT_ID),
                nonce = getString(if (isSignInWithGoogle) GOOGLE_ID_SIWG_NONCE else GOOGLE_ID_NONCE),
                hostedDomainFilter = getString(GOOGLE_ID_SIWG_HOSTED_DOMAIN),
                isAutoSelectAllowed = getBoolean(if (isSignInWithGoogle) GOOGLE_ID_SIWG_AUTO_SELECT else GOOGLE_ID_AUTO_SELECT,
                    getBoolean(GOOGLE_ID_ANDROIDX_AUTO_SELECT, false)),
                filterByAuthorized = getBoolean(GOOGLE_ID_FILTER_BY_AUTHORIZED_ACCOUNTS, true),
                linkedServiceId = getString(GOOGLE_ID_LINKED_SERVICE_ID),
                requestVerifiedPhoneNumber = getBoolean(GOOGLE_ID_REQUEST_VERIFIED_PHONE, false),
                idTokenDepositionScopes = getStringArrayList(GOOGLE_ID_DEPOSITION_SCOPES)
            )
        }
    }
}
