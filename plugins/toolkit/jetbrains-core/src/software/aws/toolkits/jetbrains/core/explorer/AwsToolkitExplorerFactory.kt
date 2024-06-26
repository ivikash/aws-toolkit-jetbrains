// Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.core.explorer

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowEx
import software.aws.toolkits.core.utils.debug
import software.aws.toolkits.core.utils.getLogger
import software.aws.toolkits.jetbrains.AwsToolkit
import software.aws.toolkits.jetbrains.core.credentials.AwsBearerTokenConnection
import software.aws.toolkits.jetbrains.core.credentials.AwsConnectionManager
import software.aws.toolkits.jetbrains.core.credentials.AwsConnectionManagerConnection
import software.aws.toolkits.jetbrains.core.credentials.ConnectionSettingsStateChangeNotifier
import software.aws.toolkits.jetbrains.core.credentials.ConnectionState
import software.aws.toolkits.jetbrains.core.credentials.ToolkitConnection
import software.aws.toolkits.jetbrains.core.credentials.ToolkitConnectionManager
import software.aws.toolkits.jetbrains.core.credentials.ToolkitConnectionManagerListener
import software.aws.toolkits.jetbrains.core.credentials.pinning.CodeCatalystConnection
import software.aws.toolkits.jetbrains.core.credentials.sono.CODECATALYST_SCOPES
import software.aws.toolkits.jetbrains.core.credentials.sono.IDENTITY_CENTER_ROLE_ACCESS_SCOPE
import software.aws.toolkits.jetbrains.core.credentials.sso.bearer.BearerTokenAuthState
import software.aws.toolkits.jetbrains.core.credentials.sso.bearer.BearerTokenProviderListener
import software.aws.toolkits.jetbrains.core.experiments.ExperimentsActionGroup
import software.aws.toolkits.jetbrains.core.explorer.webview.ToolkitWebviewPanel
import software.aws.toolkits.jetbrains.core.explorer.webview.shouldPromptToolkitReauth
import software.aws.toolkits.jetbrains.core.help.HelpIds
import software.aws.toolkits.jetbrains.core.webview.BrowserState
import software.aws.toolkits.jetbrains.utils.actions.OpenBrowserAction
import software.aws.toolkits.jetbrains.utils.isTookitConnected
import software.aws.toolkits.resources.message
import software.aws.toolkits.telemetry.FeatureId
import javax.swing.JComponent

class AwsToolkitExplorerFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.helpId = HelpIds.EXPLORER_WINDOW.id

        if (toolWindow is ToolWindowEx) {
            val actionManager = ActionManager.getInstance()
            toolWindow.setTitleActions(listOf(actionManager.getAction("aws.toolkit.explorer.titleBar")))
            toolWindow.setAdditionalGearActions(
                DefaultActionGroup().apply {
                    add(
                        OpenBrowserAction(
                            title = message("explorer.view_documentation"),
                            url = AwsToolkit.AWS_DOCS_URL
                        )
                    )
                    add(
                        OpenBrowserAction(
                            title = message("explorer.view_source"),
                            icon = AllIcons.Vcs.Vendors.Github,
                            url = AwsToolkit.GITHUB_URL
                        )
                    )
                    add(
                        OpenBrowserAction(
                            title = message("explorer.create_new_issue"),
                            icon = AllIcons.Vcs.Vendors.Github,
                            url = "${AwsToolkit.GITHUB_URL}/issues/new/choose"
                        )
                    )
                    add(actionManager.getAction("aws.toolkit.showFeedback"))
                    add(ExperimentsActionGroup())
                    add(actionManager.getAction("aws.settings.show"))
                }
            )
        }

        val contentManager = toolWindow.contentManager

        val component = if (!isTookitConnected(project) || shouldPromptToolkitReauth(project)) {
            ToolkitWebviewPanel.getInstance(project).component
        } else {
            AwsToolkitExplorerToolWindow.getInstance(project)
        }

        val content = contentManager.factory.createContent(component, null, false).also {
            it.isCloseable = true
            it.isPinnable = true
        }
        contentManager.addContent(content)
        toolWindow.activate(null)
        contentManager.setSelectedContent(content)

        project.messageBus.connect().subscribe(
            ToolkitConnectionManagerListener.TOPIC,
            object : ToolkitConnectionManagerListener {
                override fun activeConnectionChanged(newConnection: ToolkitConnection?) {
                    connectionChanged(project, newConnection)
                }
            }
        )

        project.messageBus.connect().subscribe(
            AwsConnectionManager.CONNECTION_SETTINGS_STATE_CHANGED,
            object : ConnectionSettingsStateChangeNotifier {
                override fun settingsStateChanged(newState: ConnectionState) {
                    settingsStateChanged(project, newState)
                }
            }
        )

        project.messageBus.connect().subscribe(
            BearerTokenProviderListener.TOPIC,
            object : BearerTokenProviderListener {
                override fun onChange(providerId: String, newScopes: List<String>?) {
                    if (ToolkitConnectionManager.getInstance(project)
                            .connectionStateForFeature(CodeCatalystConnection.getInstance()) == BearerTokenAuthState.AUTHORIZED
                    ) {
                        showExplorerTree(project)
                    }
                }
            }
        )
    }

    override fun init(toolWindow: ToolWindow) {
        toolWindow.stripeTitle = message("aws.notification.title")
    }

    private fun connectionChanged(project: Project, newConnection: ToolkitConnection?) {
        val isNewConnToolkitConnection = when (newConnection) {
            is AwsConnectionManagerConnection -> {
                LOG.debug { "IAM connection" }
                true
            }

            is AwsBearerTokenConnection -> {
                val hasCodecatalystScope = CODECATALYST_SCOPES.all { it in newConnection.scopes }
                val hasIdcRoleAccess = newConnection.scopes.contains(IDENTITY_CENTER_ROLE_ACCESS_SCOPE)

                LOG.debug { "Bearer connection: isCodecatalyst=$hasCodecatalystScope; isIdCRoleAccess=$hasIdcRoleAccess" }

                CODECATALYST_SCOPES.all { it in newConnection.scopes } ||
                    newConnection.scopes.contains(IDENTITY_CENTER_ROLE_ACCESS_SCOPE)
            }

            else -> false
        }

        if (isNewConnToolkitConnection) {
            showExplorerTree(project)
        } else if (!isTookitConnected(project) || shouldPromptToolkitReauth(project)) {
            ToolkitWebviewPanel.getInstance(project).browser?.prepareBrowser(BrowserState(FeatureId.AwsExplorer))
            showWebview(project)
        } else {
            showExplorerTree(project)
        }
    }

    private fun settingsStateChanged(project: Project, newState: ConnectionState) {
        val isToolkitConnected = if (newState is ConnectionState.ValidConnection) {
            true
        } else {
            isTookitConnected(project) && !shouldPromptToolkitReauth(project)
        }

        LOG.debug { "settingsStateChanged: ${newState::class.simpleName}; isToolkitConnected=$isToolkitConnected" }

        if (!isToolkitConnected || shouldPromptToolkitReauth(project)) {
            ToolkitWebviewPanel.getInstance(project).browser?.prepareBrowser(BrowserState(FeatureId.AwsExplorer))
            showWebview(project)
        } else {
            showExplorerTree(project)
        }
    }

    companion object {
        private val LOG = getLogger<AwsToolkitExplorerFactory>()
        const val TOOLWINDOW_ID = "aws.toolkit.explorer"
    }
}

fun showWebview(project: Project) {
    AwsToolkitExplorerToolWindow.toolWindow(project).loadContent(ToolkitWebviewPanel.getInstance(project).component)
}

fun showExplorerTree(project: Project) {
    AwsToolkitExplorerToolWindow.toolWindow(project).loadContent(AwsToolkitExplorerToolWindow.getInstance(project))
}

private fun ToolWindow.loadContent(component: JComponent) {
    val content = contentManager.factory.createContent(component, null, false).also {
        it.isCloseable = true
        it.isPinnable = true
    }

    runInEdt {
        contentManager.removeAllContents(true)
        contentManager.addContent(content)
    }
}
