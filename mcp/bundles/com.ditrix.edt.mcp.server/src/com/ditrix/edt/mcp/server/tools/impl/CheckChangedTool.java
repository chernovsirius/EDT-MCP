/**
 * MCP Server for EDT - Sirius Fork
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.SiriusProfile;
import com.ditrix.edt.mcp.server.utils.BmModelResolver;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.MarkerRows;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.ditrix.edt.mcp.server.utils.SiriusAuditChecks;
import com.ditrix.edt.mcp.server.utils.SiriusCheckEnabler;
import com.e1c.g5.v8.dt.check.qfix.IFixRepository;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;

/**
 * One-shot Sirius workflow tool: detects edited EDT objects via git diff/status,
 * runs targeted revalidation (even when EDT runs with {@code -DdisableProjectChecks=true}),
 * and returns a compact noise-filtered report highlighting errors and warnings on changed lines.
 */
public class CheckChangedTool implements IMcpTool
{
    public static final String NAME = "check_changed"; //$NON-NLS-1$

    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@"); //$NON-NLS-1$
    private static final int GIT_TIMEOUT_SECONDS = 20;

    private static final Map<String, String> DIR_TO_TYPE = buildDirToTypeMap();

    private static Map<String, String> buildDirToTypeMap()
    {
        Map<String, String> m = new HashMap<>();
        m.put("CommonModules", "CommonModule"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Catalogs", "Catalog"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Documents", "Document"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("DocumentJournals", "DocumentJournal"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("DocumentNumerators", "DocumentNumerator"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Enums", "Enum"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Reports", "Report"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("DataProcessors", "DataProcessor"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Constants", "Constant"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("InformationRegisters", "InformationRegister"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("AccumulationRegisters", "AccumulationRegister"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("AccountingRegisters", "AccountingRegister"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("CalculationRegisters", "CalculationRegister"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ChartsOfAccounts", "ChartOfAccounts"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ChartsOfCharacteristicTypes", "ChartOfCharacteristicTypes"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ChartsOfCalculationTypes", "ChartOfCalculationTypes"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ExchangePlans", "ExchangePlan"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("BusinessProcesses", "BusinessProcess"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Tasks", "Task"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("HTTPServices", "HTTPService"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("WebServices", "WebService"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("WSReferences", "WSReference"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("XDTOPackages", "XDTOPackage"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Roles", "Role"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Subsystems", "Subsystem"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("CommonForms", "CommonForm"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("CommonTemplates", "CommonTemplate"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("CommonCommands", "CommonCommand"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("CommonAttributes", "CommonAttribute"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("CommonPictures", "CommonPicture"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("CommandGroups", "CommandGroup"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("SessionParameters", "SessionParameter"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("FunctionalOptions", "FunctionalOption"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("FunctionalOptionsParameters", "FunctionalOptionsParameter"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("DefinedTypes", "DefinedType"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("EventSubscriptions", "EventSubscription"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ScheduledJobs", "ScheduledJob"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("FilterCriteria", "FilterCriterion"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("SettingsStorages", "SettingsStorage"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Sequences", "Sequence"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ExternalDataSources", "ExternalDataSource"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("IntegrationServices", "IntegrationService"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Bots", "Bot"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Styles", "Style"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("StyleItems", "StyleItem"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Languages", "Language"); //$NON-NLS-1$ //$NON-NLS-2$
        return Collections.unmodifiableMap(m);
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "One-shot validation of git changes in an EDT project: waits for project readiness, runs targeted " //$NON-NLS-1$
            + "EDT revalidation (works with -DdisableProjectChecks=true), checks role rights, dangling references, " //$NON-NLS-1$
            + "&ChangeAndValidate intercepts, universal BSL/form hazards, and optional bsl-ls; returns a compact report."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "EDT project name", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("gitRef", "Optional git base ref for diff (e.g. 'HEAD~1', 'main'). Omit for uncommitted working tree changes (staged + unstaged + untracked).") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("onlyChangedLines", "When true, non-error warnings outside git diff lines are hidden (ERRORS are always shown). Default: false.") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("includeNoisy", "When true, include noisy standard-ERP style warnings normally hidden from the table. Default: false.") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("runBslLs", "When true, also runs external bsl-language-server on changed .bsl files (requires -Dedt.mcp.bslLsJar or BSL_LS_JAR). Default: false.") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("waitReadySec", "Seconds to wait if the project is still starting/building after EDT launch (default 60, 0 = fail immediately).") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("limit", "Max non-error warnings to display (default 30, max 200; all ERRORS are always shown).") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }

        int waitReadySec = JsonUtils.extractIntArgument(params, "waitReadySec", 60); //$NON-NLS-1$
        SiriusCheckEnabler.waitProjectReady(projectName, waitReadySec);

        String building = ProjectStateChecker.buildingErrorOrNull(projectName);
        if (building != null)
        {
            return ToolResult.error(building).toJson();
        }

        String gitRef = JsonUtils.extractStringArgument(params, "gitRef"); //$NON-NLS-1$
        boolean onlyChangedLines = JsonUtils.extractBooleanArgument(params, "onlyChangedLines", false); //$NON-NLS-1$
        boolean includeNoisy = JsonUtils.extractBooleanArgument(params, "includeNoisy", false); //$NON-NLS-1$
        boolean runBslLs = JsonUtils.extractBooleanArgument(params, "runBslLs", false); //$NON-NLS-1$
        int limit = Math.max(1, Math.min(200, JsonUtils.extractIntArgument(params, "limit", 30))); //$NON-NLS-1$

        try
        {
            ProjectContext ctx = ProjectContext.of(projectName);
            if (!ctx.exists())
            {
                return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
            }
            if (!ctx.isOpen())
            {
                return ToolResult.error("Project is closed: " + projectName).toJson(); //$NON-NLS-1$
            }

            IProject project = ctx.project();
            IPath location = project.getLocation();
            if (location == null)
            {
                return ToolResult.error("Cannot resolve filesystem location for project: " + projectName).toJson(); //$NON-NLS-1$
            }
            File projectDir = location.toFile();
            File gitRoot = findGitRoot(projectDir);
            if (gitRoot == null)
            {
                return ToolResult.error("Project is not inside a git repository: " + projectDir.getAbsolutePath()).toJson(); //$NON-NLS-1$
            }

            // Compute project path prefix relative to git root (forward slashes)
            Path rootPath = gitRoot.toPath().toAbsolutePath().normalize();
            Path projPath = projectDir.toPath().toAbsolutePath().normalize();
            String projPrefix = ""; //$NON-NLS-1$
            if (!projPath.equals(rootPath) && projPath.startsWith(rootPath))
            {
                projPrefix = rootPath.relativize(projPath).toString().replace('\\', '/') + "/"; //$NON-NLS-1$
            }

            // Collect changed & newly added files + changed BSL line numbers
            Set<String> changedRepoPaths = collectChangedPaths(gitRoot, projPrefix, gitRef);
            Set<String> addedRepoPaths = collectAddedPaths(gitRoot, projPrefix, gitRef);
            Map<String, Set<Integer>> changedLinesByModule = collectChangedBslLines(gitRoot, projPrefix, gitRef);

            Set<String> fqns = new LinkedHashSet<>();
            Set<String> addedFqns = new LinkedHashSet<>();
            List<String> srcRelPaths = new ArrayList<>();
            Set<String> addedSrcRelPaths = new LinkedHashSet<>();
            for (String repoRel : changedRepoPaths)
            {
                String insideProj = projPrefix.isEmpty() ? repoRel
                    : (repoRel.startsWith(projPrefix) ? repoRel.substring(projPrefix.length()) : null);
                if (insideProj == null)
                {
                    continue;
                }
                if (insideProj.startsWith("src/")) //$NON-NLS-1$
                {
                    String relFromSrc = insideProj.substring("src/".length()); //$NON-NLS-1$
                    srcRelPaths.add(relFromSrc);
                    boolean isAdded = addedRepoPaths.contains(repoRel);
                    if (isAdded)
                    {
                        addedSrcRelPaths.add(relFromSrc);
                    }
                    String fqn = srcPathToFqn(relFromSrc);
                    if (fqn != null)
                    {
                        fqns.add(fqn);
                        if (isAdded && relFromSrc.endsWith(".mdo")) //$NON-NLS-1$
                        {
                            addedFqns.add(fqn);
                        }
                    }
                }
            }

            if (fqns.isEmpty())
            {
                return "# check_changed: " + projectName + "\n\n" //$NON-NLS-1$ //$NON-NLS-2$
                    + "No changed EDT metadata/BSL objects found" //$NON-NLS-1$
                    + (gitRef != null && !gitRef.isEmpty() ? " (vs `" + gitRef + "`)" : " (working tree)") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + ".\n"; //$NON-NLS-1$
            }

            List<String> fqnList = new ArrayList<>(fqns);

            // Step 1: Targeted revalidation (automatically toggles -DdisableProjectChecks=true via SiriusCheckEnabler)
            String revalidateOut = RevalidateObjectsTool.revalidateObjects(projectName, fqnList);
            if (revalidateOut != null && revalidateOut.trim().startsWith("{") && revalidateOut.contains("\"error\"")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                return revalidateOut;
            }

            // Step 2: Universal project-agnostic static audit (roles, dangling refs, intercepts, BSL/form, optional bsl-ls)
            List<SiriusAuditChecks.Finding> auditFindings = SiriusAuditChecks.runAll(
                project, srcRelPaths, addedSrcRelPaths, addedFqns, runBslLs);

            // Step 3: Collect EDT markers + merge audit findings into compact report
            return buildCompactReport(project, fqnList, srcRelPaths, changedLinesByModule,
                auditFindings, gitRef, onlyChangedLines, includeNoisy, limit);
        }
        catch (Exception e)
        {
            Activator.logError("check_changed failed", e); //$NON-NLS-1$
            return ToolResult.error("check_changed failed: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    private static String buildCompactReport(IProject project, List<String> fqnList, List<String> srcRelPaths,
        Map<String, Set<Integer>> changedLinesByModule, List<SiriusAuditChecks.Finding> auditFindings,
        String gitRef, boolean onlyChangedLines, boolean includeNoisy, int limit)
    {
        IMarkerManager markerManager = Activator.getDefault().getMarkerManager();
        if (markerManager == null)
        {
            return ToolResult.error("IMarkerManager service is not available").toJson(); //$NON-NLS-1$
        }

        ICheckRepository checkRepository = Activator.getDefault().getCheckRepository();
        IFixRepository fixRepository = Activator.getDefault().getFixRepository();
        IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();

        Set<String> filterVariants = GetProjectErrorsTool.scanFilterVariants(fqnList, true);
        Map<IProject, List<Marker>> markersByProject = MarkerRows.groupMarkersByProject(markerManager, project.getName());
        List<Marker> projectMarkers = markersByProject.getOrDefault(project, Collections.emptyList());

        List<RowWithSeverity> errorRows = new ArrayList<>();
        List<RowWithSeverity> warningRows = new ArrayList<>();
        Map<String, Integer> hiddenNoisyCounts = new LinkedHashMap<>();
        int[] hiddenOutsideDiff = {0};
        int[] unresolvedFiltered = {0};

        // Merge project-agnostic static audit findings first (always prioritized)
        if (auditFindings != null)
        {
            for (SiriusAuditChecks.Finding f : auditFindings)
            {
                MarkerRows.Row row = new MarkerRows.Row();
                row.modulePath = f.location;
                row.objectPresentation = f.location;
                row.line = f.line;
                row.checkId = f.checkId;
                row.message = f.message;
                boolean onChangedLine = isOnChangedLine(row, changedLinesByModule);
                if ("ERROR".equalsIgnoreCase(f.severity)) //$NON-NLS-1$
                {
                    errorRows.add(new RowWithSeverity(row, MarkerSeverity.ERRORS, onChangedLine));
                }
                else
                {
                    warningRows.add(new RowWithSeverity(row, MarkerSeverity.MAJOR, onChangedLine));
                }
            }
        }

        Runnable scan = () -> {
            for (Marker marker : projectMarkers)
            {
                MarkerRows.Match match = MarkerRows.matchOf(marker, null, null, filterVariants,
                    Collections.emptySet(), checkRepository, unresolvedFiltered, true);
                if (match == null)
                {
                    continue;
                }
                int[] placeholder = new int[1];
                MarkerRows.Row row = MarkerRows.buildRow(marker, match, checkRepository, fixRepository,
                    placeholder, true, MarkerRows.Row::new);

                MarkerSeverity sev = marker.getSeverity() != null ? marker.getSeverity() : MarkerSeverity.NONE;
                boolean isError = (sev == MarkerSeverity.ERRORS || sev == MarkerSeverity.BLOCKER
                    || sev == MarkerSeverity.CRITICAL);
                String cid = (row.checkId != null && !row.checkId.isEmpty()) ? row.checkId : row.checkCode;
                boolean onChangedLine = isOnChangedLine(row, changedLinesByModule);

                if (isError)
                {
                    errorRows.add(new RowWithSeverity(row, sev, onChangedLine));
                    continue;
                }

                if (!includeNoisy && SiriusProfile.isDefaultNoisyCheck(cid))
                {
                    hiddenNoisyCounts.merge(cid, 1, Integer::sum);
                    continue;
                }

                if (onlyChangedLines && !onChangedLine)
                {
                    hiddenOutsideDiff[0]++;
                    continue;
                }

                warningRows.add(new RowWithSeverity(row, sev, onChangedLine));
            }
        };

        IBmModel bmModel = bmModelManager != null ? BmModelResolver.resolve(project, bmModelManager).getModel() : null;
        if (bmModel != null)
        {
            BmTransactions.<Void>read(bmModel, "CheckChangedCollect", (tx, pm) -> { //$NON-NLS-1$
                scan.run();
                return null;
            });
        }
        else
        {
            scan.run();
        }

        // Put warnings on changed lines first
        warningRows.sort((a, b) -> Boolean.compare(b.onChangedLine, a.onChangedLine));

        StringBuilder md = new StringBuilder();
        md.append("# check_changed: ").append(project.getName()).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        md.append("- **Scope:** ").append(fqnList.size()).append(" object(s), ") //$NON-NLS-1$ //$NON-NLS-2$
            .append(srcRelPaths.size()).append(" file(s)") //$NON-NLS-1$
            .append(gitRef != null && !gitRef.isEmpty() ? " (vs `" + gitRef + "`)" : " (working tree)") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .append("\n"); //$NON-NLS-1$
        md.append("- **Objects:** `").append(String.join("`, `", fqnList)).append("`\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (SiriusCheckEnabler.isDisableProjectChecksPropertySet())
        {
            md.append("- **Targeted checks:** temporarily enabled over `-DdisableProjectChecks=true`\n"); //$NON-NLS-1$
        }
        md.append("- **ERRORS:** ").append(errorRows.size()) //$NON-NLS-1$
            .append(" | **Warnings:** ").append(warningRows.size()); //$NON-NLS-1$
        int noisyTotal = hiddenNoisyCounts.values().stream().mapToInt(Integer::intValue).sum();
        if (noisyTotal > 0)
        {
            md.append(" | **Hidden noisy:** ").append(noisyTotal); //$NON-NLS-1$
        }
        if (hiddenOutsideDiff[0] > 0)
        {
            md.append(" | **Hidden outside diff:** ").append(hiddenOutsideDiff[0]); //$NON-NLS-1$
        }
        md.append("\n\n"); //$NON-NLS-1$

        if (errorRows.isEmpty() && warningRows.isEmpty())
        {
            md.append("OK: no errors or significant warnings in changed objects.\n"); //$NON-NLS-1$
        }

        if (!errorRows.isEmpty())
        {
            md.append("## ERRORS (").append(errorRows.size()).append(")\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            appendCompactTable(md, errorRows, errorRows.size());
            md.append("\n"); //$NON-NLS-1$
        }

        if (!warningRows.isEmpty())
        {
            int showCount = Math.min(limit, warningRows.size());
            md.append("## Warnings (").append(showCount); //$NON-NLS-1$
            if (showCount < warningRows.size())
            {
                md.append(" of ").append(warningRows.size()); //$NON-NLS-1$
            }
            md.append(")\n\n"); //$NON-NLS-1$
            appendCompactTable(md, warningRows, showCount);
            md.append("\n"); //$NON-NLS-1$
        }

        if (!hiddenNoisyCounts.isEmpty())
        {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> e : hiddenNoisyCounts.entrySet())
            {
                parts.add("`" + e.getKey() + "`=" + e.getValue()); //$NON-NLS-1$ //$NON-NLS-2$
            }
            md.append("> Hidden noisy checks (").append(noisyTotal).append("): ") //$NON-NLS-1$ //$NON-NLS-2$
                .append(String.join(", ", parts)) //$NON-NLS-1$
                .append(". Pass `includeNoisy=true` to show.\n"); //$NON-NLS-1$
        }

        return md.toString();
    }

    private static void appendCompactTable(StringBuilder md, List<RowWithSeverity> rows, int maxRows)
    {
        md.append(MarkdownUtils.tableHeader("Sev", "Module / Location", "Line", "Check", "Message")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        int count = Math.min(maxRows, rows.size());
        for (int i = 0; i < count; i++)
        {
            RowWithSeverity item = rows.get(i);
            MarkerRows.Row r = item.row;
            String loc = (r.modulePath != null && !r.modulePath.isEmpty()) ? r.modulePath
                : (r.objectPresentation != null ? r.objectPresentation : ""); //$NON-NLS-1$
            String lineStr = r.line != null ? r.line.toString() : ""; //$NON-NLS-1$
            if (item.onChangedLine && !lineStr.isEmpty())
            {
                lineStr = lineStr + "*"; //$NON-NLS-1$
            }
            String cid = (r.checkId != null && !r.checkId.isEmpty()) ? r.checkId
                : (r.checkCode != null ? r.checkCode : ""); //$NON-NLS-1$
            String msg = r.message != null ? r.message.replace('\n', ' ').replace('\r', ' ').trim() : ""; //$NON-NLS-1$
            if (msg.length() > 180)
            {
                msg = msg.substring(0, 177) + "..."; //$NON-NLS-1$
            }
            md.append(MarkdownUtils.tableRow(item.severity.name(), loc, lineStr, "`" + cid + "`", msg)); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static boolean isOnChangedLine(MarkerRows.Row row, Map<String, Set<Integer>> changedLinesByModule)
    {
        if (row.line == null || row.modulePath == null || changedLinesByModule.isEmpty())
        {
            return false;
        }
        String norm = normalizeKey(row.modulePath);
        Set<Integer> lines = changedLinesByModule.get(norm);
        return lines != null && lines.contains(row.line);
    }

    private static String normalizeKey(String path)
    {
        String s = path.replace('\\', '/');
        if (s.startsWith("src/")) //$NON-NLS-1$
        {
            s = s.substring(4);
        }
        return s.toLowerCase(Locale.ROOT);
    }

    static String srcPathToFqn(String relFromSrc)
    {
        String[] parts = relFromSrc.replace('\\', '/').split("/"); //$NON-NLS-1$
        if (parts.length == 0)
        {
            return null;
        }
        if ("Configuration".equals(parts[0])) //$NON-NLS-1$
        {
            return "Configuration"; //$NON-NLS-1$
        }
        if (parts.length >= 2)
        {
            String singular = DIR_TO_TYPE.get(parts[0]);
            if (singular != null && !parts[1].isEmpty())
            {
                return singular + "." + parts[1]; //$NON-NLS-1$
            }
        }
        return null;
    }

    private static File findGitRoot(File dir) throws IOException, InterruptedException
    {
        List<String> out = runGit(dir, "rev-parse", "--show-toplevel"); //$NON-NLS-1$ //$NON-NLS-2$
        if (out.isEmpty())
        {
            return null;
        }
        File f = new File(out.get(0).trim());
        return f.isDirectory() ? f : null;
    }

    private static Set<String> collectChangedPaths(File gitRoot, String projPrefix, String gitRef)
        throws IOException, InterruptedException
    {
        Set<String> result = new LinkedHashSet<>();
        String pathSpec = projPrefix.isEmpty() ? "." : projPrefix; //$NON-NLS-1$
        if (gitRef != null && !gitRef.isEmpty())
        {
            for (String line : runGit(gitRoot, "-c", "core.quotepath=false", "diff", "--name-only", gitRef, "--", pathSpec)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            {
                String p = line.trim().replace('\\', '/');
                if (!p.isEmpty())
                {
                    result.add(p);
                }
            }
            // Include untracked files too
            for (String line : runGit(gitRoot, "-c", "core.quotepath=false", "ls-files", "--others", "--exclude-standard", "--", pathSpec)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            {
                String p = line.trim().replace('\\', '/');
                if (!p.isEmpty())
                {
                    result.add(p);
                }
            }
        }
        else
        {
            for (String line : runGit(gitRoot, "-c", "core.quotepath=false", "status", "--porcelain", "--", pathSpec)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            {
                if (line.length() < 4)
                {
                    continue;
                }
                String status = line.substring(0, 2);
                if (status.contains("D")) //$NON-NLS-1$
                {
                    continue;
                }
                String pathPart = line.substring(3).trim();
                int arrow = pathPart.indexOf(" -> "); //$NON-NLS-1$
                if (arrow >= 0)
                {
                    pathPart = pathPart.substring(arrow + 4).trim();
                }
                if (pathPart.startsWith("\"") && pathPart.endsWith("\"") && pathPart.length() >= 2) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    pathPart = pathPart.substring(1, pathPart.length() - 1);
                }
                result.add(pathPart.replace('\\', '/'));
            }
        }
        return result;
    }

    private static Set<String> collectAddedPaths(File gitRoot, String projPrefix, String gitRef)
        throws IOException, InterruptedException
    {
        Set<String> result = new LinkedHashSet<>();
        String pathSpec = projPrefix.isEmpty() ? "." : projPrefix; //$NON-NLS-1$
        if (gitRef != null && !gitRef.isEmpty())
        {
            for (String line : runGit(gitRoot, "-c", "core.quotepath=false", "diff", "--diff-filter=A", "--name-only", gitRef, "--", pathSpec)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            {
                String p = line.trim().replace('\\', '/');
                if (!p.isEmpty())
                {
                    result.add(p);
                }
            }
            for (String line : runGit(gitRoot, "-c", "core.quotepath=false", "ls-files", "--others", "--exclude-standard", "--", pathSpec)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            {
                String p = line.trim().replace('\\', '/');
                if (!p.isEmpty())
                {
                    result.add(p);
                }
            }
        }
        else
        {
            for (String line : runGit(gitRoot, "-c", "core.quotepath=false", "status", "--porcelain", "--", pathSpec)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            {
                if (line.length() < 4)
                {
                    continue;
                }
                String st = line.substring(0, 2);
                if (!st.contains("A") && !st.contains("?")) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    continue;
                }
                String pathPart = line.substring(3).trim();
                if (pathPart.startsWith("\"") && pathPart.endsWith("\"") && pathPart.length() >= 2) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    pathPart = pathPart.substring(1, pathPart.length() - 1);
                }
                result.add(pathPart.replace('\\', '/'));
            }
        }
        return result;
    }

    private static Map<String, Set<Integer>> collectChangedBslLines(File gitRoot, String projPrefix, String gitRef)
        throws IOException, InterruptedException
    {
        Map<String, Set<Integer>> map = new HashMap<>();
        String pathSpec = projPrefix.isEmpty() ? "." : projPrefix; //$NON-NLS-1$
        List<String> args = new ArrayList<>();
        args.add("-c"); //$NON-NLS-1$
        args.add("core.quotepath=false"); //$NON-NLS-1$
        args.add("diff"); //$NON-NLS-1$
        args.add("-U0"); //$NON-NLS-1$
        args.add(gitRef != null && !gitRef.isEmpty() ? gitRef : "HEAD"); //$NON-NLS-1$
        args.add("--"); //$NON-NLS-1$
        args.add(pathSpec);

        List<String> lines = runGit(gitRoot, args.toArray(new String[0]));
        String currentKey = null;
        for (String line : lines)
        {
            if (line.startsWith("+++ b/")) //$NON-NLS-1$
            {
                String repoRel = line.substring(6).trim().replace('\\', '/');
                String inside = projPrefix.isEmpty() ? repoRel
                    : (repoRel.startsWith(projPrefix) ? repoRel.substring(projPrefix.length()) : null);
                if (inside != null && inside.toLowerCase(Locale.ROOT).endsWith(".bsl")) //$NON-NLS-1$
                {
                    currentKey = normalizeKey(inside);
                }
                else
                {
                    currentKey = null;
                }
            }
            else if (currentKey != null && line.startsWith("@@ ")) //$NON-NLS-1$
            {
                Matcher m = HUNK_HEADER.matcher(line);
                if (m.find())
                {
                    int start = Integer.parseInt(m.group(1));
                    int count = m.group(2) != null ? Integer.parseInt(m.group(2)) : 1;
                    if (count > 0)
                    {
                        Set<Integer> set = map.computeIfAbsent(currentKey, k -> new HashSet<>());
                        for (int ln = start; ln < start + count; ln++)
                        {
                            set.add(ln);
                        }
                    }
                }
            }
        }
        return map;
    }

    private static List<String> runGit(File workingDir, String... gitArgs) throws IOException, InterruptedException
    {
        List<String> cmd = new ArrayList<>();
        cmd.add("git"); //$NON-NLS-1$
        Collections.addAll(cmd, gitArgs);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workingDir);
        pb.redirectErrorStream(false);
        Process p = pb.start();

        List<String> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = br.readLine()) != null)
            {
                out.add(line);
            }
        }
        if (!p.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        {
            p.destroyForcibly();
            throw new IOException("git command timed out after " + GIT_TIMEOUT_SECONDS + "s"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return out;
    }

    private static final class RowWithSeverity
    {
        final MarkerRows.Row row;
        final MarkerSeverity severity;
        final boolean onChangedLine;

        RowWithSeverity(MarkerRows.Row row, MarkerSeverity severity, boolean onChangedLine)
        {
            this.row = row;
            this.severity = severity;
            this.onChangedLine = onChangedLine;
        }
    }
}
