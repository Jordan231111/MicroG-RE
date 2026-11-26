/*
 * SPDX-FileCopyrightText: 2026 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */
package org.microg.gms.auth.signin

import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import kotlinx.coroutines.CancellationException
import java.io.IOException

internal class ConsentCanceledException : Exception("Consent canceled")

/** Lifecycle cancellation must stop work without delivering a new activity result. */
internal fun signInFailureStatus(error: Throwable): Status = when (error) {
    is CancellationException -> throw error
    is ConsentCanceledException -> Status.CANCELED
    is IOException -> Status(CommonStatusCodes.NETWORK_ERROR)
    is IllegalArgumentException -> Status(CommonStatusCodes.DEVELOPER_ERROR)
    else -> Status.INTERNAL_ERROR
}
