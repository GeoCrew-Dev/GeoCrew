package org.geocrew.feature.team.push

import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.geocrew.core.data.di.ApplicationScope
import org.geocrew.core.network.PushKeys
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import javax.inject.Inject

/** Receives UnifiedPush events; the instance is the project id. */
@AndroidEntryPoint
class GeoCrewPushService : PushService() {
    @Inject lateinit var push: TeamPush

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val keys = endpoint.pubKeySet?.let { PushKeys(it.pubKey, it.auth) }
        scope.launch { push.onNewEndpoint(instance, endpoint.url, keys) }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        // Notices hold no secrets, so one the library could not decrypt
        // (a server without keys sends clear JSON) is still read.
        val content = message.content
        scope.launch { push.onMessage(instance, content) }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        scope.launch { push.onUnregistered(instance) }
    }

    override fun onUnregistered(instance: String) {
        scope.launch { push.onUnregistered(instance) }
    }
}
