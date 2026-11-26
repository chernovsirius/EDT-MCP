/**
 * MCP Server for EDT - Sirius Fork
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Sirius customizations Copyright (C) 2026 Sirius
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Curated Sirius tool profile and noise-filter configuration.
 * <p>
 * Keeps the MCP surface lean (~20 high-value tools instead of 105+) while leaving upstream
 * tool classes untouched so future upstream merges stay conflict-free.
 * Set {@code -Dedt.mcp.allTools=true} or env {@code EDT_MCP_ALL_TOOLS=true} to expose every
 * upstream tool without rebuilding.
 */
public final class SiriusProfile
{
    /** System property / env override to expose all upstream tools. */
    public static final String PROP_ALL_TOOLS = "edt.mcp.allTools"; //$NON-NLS-1$
    public static final String ENV_ALL_TOOLS = "EDT_MCP_ALL_TOOLS"; //$NON-NLS-1$

    /**
     * Curated whitelist of tools exposed in the Sirius fork:
     * - Semantic validation & queries (what files + bsl-ls cannot do)
     * - EDT infobase sync (copy2)
     * - Metadata adoption & 3-way release merge
     * - Minimal EDT lifecycle / dialog controls
     */
    public static final Set<String> ENABLED_TOOLS = Set.of(
        // Semantic checks & analysis
        "check_changed", //$NON-NLS-1$
        "revalidate_objects", //$NON-NLS-1$
        "get_project_errors", //$NON-NLS-1$
        "validate_query", //$NON-NLS-1$
        "validate_form_model", //$NON-NLS-1$
        "find_references", //$NON-NLS-1$
        "get_method_call_hierarchy", //$NON-NLS-1$
        "get_check_description", //$NON-NLS-1$
        // Infobase / database update
        "update_database", //$NON-NLS-1$
        "get_applications", //$NON-NLS-1$
        "infobase_sessions", //$NON-NLS-1$
        "infobase_extensions", //$NON-NLS-1$
        // Metadata adoption & 3-way release merge
        "adopt_metadata_object", //$NON-NLS-1$
        "compare_configurations", //$NON-NLS-1$
        "get_comparison_node", //$NON-NLS-1$
        "merge_rules", //$NON-NLS-1$
        // EDT lifecycle & diagnostics
        "list_projects", //$NON-NLS-1$
        "clean_project", //$NON-NLS-1$
        "edt_dialog", //$NON-NLS-1$
        "get_server_status" //$NON-NLS-1$
    );

    /**
     * Noisy non-error checks that fire massively on standard 1C:ERP code.
     * Hidden from default listings (still counted in the summary header);
     * ERROR/BLOCKER/CRITICAL severities are NEVER suppressed even if matched.
     */
    public static final Set<String> DEFAULT_NOISY_CHECKS = Set.of(
        "bsl-legacy-check-static-feature-access-for-unknown-left-part", //$NON-NLS-1$
        "module-structure-method-in-regions", //$NON-NLS-1$
        "bsl-variable-name-invalid", //$NON-NLS-1$
        "doc-comment-collection-item-type", //$NON-NLS-1$
        "doc-comment-complex-type-with-link", //$NON-NLS-1$
        "constructor-function-return-section" //$NON-NLS-1$
    );

    private SiriusProfile()
    {
        // Utility class
    }

    /** Whether all upstream tools should be exposed instead of the Sirius whitelist. */
    public static boolean isAllToolsMode()
    {
        if (Boolean.getBoolean(PROP_ALL_TOOLS))
        {
            return true;
        }
        String env = System.getenv(ENV_ALL_TOOLS);
        return env != null && "true".equalsIgnoreCase(env.trim()); //$NON-NLS-1$
    }

    /**
     * Filters the full upstream catalogue down to {@link #ENABLED_TOOLS} unless
     * {@link #isAllToolsMode()} is active.
     */
    public static List<IMcpTool> filterCatalogue(List<IMcpTool> fullCatalogue)
    {
        if (isAllToolsMode())
        {
            Activator.logInfo("SiriusProfile: all-tools mode active, keeping all " + fullCatalogue.size() + " tools"); //$NON-NLS-1$ //$NON-NLS-2$
            return fullCatalogue;
        }
        List<IMcpTool> filtered = fullCatalogue.stream()
            .filter(t -> t != null && ENABLED_TOOLS.contains(t.getName()))
            .collect(Collectors.toList());
        Activator.logInfo("SiriusProfile: filtered " + fullCatalogue.size() + " -> " + filtered.size() + " tools"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return filtered;
    }

    /** Checks whether a check ID belongs to the default noisy set. */
    public static boolean isDefaultNoisyCheck(String checkId)
    {
        return checkId != null && DEFAULT_NOISY_CHECKS.contains(checkId);
    }
}
