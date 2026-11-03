package com.mywisper.core

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.mywisper.R
import kotlinx.coroutines.tasks.await

/**
 * Google sign-in (Android Credential Manager) → Firebase. Same Firebase project and Google account as the
 * Windows app, so both see the same history, prompts and dictionary.
 */
object Account {
    val user: FirebaseUser? get() = FirebaseAuth.getInstance().currentUser
    val isSignedIn get() = user != null

    /** Shows Google's account picker; returns null on success or a short error message. */
    suspend fun signIn(activity: Activity): String? {
        val option = GetSignInWithGoogleOption.Builder(activity.getString(R.string.default_web_client_id)).build()
        return try {
            val result = CredentialManager.create(activity).getCredential(activity, GetCredentialRequest.Builder().addCredentialOption(option).build())
            val cred = result.credential
            if (cred !is CustomCredential || cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                return "Unexpected sign-in result"
            }
            val idToken = GoogleIdTokenCredential.createFrom(cred.data).idToken
            FirebaseAuth.getInstance().signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
            null
        } catch (e: GetCredentialCancellationException) {
            "Sign-in cancelled"
        } catch (e: NoCredentialException) {
            "No Google account on this phone"
        } catch (e: Exception) {
            e.message ?: "Sign-in failed"
        }
    }

    suspend fun signOut(context: Context) {
        FirebaseAuth.getInstance().signOut()
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
    }
}
