package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpPolicyAction

internal enum class McpSectionMode { All, View, Update, Custom }

internal fun savedSectionMode(
    tools: List<McpToolIdentity>,
    rules: Map<String, McpPolicyAction>,
): McpSectionMode {
    val explicit = tools.all { rules[it.toolName] in setOf(McpPolicyAction.ALLOW, McpPolicyAction.DENY) }
    val allowed = tools.filter { rules[it.toolName] == McpPolicyAction.ALLOW }.map { it.toolName }.toSet()
    if (!explicit || allowed.isEmpty()) return McpSectionMode.Custom
    return listOf(McpSectionMode.All, McpSectionMode.View, McpSectionMode.Update)
        .firstOrNull { sectionSelection(tools, it) == allowed } ?: McpSectionMode.Custom
}
