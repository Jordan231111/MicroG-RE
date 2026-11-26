/*
 * SPDX-FileCopyrightText: 2023 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.auth.signin

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.getSystemService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.R
import com.google.android.gms.auth.api.identity.BeginSignInRequest
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.microg.gms.auth.AuthConstants
import org.microg.gms.auth.login.LoginActivity
import org.microg.gms.common.AccountUtils
import org.microg.gms.people.PeopleManager
import org.microg.gms.utils.getApplicationLabel

/** A null status is an account whose authorization could not be checked. */
internal class SignInAccountChoices(statuses: Map<String, Boolean?>, authorizedOnly: Boolean) {
    val accounts = if (authorizedOnly) statuses.filterValues { it == true } else statuses
    val onlySelectable = accounts.entries.singleOrNull { it.value != null }
    val hasFailedChecks = statuses.values.any { it == null }
}

class AssistedSignInFragment : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "AssistedSignInFragment"
        private const val KEY_PACKAGE_NAME = "clientPackageName"
        private const val KEY_GOOGLE_SIGN_IN_OPTIONS = "googleSignInOptions"
        private const val KEY_BEGIN_SIGN_IN_REQUEST = "beginSignInRequest"

        fun newInstance(clientPackageName: String, options: GoogleSignInOptions, request: BeginSignInRequest): AssistedSignInFragment {
            val fragment = AssistedSignInFragment()
            val args = Bundle().apply {
                putString(KEY_PACKAGE_NAME, clientPackageName)
                putParcelable(KEY_GOOGLE_SIGN_IN_OPTIONS, options)
                putParcelable(KEY_BEGIN_SIGN_IN_REQUEST, request)
            }
            fragment.arguments = args
            return fragment
        }
    }

    private lateinit var clientPackageName: String
    private lateinit var options: GoogleSignInOptions
    private lateinit var beginSignInRequest: BeginSignInRequest
    private lateinit var accounts: Array<Account>
    private lateinit var accountManager: AccountManager

    private var cancelBtn: ImageView? = null
    private var container: FrameLayout? = null
    private var loginJob: Job? = null
    private var isSigningIn = false
    private var signInBack = false
    private var authStatusList: Map<String, Boolean?> = emptyMap()
    private var refreshJob: Job? = null

    private var lastChooseAccount: Account? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate start")
        clientPackageName = arguments?.getString(KEY_PACKAGE_NAME) ?: return errorResult()
        options = arguments?.getParcelable(KEY_GOOGLE_SIGN_IN_OPTIONS) ?: return errorResult()
        beginSignInRequest = arguments?.getParcelable(KEY_BEGIN_SIGN_IN_REQUEST) ?: return errorResult()
        accountManager = activity?.getSystemService<AccountManager>() ?: return errorResult()
    }

    fun initView() {
        if (loginJob?.isActive == true || signInBack) return
        refreshJob?.cancel()
        val requestedName = options.account?.name?.takeUnless { it == AuthConstants.DEFAULT_ACCOUNT }
        val authorizedOnly = beginSignInRequest.googleIdTokenRequestOptions.filterByAuthorizedAccounts()
        accounts = accountManager.getAccountsByType(AuthConstants.DEFAULT_ACCOUNT_TYPE)
        refreshJob = lifecycleScope.launch {
            try {
                if (accounts.isEmpty() && requestedName == null && !authorizedOnly) {
                    addGoogleAccount()
                } else {
                    filterAccountsLogin(requestedName, authorizedOnly)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorResult()
            }
        }
    }

    private fun addGoogleAccount() {
        notifyCancelBtn(true)
        container?.removeAllViews()
        val chooseView = LayoutInflater.from(requireContext()).inflate(R.layout.assisted_signin_hint_login, container, false)
        val addAccountBtn = chooseView.findViewById<TextView>(R.id.add_google_account_btn)
        val clientAppLabel = requireContext().packageManager.getApplicationLabel(clientPackageName)
        chooseView.findViewById<TextView>(R.id.add_account_subtitle_tv).text = String.format(
            getString(R.string.credentials_assisted_choose_account_subtitle), clientAppLabel
        )
        addAccountBtn.setOnClickListener {
            val intent = Intent(activity, LoginActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)}
            startActivity(intent)
        }
        container?.addView(chooseView)
    }

    private fun autoSingleSignIn(accountName: String, permitted: Boolean = false) {
        if (beginSignInRequest.isAutoSelectEnabled && permitted) {
            prepareSignInLoading(accountName) { cancelLogin(true) }
        } else {
            prepareChooseLogin(accountName, permitted)
        }
    }

    private suspend fun filterAccountsLogin(requestedName: String?, authorizedOnly: Boolean) {
        val statuses = linkedMapOf<String, Boolean?>()
        for (account in accounts) {
            if (requestedName != null && requestedName != account.name) continue
            statuses[account.name] = try {
                checkAccountAuthStatus(requireContext(), clientPackageName, options.scopes, account)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Account authorization check failed: ${e.javaClass.simpleName}")
                null
            }
        }
        val choices = SignInAccountChoices(statuses, authorizedOnly)
        authStatusList = choices.accounts
        val single = choices.onlySelectable
        when {
            single != null -> autoSingleSignIn(single.key, single.value == true)
            authStatusList.isEmpty() -> errorResult(if (choices.hasFailedChecks) Status.INTERNAL_ERROR
                else Status(CommonStatusCodes.SIGN_IN_REQUIRED))
            else -> prepareMultiSignIn(authStatusList)
        }
    }

    private fun prepareMultiSignIn(authorizedAccounts: Map<String, Boolean?>) {
        lifecycleScope.launch {
            notifyCancelBtn(true)
            container?.removeAllViews()
            val chooseView = LayoutInflater.from(requireContext()).inflate(R.layout.assisted_signin_choose_layout, container, false)
            val clientAppLabel = requireContext().packageManager.getApplicationLabel(clientPackageName)
            chooseView.findViewById<TextView>(R.id.sign_multi_description).text =
                String.format(getString(R.string.credentials_assisted_choose_account_subtitle), clientAppLabel)
            val accountViews = chooseView.findViewById<LinearLayout>(R.id.sign_multi_account_container)
            val progress = chooseView.findViewById<ProgressBar>(R.id.sign_multi_progress)
            authorizedAccounts.entries.forEachIndexed { index, (accountName, permitted) ->
                val accountView = LayoutInflater.from(requireContext()).inflate(R.layout.assisted_signin_multi_layout, accountViews, false)
                accountView.findViewById<TextView>(R.id.account_email).text = accountName
                withContext(Dispatchers.IO) {
                    PeopleManager.getDisplayName(requireContext(), accountName)
                }.let { accountView.findViewById<TextView>(R.id.account_display_name).text = it }
                withContext(Dispatchers.IO) {
                    PeopleManager.getOwnerAvatarBitmap(requireContext(), accountName, false)
                        ?: PeopleManager.getOwnerAvatarBitmap(requireContext(), accountName, true)
                }.let { accountView.findViewById<ImageView>(R.id.account_photo).setImageBitmap(it) }
                if (permitted != null) {
                    accountView.findViewById<TextView>(R.id.account_description).text =
                        getString(R.string.credentials_assisted_signin_button_text_long)
                    accountView.setOnClickListener {
                        progress.visibility = View.VISIBLE
                        prepareChooseLogin(accountName, permitted)
                    }
                } else {
                    accountView.findViewById<TextView>(R.id.account_description).apply {
                        text = getString(R.string.credentials_assisted_choose_account_error_tips)
                        setTextColor(Color.RED)
                    }
                    accountView.setOnClickListener(null)
                }
                if (index == authorizedAccounts.size - 1) {
                    accountView.findViewById<View>(R.id.multi_account_line).visibility = View.GONE
                }
                accountViews.addView(accountView)
            }
            container?.addView(chooseView)
        }
    }

    private fun prepareSignInLoading(accountName: String, cancelBlock: (() -> Unit)? = null) {
        lifecycleScope.launch {
            notifyCancelBtn(false)
            container?.removeAllViews()
            val loadingView =
                LayoutInflater.from(requireContext()).inflate(R.layout.assisted_signin_loading_layout, container, false)
            loadingView.findViewById<TextView>(R.id.sign_account_email).text = accountName
            withContext(Dispatchers.IO) {
                PeopleManager.getDisplayName(requireContext(), accountName)
            }.let { loadingView.findViewById<TextView>(R.id.sign_account_display_name).text = it }
            withContext(Dispatchers.IO) {
                PeopleManager.getOwnerAvatarBitmap(requireContext(), accountName, false)
                    ?: PeopleManager.getOwnerAvatarBitmap(requireContext(), accountName, true)
            }.let { loadingView.findViewById<ImageView>(R.id.sign_account_photo).setImageBitmap(it) }
            if (cancelBlock != null) {
                loadingView.findViewById<TextView>(R.id.sign_cancel).visibility = View.VISIBLE
                loadingView.findViewById<TextView>(R.id.sign_cancel).setOnClickListener {
                    cancelBlock()
                }
            }
            container?.addView(loadingView)
            startLogin(accountName, allowAutoSelectCancellation = cancelBlock != null)
        }
    }

    private fun prepareChooseLogin(accountName: String, permitted: Boolean = false) {
        lifecycleScope.launch {
            notifyCancelBtn(visible = true, backToMulti = authStatusList.size > 1)
            container?.removeAllViews()
            val reloadView =
                LayoutInflater.from(requireContext()).inflate(R.layout.assisted_signin_back_consent_layout, container, false)
            reloadView.findViewById<TextView>(R.id.sign_account_email).text = accountName
            withContext(Dispatchers.IO) {
                PeopleManager.getDisplayName(requireContext(), accountName)
            }.let { reloadView.findViewById<TextView>(R.id.sign_account_display_name).text = it }
            withContext(Dispatchers.IO) {
                PeopleManager.getOwnerAvatarBitmap(requireContext(), accountName, false)
                    ?: PeopleManager.getOwnerAvatarBitmap(requireContext(), accountName, true)
            }.let { reloadView.findViewById<ImageView>(R.id.sign_account_photo).setImageBitmap(it) }
            withContext(Dispatchers.IO) {
                PeopleManager.getGivenName(requireContext(), accountName)
            }.let {
                reloadView.findViewById<MaterialButton>(R.id.sign_reloading_back).text =
                    if (it.isNullOrEmpty()) getString(R.string.credentials_assisted_continue) else String.format(
                        getString(R.string.credentials_assisted_continue_as_user_button_label), it
                    )
            }
            reloadView.findViewById<MaterialButton>(R.id.sign_reloading_back).setOnClickListener {
                reloadView.findViewById<ProgressBar>(R.id.sign_reloading_progress).visibility = View.VISIBLE
                prepareSignInLoading(accountName)
            }
            val clientAppLabel = requireContext().packageManager.getApplicationLabel(clientPackageName)
            reloadView.findViewById<TextView>(R.id.sign_reloading_title).text = if (!permitted) String.format(
                getString(R.string.credentials_assisted_signin_consent_header), clientAppLabel
            ) else String.format(getString(R.string.credentials_assisted_sign_back_title), clientAppLabel)
            val consentTextView = reloadView.findViewById<TextView>(R.id.sign_reloading_consent)
            consentTextView.text =
                String.format(getString(R.string.credentials_assisted_signin_consent), clientAppLabel)
            consentTextView.visibility = if (!permitted) View.VISIBLE else View.GONE
            container?.addView(reloadView)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
            dialog.setCanceledOnTouchOutside(false)
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (isSigningIn) {
                    cancelLogin(true)
                } else dialog.dismiss()
                return@setOnKeyListener true
            }
            return@setOnKeyListener false
        }
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        Log.d(TAG, "onCreateView")
        return layoutInflater.inflate(R.layout.assisted_signin_google_dialog, container, false);
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated")
        cancelBtn = view.findViewById(R.id.cancel)
        container = view.findViewById(R.id.google_sign_in_container)
        // Account data arrives after the initial sheet animation. Recompute its target
        // when content height changes instead of settling at the old loading height.
        container?.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                (dialog as? BottomSheetDialog)?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        refreshJob?.cancel()
        val host = activity
        if (!signInBack && host != null && !host.isFinishing && !host.isChangingConfigurations) {
            cancelLogin()
            errorResult(Status.CANCELED)
        }
        super.onDismiss(dialog)
    }

    private fun notifyCancelBtn(visible: Boolean, backToMulti: Boolean = false) {
        cancelBtn?.visibility = if (visible) View.VISIBLE else View.GONE
        cancelBtn?.isClickable = visible
        cancelBtn?.setOnClickListener {
            if (backToMulti) {
                prepareMultiSignIn(authStatusList)
                return@setOnClickListener
            }
            dismiss()
        }
    }

    private fun startLogin(accountName: String, allowAutoSelectCancellation: Boolean) {
        if (loginJob?.isActive == true) return
        loginJob = lifecycleScope.launch {
            val account = accounts.find { it.name == accountName }
                ?: return@launch errorResult(Status(CommonStatusCodes.SIGN_IN_REQUIRED))
            lastChooseAccount = account
            isSigningIn = true
            if (allowAutoSelectCancellation) delay(3000)
            try {
                val (_, googleSignInAccount) = withContext(Dispatchers.IO) {
                    performSignIn(requireContext(), clientPackageName, options, account, true, beginSignInRequest.googleIdTokenRequestOptions.nonce)
                }
                loginResult(googleSignInAccount)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Sign-in failed: ${e.javaClass.simpleName}")
                errorResult(signInFailureStatus(e))
            }
        }
    }

    fun cancelLogin(showChoose: Boolean = false) {
        Log.d(TAG, "cancelLogin")
        isSigningIn = false
        loginJob?.cancel()
        if (showChoose && lastChooseAccount != null) {
            val name = lastChooseAccount!!.name
            prepareChooseLogin(name, authStatusList[name] == true)
        }
    }

    private fun errorResult(status: Status = Status.INTERNAL_ERROR) {
        if (activity != null && activity is AssistedSignInActivity) {
            val assistedSignInActivity = activity as AssistedSignInActivity
            assistedSignInActivity.errorResult(status)
        }
        activity?.finish()
    }

    private fun loginResult(googleSignInAccount: GoogleSignInAccount?) {
        if (activity != null && activity is AssistedSignInActivity) {
            signInBack = true
            runCatching {
                val assistedSignInActivity = activity as AssistedSignInActivity
                AccountUtils.get(requireContext()).saveSelectedAccount(clientPackageName, googleSignInAccount?.account)
                assistedSignInActivity.loginResult(googleSignInAccount)
            }
        }
        activity?.finish()
    }

}
