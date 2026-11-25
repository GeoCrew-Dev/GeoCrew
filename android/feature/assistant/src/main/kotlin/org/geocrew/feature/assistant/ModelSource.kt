package org.geocrew.feature.assistant

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.JsonObject
import org.geocrew.core.model.action.OpenAiWire

/**
 * Where the assistant's answers come from, behind the rule-based quick
 * commands that run first. Requests and replies use the OpenAI chat format
 * with tool calls ([OpenAiWire]) whatever the source, so a local model
 * (for example a GGUF model run by llama.cpp on the phone) or a small
 * model trained for field work can be added later as another
 * implementation without touching the action layer or the screens.
 */
interface ModelSource {
    /** Shown in settings and the chat, e.g. "云端接口". */
    val label: String

    /** True when it can answer now (configured, downloaded...). */
    val available: Boolean

    suspend fun chat(request: JsonObject): OpenAiWire.Reply

    /** The model name to put in requests. */
    val model: String
}

/** The user's own OpenAI-compatible endpoint. */
class CloudModelSource @javax.inject.Inject constructor(
    private val client: OpenAiClient,
    private val config: AssistantConfig,
) : ModelSource {
    override val label = "云端接口"
    override val available: Boolean get() = config.values.value.ready
    override val model: String get() = config.values.value.model
    override suspend fun chat(request: JsonObject) = client.chat(request)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ModelSourceModule {
    @Binds
    abstract fun bind(source: CloudModelSource): ModelSource
}
