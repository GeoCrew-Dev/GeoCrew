package org.geocrew.feature.team.push

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import org.geocrew.core.data.team.TeamLeaveHook

@Module
@InstallIn(SingletonComponent::class)
abstract class TeamPushModule {
    /** Leaving a workspace or deleting its project also stops its notices. */
    @Binds
    @IntoSet
    abstract fun stopPushOnLeave(push: TeamPush): TeamLeaveHook
}
