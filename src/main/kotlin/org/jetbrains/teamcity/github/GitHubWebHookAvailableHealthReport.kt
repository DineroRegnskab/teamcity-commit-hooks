package org.jetbrains.teamcity.github

import jetbrains.buildServer.dataStructures.MultiMapToSet
import jetbrains.buildServer.serverSide.healthStatus.*
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.vcs.SVcsRoot
import java.util.*

class GitHubWebHookAvailableHealthReport(private val WebHooksManager: WebHooksManager,
                                         private val OAuthConnectionsManager: OAuthConnectionsManager) : HealthStatusReport() {
    companion object {
        val TYPE = "GitHub.WebHookAvailable"
        val CATEGORY: ItemCategory = ItemCategory("GH.WebHook.Available", "GitHub repo polling could be replaced with webhook", ItemSeverity.INFO)

        fun splitRoots(vcsRoots: Set<SVcsRoot>): MultiMapToSet<GitHubRepositoryInfo, SVcsRoot> {
            val map = MultiMapToSet<GitHubRepositoryInfo, SVcsRoot>()

            for (root in vcsRoots) {
                val info = Util.Companion.getGitHubInfo(root) ?: continue

                // Ignore roots with unresolved references in url
                if (info.isHasParameterReferences()) continue

                map.add(info, root)
            }
            return map
        }
    }

    override fun getType(): String = TYPE

    override fun getDisplayName(): String {
        return "Find VCS roots which can use GitHub push hook instead of polling"
    }

    override fun getCategories(): MutableCollection<ItemCategory> {
        return arrayListOf(CATEGORY)
    }

    override fun canReportItemsFor(scope: HealthStatusScope): Boolean {
        if (!scope.isItemWithSeverityAccepted(CATEGORY.severity)) return false
        var found = false
        Util.findSuitableRoots(scope) { found = true; false }
        return found
    }


    override fun report(scope: HealthStatusScope, resultConsumer: HealthStatusItemConsumer) {
        val gitRoots = HashSet<SVcsRoot>()
        Util.findSuitableRoots(scope, { gitRoots.add(it); true })

        val split = splitRoots(gitRoots)

        val filtered = split.entrySet()
                .filter {
                    WebHooksManager.storage.getHooks(it.key).isEmpty()
                }
                .filterKnownServers(OAuthConnectionsManager)

        for ((info, roots) in filtered) {
            if (WebHooksManager.storage.getHooks(info).isNotEmpty()) {
                // Something changes since filtering on '.filter' above
                continue
            }
            for (root in roots) {
                // 'Add WebHook' part
                val item = WebHookAddHookHealthItem(info, root)
                resultConsumer.consumeForVcsRoot(root, item)
                root.usagesInConfigurations.forEach { resultConsumer.consumeForBuildType(it, item) }
                root.usagesInProjects.plus(root.project).toSet().forEach { resultConsumer.consumeForProject(it, item) }
            }
        }
    }
}
