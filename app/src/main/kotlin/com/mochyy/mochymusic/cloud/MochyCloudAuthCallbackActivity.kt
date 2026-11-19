package com.mochyy.mochymusic.cloud

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.mochyy.mochymusic.MainActivity

/**
 * Receives the Mochy Cloud Google OAuth callback (`mochy-cloud://auth-callback`),
 * hands it to [MochyCloudSyncManager], and brings [MainActivity] back to the front.
 */
class MochyCloudAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent?.data?.let { callbackUri ->
            MochyCloudSyncManager.handleGoogleOAuthCallback(applicationContext, callbackUri)
        }
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
