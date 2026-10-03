package dev.ayaya.dailyobsi.todo

import android.app.Activity
import android.content.Intent
import com.nextcloud.android.sso.AccountImporter
import com.nextcloud.android.sso.exceptions.NextcloudFilesAppNotInstalledException
import com.nextcloud.android.sso.model.SingleSignOnAccount

/**
 * The two-step Single Sign-On handshake: Android's account picker (accounts
 * owned by the Nextcloud Files app), then the Files app's "allow DailyObsi
 * to use this account" screen. Both come back through `onActivityResult`.
 *
 * Not using `AccountImporter.onActivityResult`: its error paths open AppCompat
 * dialogs, which crash under this app's non-AppCompat theme. Errors come back
 * as messages instead.
 */
object NextcloudSignIn {
    sealed interface Result {
        /** Not one of ours, or a step finished and the next one started. */
        data object Ignored : Result
        data object Cancelled : Result
        data class Connected(val account: SingleSignOnAccount) : Result
        data class Failed(val message: String) : Result
    }

    /** Opens the account picker; returns an error message if it can't. */
    fun start(activity: Activity): String? = try {
        AccountImporter.pickNewAccount(activity)
        null
    } catch (_: NextcloudFilesAppNotInstalledException) {
        "Install the Nextcloud app and log in to your server there first."
    } catch (error: Exception) {
        error.message ?: "Couldn't open the Nextcloud account picker"
    }

    fun handleResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?): Result {
        val ok = resultCode == Activity.RESULT_OK
        return when (requestCode) {
            AccountImporter.CHOOSE_ACCOUNT_SSO -> when {
                !ok || data == null -> Result.Cancelled
                else -> try {
                    AccountImporter.requestAuthToken(activity, data)
                    Result.Ignored
                } catch (error: Exception) {
                    Result.Failed(error.message ?: "The Nextcloud app refused the request")
                }
            }
            AccountImporter.REQUEST_AUTH_TOKEN_SSO -> if (ok && data != null) {
                try {
                    Result.Connected(AccountImporter.extractSingleSignOnAccountFromResponse(data, activity))
                } catch (error: Exception) {
                    Result.Failed(error.message ?: "Couldn't read the Nextcloud account")
                }
            } else if (data == null) {
                Result.Cancelled // backed out of the Files app's screen
            } else {
                try {
                    AccountImporter.handleFailedAuthRequest(activity, data)
                    Result.Cancelled
                } catch (error: Exception) {
                    Result.Failed(error.message ?: "Nextcloud access was not granted")
                }
            }
            else -> Result.Ignored
        }
    }
}
