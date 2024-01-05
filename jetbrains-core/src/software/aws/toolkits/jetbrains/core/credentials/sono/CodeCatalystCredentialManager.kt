// Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.core.credentials.sono

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import software.aws.toolkits.core.TokenConnectionSettings
import software.aws.toolkits.core.telemetry.DefaultMetricEvent
import software.aws.toolkits.core.utils.tryOrNull
import software.aws.toolkits.jetbrains.core.AwsResourceCache
import software.aws.toolkits.jetbrains.core.credentials.AwsBearerTokenConnection
import software.aws.toolkits.jetbrains.core.credentials.ToolkitConnectionManager
import software.aws.toolkits.jetbrains.core.credentials.logoutFromSsoConnection
import software.aws.toolkits.jetbrains.core.credentials.pinning.CodeCatalystConnection
import software.aws.toolkits.jetbrains.core.credentials.reauthProviderIfNeeded
import software.aws.toolkits.jetbrains.core.credentials.sso.bearer.BearerTokenAuthState
import software.aws.toolkits.jetbrains.core.credentials.sso.bearer.BearerTokenProvider
import software.aws.toolkits.jetbrains.core.gettingstarted.requestCredentialsForCodeCatalyst
import software.aws.toolkits.jetbrains.services.caws.CawsResources
import software.aws.toolkits.jetbrains.utils.computeOnEdt
import software.aws.toolkits.jetbrains.utils.runUnderProgressIfNeeded
import software.aws.toolkits.resources.message

class CodeCatalystCredentialManager {
    private val project: Project?
    constructor(project: Project) {
        this.project = project
    }

    constructor() {
        this.project = null
    }

    internal fun connection() = (
        ToolkitConnectionManager.getInstance(project).activeConnectionForFeature(CodeCatalystConnection.getInstance())
            as? AwsBearerTokenConnection
        )

    internal fun provider(conn: AwsBearerTokenConnection) = conn.getConnectionSettings().tokenProvider.delegate as BearerTokenProvider

    fun getConnectionSettings(passiveOnly: Boolean = false): TokenConnectionSettings? {
        val connection = connection()
        if (connection == null) {
            if (passiveOnly) {
                return null
            }
            return getSettingsAndPromptAuth()
        }

        val provider = provider(connection)
        return when (provider.state()) {
            BearerTokenAuthState.NOT_AUTHENTICATED -> null
            BearerTokenAuthState.AUTHORIZED -> connection.getConnectionSettings()
            else -> {
                if (passiveOnly) {
                    null
                } else {
                    tryOrNull {
                        getSettingsAndPromptAuth()
                    }
                }
            }
        }
    }

    fun getSettingsAndPromptAuth(): TokenConnectionSettings {
        promptAuth()
        val connection = connection() ?: error("Expected connection not to be null")
        return connection.getConnectionSettings()
    }

    fun promptAuth(): BearerTokenProvider {
        connection()?.let {
            return reauthProviderIfNeeded(project, provider(it))
        }

        return runUnderProgressIfNeeded(project, message("credentials.pending.title"), true) {
            val closed = computeOnEdt {
                requestCredentialsForCodeCatalyst(project)
            }
            if (closed) {
                connection()?.let {
                    return@runUnderProgressIfNeeded provider(it)
                }
            }
            error("Unable to request credentials for CodeCatalyst")
        }
    }

    fun closeConnection() {
        connection()?.let { logoutFromSsoConnection(project, it) }
    }

    fun isConnected(): Boolean = connection()?.let { provider(it).state() != BearerTokenAuthState.NOT_AUTHENTICATED } ?: false

    companion object {
        fun getInstance(project: Project? = null) = project?.let { it.service<CodeCatalystCredentialManager>() } ?: service()
    }
}

fun lazilyGetUserId() = tryOrNull {
    CodeCatalystCredentialManager.getInstance().getConnectionSettings(passiveOnly = true)?.let {
        AwsResourceCache.getInstance().getResourceNow(CawsResources.ID, it)
    }
} ?: DefaultMetricEvent.METADATA_NA