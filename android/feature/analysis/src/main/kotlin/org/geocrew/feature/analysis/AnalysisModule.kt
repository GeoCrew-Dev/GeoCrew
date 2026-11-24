package org.geocrew.feature.analysis

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet
import org.geocrew.core.gis.GisTool
import org.geocrew.core.gis.ToolRegistry

/**
 * Tools are collected into a `Set<GisTool>`. Another module adds its own with
 * `@Provides @IntoSet fun myTool(): GisTool = MyTool()`.
 */
@Module
@InstallIn(SingletonComponent::class)
object AnalysisModule {
    @Provides
    @ElementsIntoSet
    fun builtInTools(): Set<GisTool> = ToolRegistry.builtIn().toSet()
}
