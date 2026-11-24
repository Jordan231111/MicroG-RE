/*
 * SPDX-FileCopyrightText: 2024 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.appset

import android.content.Context
import android.util.Log
import com.google.android.gms.appset.AppSetIdInfo
import com.google.android.gms.appset.AppSetIdRequestParams
import com.google.android.gms.appset.AppSetInfoParcel
import com.google.android.gms.appset.internal.IAppSetIdCallback
import com.google.android.gms.appset.internal.IAppSetService
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.Feature
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.internal.ConnectionInfo
import com.google.android.gms.common.internal.GetServiceRequest
import com.google.android.gms.common.internal.IGmsCallbacks
import org.microg.gms.BaseService
import org.microg.gms.common.GmsService
import org.microg.gms.common.PackageUtils
import java.util.UUID

private const val TAG = "AppSetService"
private val FEATURES = arrayOf(Feature("app_set_id", 1L))

class AppSetService : BaseService(TAG, GmsService.APP_SET_ID) {

    override fun handleServiceRequest(callback: IGmsCallbacks?, request: GetServiceRequest?, service: GmsService?) {
        val packageName = PackageUtils.getAndCheckCallingPackageWithoutSpoofing(this, request?.packageName)
            ?: throw IllegalArgumentException("Missing calling package")
        callback?.onPostInitCompleteWithConnectionInfo(
            ConnectionResult.SUCCESS,
            AppSetServiceImpl(this, packageName).asBinder(),
            ConnectionInfo().apply { features = FEATURES }
        )
    }
}

class AppSetServiceImpl(private val context: Context, private val packageName: String) : IAppSetService.Stub() {
    override fun getAppSetIdInfo(params: AppSetIdRequestParams?, callback: IAppSetIdCallback?) {
        // The broker verified the package on connection. Verify it again on each
        // Binder call so a transferred binder cannot read another app's ID.
        PackageUtils.getAndCheckCallingPackageWithoutSpoofing(context, packageName)
        val id = synchronized(AppSetServiceImpl::class.java) {
            val preferences = context.getSharedPreferences("app_set_ids", Context.MODE_PRIVATE)
            preferences.getString(packageName, null) ?: UUID.randomUUID().toString().also {
                check(preferences.edit().putString(packageName, it).commit()) { "Failed to persist App Set ID" }
            }
        }
        Log.d(TAG, "Returning stable app-scoped ID to $packageName")
        callback?.onAppSetInfo(Status.SUCCESS, AppSetInfoParcel(id, AppSetIdInfo.SCOPE_APP))
    }
}
