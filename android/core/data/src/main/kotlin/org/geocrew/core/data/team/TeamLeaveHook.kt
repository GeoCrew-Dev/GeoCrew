package org.geocrew.core.data.team

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * Runs while a project is still linked, just before [TeamRepository.leave]
 * drops the link and token, so features can undo what they set up on the
 * server (a push endpoint, say). Called for both "leave the workspace" and
 * "delete the project". Failures are swallowed; leaving always succeeds.
 *
 * Contribute one with `@Binds @IntoSet` from any module.
 */
fun interface TeamLeaveHook {
    suspend fun beforeLeave(projectId: String)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TeamHooksModule {
    /** Declared so the set exists even when no feature contributes. */
    @Multibinds
    abstract fun leaveHooks(): Set<TeamLeaveHook>
}
