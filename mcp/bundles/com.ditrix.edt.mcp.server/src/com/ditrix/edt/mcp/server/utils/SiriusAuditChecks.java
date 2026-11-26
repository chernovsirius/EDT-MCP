/**
 * MCP Server for EDT - Sirius Fork
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.ditrix.edt.mcp.server.Activator;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Project-agnostic 1C/EDT static audit checks executed by {@code check_changed}:
 * <ol>
 *   <li>Missing role rights for newly added metadata objects and HTTP service methods (scans all Roles in the project).</li>
 *   <li>Dangling metadata references in edited {@code Rights.rights} and {@code Configuration.mdo}.</li>
 *   <li>{@code &ИзменениеИКонтроль} / {@code &ChangeAndValidate} intercept verification between base config and extensions in the workspace.</li>
 *   <li>Fast BSL checks EDT misses: unpaired {@code SetPrivilegedMode(True)}, shadowing of global metadata managers.</li>
 *   <li>Managed form check: single-page {@code Pages} group with {@code pagesRepresentation=Auto}.</li>
 *   <li>Optional external {@code bsl-language-server} invocation when {@code -Dedt.mcp.bslLsJar} or {@code BSL_LS_JAR} is configured.</li>
 * </ol>
 */
public final class SiriusAuditChecks
{
    public static final String PROP_BSL_LS_JAR = "edt.mcp.bslLsJar"; //$NON-NLS-1$
    public static final String ENV_BSL_LS_JAR = "BSL_LS_JAR"; //$NON-NLS-1$
    public static final String PROP_BSL_LS_CONFIG = "edt.mcp.bslLsConfig"; //$NON-NLS-1$
    public static final String ENV_BSL_LS_CONFIG = "BSL_LS_CONFIG"; //$NON-NLS-1$

    /** One universal audit finding. */
    public static final class Finding
    {
        public final String severity; // "ERROR" or "WARNING"
        public final String location;
        public final Integer line;
        public final String checkId;
        public final String message;

        public Finding(String severity, String location, Integer line, String checkId, String message)
        {
            this.severity = severity;
            this.location = location;
            this.line = line;
            this.checkId = checkId;
            this.message = message;
        }
    }

    /** Singular metadata type -> EDT src/ plural folder name. */
    private static final Map<String, String> TYPE_TO_DIR = buildTypeToDirMap();

    /** Metadata types that normally require explicit role rights when newly created. */
    private static final Set<String> ROLE_REQUIRING_TYPES = Set.of(
        "Catalog", "Document", "DocumentJournal", "Report", "DataProcessor", "Constant", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        "InformationRegister", "AccumulationRegister", "AccountingRegister", "CalculationRegister", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        "ChartOfAccounts", "ChartOfCharacteristicTypes", "ChartOfCalculationTypes", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        "ExchangePlan", "BusinessProcess", "Task", "HTTPService", "WebService", "IntegrationService" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    );

    private static final Pattern METHOD_START = Pattern.compile(
        "^\\s*(?:(?:Асинх|Async)\\s+)?(Процедура|Функция|Procedure|Function)\\s+([\\wА-Яа-яЁё]+)\\s*\\(", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern METHOD_END = Pattern.compile(
        "^\\s*(?:КонецПроцедуры|КонецФункции|EndProcedure|EndFunction)\\b", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern INTERCEPT = Pattern.compile(
        "^\\s*&(?:ИзменениеИКонтроль|ChangeAndValidate)\\s*\\(\\s*\"([^\"]+)\"\\s*\\)", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern PRIV_TRUE = Pattern.compile(
        "(?:УстановитьПривилегированныйРежим|SetPrivilegedMode)\\s*\\(\\s*(?:Истина|True)\\s*\\)", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern PRIV_FALSE = Pattern.compile(
        "(?:УстановитьПривилегированныйРежим|SetPrivilegedMode)\\s*\\(\\s*(?:Ложь|False)\\s*\\)", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern GLOBAL_SHADOW = Pattern.compile(
        "^\\s*(?:Перем\\s+|Var\\s+)?(Документы|Справочники|РегистрыСведений|РегистрыНакопления|РегистрыБухгалтерии|РегистрыРасчета|Обработки|Отчеты|Перечисления|Константы|ПланыОбмена|БизнесПроцессы|Задачи|ПланыСчетов|ПланыВидовХарактеристик|ПланыВидовРасчета|ЖурналыДокументов|Documents|Catalogs|InformationRegisters|AccumulationRegisters|AccountingRegisters|CalculationRegisters|DataProcessors|Reports|Enums|Constants|ExchangePlans|BusinessProcesses|Tasks)\\s*(?:=(?!=)|;|,|$)", //$NON-NLS-1$
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern BASE_PROJECT_PMF = Pattern.compile("^\\s*Base-Project\\s*:\\s*(.+)\\s*$"); //$NON-NLS-1$

    private SiriusAuditChecks()
    {
        // Utility class
    }

    private static Map<String, String> buildTypeToDirMap()
    {
        Map<String, String> m = new HashMap<>();
        m.put("CommonModule", "CommonModules"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Catalog", "Catalogs"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Document", "Documents"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("DocumentJournal", "DocumentJournals"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("DocumentNumerator", "DocumentNumerators"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Enum", "Enums"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Report", "Reports"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("DataProcessor", "DataProcessors"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Constant", "Constants"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("InformationRegister", "InformationRegisters"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("AccumulationRegister", "AccumulationRegisters"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("AccountingRegister", "AccountingRegisters"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("CalculationRegister", "CalculationRegisters"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ChartOfAccounts", "ChartsOfAccounts"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ChartOfCharacteristicTypes", "ChartsOfCharacteristicTypes"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ChartOfCalculationTypes", "ChartsOfCalculationTypes"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ExchangePlan", "ExchangePlans"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("BusinessProcess", "BusinessProcesses"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Task", "Tasks"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("HTTPService", "HTTPServices"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("WebService", "WebServices"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("WSReference", "WSReferences"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("XDTOPackage", "XDTOPackages"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Role", "Roles"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Subsystem", "Subsystems"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("CommonForm", "CommonForms"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("CommonTemplate", "CommonTemplates"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("CommonCommand", "CommonCommands"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("CommonAttribute", "CommonAttributes"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("CommonPicture", "CommonPictures"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("CommandGroup", "CommandGroups"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("SessionParameter", "SessionParameters"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("FunctionalOption", "FunctionalOptions"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("FunctionalOptionsParameter", "FunctionalOptionsParameters"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("DefinedType", "DefinedTypes"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("EventSubscription", "EventSubscriptions"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ScheduledJob", "ScheduledJobs"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("FilterCriterion", "FilterCriteria"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("SettingsStorage", "SettingsStorages"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Sequence", "Sequences"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("ExternalDataSource", "ExternalDataSources"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("IntegrationService", "IntegrationServices"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Bot", "Bots"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Style", "Styles"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("StyleItem", "StyleItems"); //$NON-NLS-1$ //$NON-NLS-2$
        m.put("Language", "Languages"); //$NON-NLS-1$ //$NON-NLS-2$
        return Collections.unmodifiableMap(m);
    }

    /**
     * Runs all project-agnostic audit checks for the changed files of {@code project}.
     *
     * @param project EDT project being checked
     * @param srcRelPaths relative paths under {@code src/} of all changed files
     * @param addedSrcRelPaths subset of {@code srcRelPaths} representing newly added files
     * @param addedFqns newly added top-level FQNs (optional)
     * @param runBslLs whether to invoke external bsl-language-server when configured
     * @return list of audit findings (never null)
     */
    public static List<Finding> runAll(IProject project, List<String> srcRelPaths,
        Set<String> addedSrcRelPaths, Set<String> addedFqns, boolean runBslLs)
    {
        List<Finding> findings = new ArrayList<>();
        if (project == null || project.getLocation() == null)
        {
            return findings;
        }
        Path projectDir = project.getLocation().toFile().toPath();
        Path srcDir = projectDir.resolve("src"); //$NON-NLS-1$
        if (!Files.isDirectory(srcDir))
        {
            return findings;
        }

        IProject baseProject = findBaseProject(project);
        Path baseSrcDir = (baseProject != null && baseProject.getLocation() != null)
            ? baseProject.getLocation().toFile().toPath().resolve("src") : null; //$NON-NLS-1$

        // 1. Role rights for newly added objects & edited HTTPServices
        checkMissingRoleRights(srcDir, srcRelPaths, addedFqns, findings);

        // 2. Dangling references in edited Rights.rights and Configuration.mdo
        checkDanglingReferences(srcDir, baseSrcDir, srcRelPaths, findings);

        // 3. &ИзменениеИКонтроль / &ChangeAndValidate intercept verification
        checkIntercepts(project, srcDir, baseProject, baseSrcDir, srcRelPaths, findings);

        // 4. Universal BSL static checks (unpaired privileged mode, global manager shadowing)
        List<Path> changedBslFiles = new ArrayList<>();
        for (String rel : srcRelPaths)
        {
            Path file = srcDir.resolve(rel);
            if (!Files.isRegularFile(file))
            {
                continue;
            }
            String lower = rel.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".bsl")) //$NON-NLS-1$
            {
                changedBslFiles.add(file);
                checkBslFile(file, rel, findings);
            }
            else if (lower.endsWith(".form")) //$NON-NLS-1$
            {
                checkFormSinglePage(file, rel, findings);
            }
        }

        // 5. Optional external bsl-language-server
        if (runBslLs && !changedBslFiles.isEmpty())
        {
            runExternalBslLs(projectDir, srcDir, changedBslFiles, findings);
        }

        return findings;
    }

    // -------------------------------------------------------------------------
    // 1. Missing role rights (universal: checks across ALL roles of the project)
    // -------------------------------------------------------------------------

    private static void checkMissingRoleRights(Path srcDir, List<String> srcRelPaths,
        Set<String> addedFqns, List<Finding> findings)
    {
        Path rolesDir = srcDir.resolve("Roles"); //$NON-NLS-1$
        if (!Files.isDirectory(rolesDir))
        {
            return; // Project defines no roles (e.g. pure code library)
        }

        boolean hasNewRoleObjects = false;
        if (addedFqns != null)
        {
            for (String fqn : addedFqns)
            {
                int dot = fqn.indexOf('.');
                if (dot > 0 && ROLE_REQUIRING_TYPES.contains(fqn.substring(0, dot)))
                {
                    hasNewRoleObjects = true;
                    break;
                }
            }
        }

        Set<String> editedHttpServices = new LinkedHashSet<>();
        for (String rel : srcRelPaths)
        {
            String norm = rel.replace('\\', '/');
            if (norm.startsWith("HTTPServices/") && norm.endsWith(".mdo")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                String[] parts = norm.split("/"); //$NON-NLS-1$
                if (parts.length >= 2)
                {
                    editedHttpServices.add(parts[1]);
                }
            }
        }

        if (!hasNewRoleObjects && editedHttpServices.isEmpty())
        {
            return;
        }

        Set<String> roleObjectNames = collectAllRoleObjectNames(rolesDir);

        // Check newly added metadata objects
        if (addedFqns != null)
        {
            for (String fqn : addedFqns)
            {
                int dot = fqn.indexOf('.');
                if (dot <= 0)
                {
                    continue;
                }
                String type = fqn.substring(0, dot);
                if (!ROLE_REQUIRING_TYPES.contains(type))
                {
                    continue;
                }
                // Adopted objects in extensions have <objectBelonging>Adopted</objectBelonging> - skip
                String dir = TYPE_TO_DIR.get(type);
                String name = fqn.substring(dot + 1);
                if (dir != null && isAdoptedMdo(srcDir.resolve(dir).resolve(name).resolve(name + ".mdo"))) //$NON-NLS-1$
                {
                    continue;
                }
                if (!roleObjectNames.contains(fqn))
                {
                    findings.add(new Finding("WARNING", fqn, null, "missing-role-rights", //$NON-NLS-1$ //$NON-NLS-2$
                        "New object '" + fqn + "' is not granted in any project role (src/Roles/*/Rights.rights).")); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }

        // Check every URLTemplate/Method of edited HTTPServices
        for (String serviceName : editedHttpServices)
        {
            Path mdo = srcDir.resolve("HTTPServices").resolve(serviceName).resolve(serviceName + ".mdo"); //$NON-NLS-1$ //$NON-NLS-2$
            if (!Files.isRegularFile(mdo))
            {
                continue;
            }
            String serviceFqn = "HTTPService." + serviceName; //$NON-NLS-1$
            List<String> methodFqns = parseHttpServiceMethods(mdo, serviceName);
            for (String methodFqn : methodFqns)
            {
                if (!roleObjectNames.contains(methodFqn) && !roleObjectNames.contains(serviceFqn))
                {
                    findings.add(new Finding("WARNING", "HTTPServices/" + serviceName + "/" + serviceName + ".mdo", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                        null, "missing-role-rights", //$NON-NLS-1$
                        "HTTP method '" + methodFqn + "' has no Use right in any project role (will return 403).")); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
    }

    private static Set<String> collectAllRoleObjectNames(Path rolesDir)
    {
        Set<String> names = new HashSet<>();
        try (Stream<Path> walk = Files.walk(rolesDir, 3))
        {
            walk.filter(p -> p.getFileName().toString().equalsIgnoreCase("Rights.rights")) //$NON-NLS-1$
                .forEach(rightsFile -> {
                    for (String name : parseRightsObjectNames(rightsFile))
                    {
                        names.add(name);
                    }
                });
        }
        catch (IOException e)
        {
            Activator.logError("SiriusAuditChecks: failed scanning roles", e); //$NON-NLS-1$
        }
        return names;
    }

    private static List<String> parseRightsObjectNames(Path rightsFile)
    {
        List<String> out = new ArrayList<>();
        try
        {
            Document doc = parseXml(rightsFile);
            NodeList objects = doc.getElementsByTagName("object"); //$NON-NLS-1$
            for (int i = 0; i < objects.getLength(); i++)
            {
                Node node = objects.item(i);
                if (!(node instanceof Element))
                {
                    continue;
                }
                String objName = directChildText((Element) node, "name"); //$NON-NLS-1$
                if (objName != null && !objName.isEmpty())
                {
                    out.add(objName.trim());
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError("SiriusAuditChecks: failed parsing " + rightsFile, e); //$NON-NLS-1$
        }
        return out;
    }

    private static List<String> parseHttpServiceMethods(Path mdoFile, String serviceName)
    {
        List<String> out = new ArrayList<>();
        try
        {
            Document doc = parseXml(mdoFile);
            NodeList templates = doc.getElementsByTagName("urlTemplates"); //$NON-NLS-1$
            for (int i = 0; i < templates.getLength(); i++)
            {
                Node tNode = templates.item(i);
                if (!(tNode instanceof Element))
                {
                    continue;
                }
                Element tEl = (Element) tNode;
                String tName = directChildText(tEl, "name"); //$NON-NLS-1$
                if (tName == null || tName.isEmpty())
                {
                    continue;
                }
                NodeList children = tEl.getChildNodes();
                for (int j = 0; j < children.getLength(); j++)
                {
                    Node mNode = children.item(j);
                    if (mNode instanceof Element && "methods".equals(localName(mNode))) //$NON-NLS-1$
                    {
                        String mName = directChildText((Element) mNode, "name"); //$NON-NLS-1$
                        if (mName != null && !mName.isEmpty())
                        {
                            out.add("HTTPService." + serviceName + ".URLTemplate." + tName + ".Method." + mName); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        }
                    }
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError("SiriusAuditChecks: failed parsing HTTPService " + mdoFile, e); //$NON-NLS-1$
        }
        return out;
    }

    private static boolean isAdoptedMdo(Path mdoFile)
    {
        if (!Files.isRegularFile(mdoFile))
        {
            return false;
        }
        try
        {
            String head = Files.readString(mdoFile, StandardCharsets.UTF_8);
            return head.contains("<objectBelonging>Adopted</objectBelonging>"); //$NON-NLS-1$
        }
        catch (IOException e)
        {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // 2. Dangling references in edited Rights.rights and Configuration.mdo
    // -------------------------------------------------------------------------

    private static void checkDanglingReferences(Path srcDir, Path baseSrcDir, List<String> srcRelPaths,
        List<Finding> findings)
    {
        for (String rel : srcRelPaths)
        {
            String norm = rel.replace('\\', '/');
            Path file = srcDir.resolve(norm);
            if (!Files.isRegularFile(file))
            {
                continue;
            }
            if (norm.startsWith("Roles/") && norm.endsWith("Rights.rights")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                for (String objName : parseRightsObjectNames(file))
                {
                    String[] segs = objName.split("\\."); //$NON-NLS-1$
                    if (segs.length < 2)
                    {
                        continue;
                    }
                    String type = segs[0];
                    String name = segs[1];
                    String dir = TYPE_TO_DIR.get(type);
                    if (dir == null)
                    {
                        continue; // Configuration root or special type
                    }
                    if (!objectDirExists(srcDir, baseSrcDir, dir, name))
                    {
                        findings.add(new Finding("ERROR", norm, null, "dangling-role-object", //$NON-NLS-1$ //$NON-NLS-2$
                            "Role references missing metadata object '" + type + "." + name + "' (removed in vendor update?).")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    }
                }
            }
            else if ("Configuration/Configuration.mdo".equals(norm)) //$NON-NLS-1$
            {
                checkConfigurationMdoReferences(file, srcDir, baseSrcDir, norm, findings);
            }
        }
    }

    private static void checkConfigurationMdoReferences(Path configMdo, Path srcDir, Path baseSrcDir,
        String rel, List<Finding> findings)
    {
        try
        {
            Document doc = parseXml(configMdo);
            Element root = doc.getDocumentElement();
            if (root == null)
            {
                return;
            }
            NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++)
            {
                Node n = children.item(i);
                if (!(n instanceof Element))
                {
                    continue;
                }
                String tagLower = localName(n).toLowerCase(Locale.ROOT);
                String text = n.getTextContent();
                if (text == null || !text.contains(".")) //$NON-NLS-1$
                {
                    continue;
                }
                String trimmed = text.trim();
                int dot = trimmed.indexOf('.');
                if (dot <= 0 || trimmed.indexOf('.', dot + 1) >= 0)
                {
                    continue;
                }
                String type = trimmed.substring(0, dot);
                String name = trimmed.substring(dot + 1);
                String dir = TYPE_TO_DIR.get(type);
                if (dir == null)
                {
                    continue;
                }
                // Only check top-level collection tags (e.g. <catalogs>Catalog.X),
                // not property references like <defaultLanguage>Language.Русский.
                if (!tagLower.equals(dir.toLowerCase(Locale.ROOT)))
                {
                    continue;
                }
                if (!objectDirExists(srcDir, baseSrcDir, dir, name))
                {
                    findings.add(new Finding("ERROR", rel, null, "dangling-config-object", //$NON-NLS-1$ //$NON-NLS-2$
                        "Configuration.mdo lists '" + trimmed + "', but src/" + dir + "/" + name + " does not exist.")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError("SiriusAuditChecks: failed checking Configuration.mdo", e); //$NON-NLS-1$
        }
    }

    private static boolean objectDirExists(Path srcDir, Path baseSrcDir, String dir, String name)
    {
        if (Files.isDirectory(srcDir.resolve(dir).resolve(name)))
        {
            return true;
        }
        return baseSrcDir != null && Files.isDirectory(baseSrcDir.resolve(dir).resolve(name));
    }

    // -------------------------------------------------------------------------
    // 3. Universal &ИзменениеИКонтроль / &ChangeAndValidate intercept check
    // -------------------------------------------------------------------------

    private static void checkIntercepts(IProject project, Path srcDir, IProject baseProject, Path baseSrcDir,
        List<String> srcRelPaths, List<Finding> findings)
    {
        // Case A: project is an extension -> compare its edited .bsl against baseProject
        if (baseSrcDir != null && Files.isDirectory(baseSrcDir))
        {
            for (String rel : srcRelPaths)
            {
                if (!rel.toLowerCase(Locale.ROOT).endsWith(".bsl")) //$NON-NLS-1$
                {
                    continue;
                }
                Path extFile = srcDir.resolve(rel);
                if (!Files.isRegularFile(extFile))
                {
                    continue;
                }
                verifyExtensionModuleAgainstBase(extFile, baseSrcDir.resolve(rel), rel, findings);
            }
            return;
        }

        // Case B: project is a base configuration -> check any workspace extension that intercepts edited .bsl
        List<IProject> extensions = findExtensionsReferencing(project);
        if (extensions.isEmpty())
        {
            return;
        }
        for (String rel : srcRelPaths)
        {
            if (!rel.toLowerCase(Locale.ROOT).endsWith(".bsl")) //$NON-NLS-1$
            {
                continue;
            }
            Path baseFile = srcDir.resolve(rel);
            if (!Files.isRegularFile(baseFile))
            {
                continue;
            }
            for (IProject extProj : extensions)
            {
                if (extProj.getLocation() == null)
                {
                    continue;
                }
                Path extFile = extProj.getLocation().toFile().toPath().resolve("src").resolve(rel); //$NON-NLS-1$
                if (Files.isRegularFile(extFile))
                {
                    verifyExtensionModuleAgainstBase(extFile, baseFile,
                        "[" + extProj.getName() + "] " + rel, findings); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
    }

    private static void verifyExtensionModuleAgainstBase(Path extFile, Path baseFile, String displayPath,
        List<Finding> findings)
    {
        try
        {
            String extText = Files.readString(extFile, StandardCharsets.UTF_8);
            if (!extText.contains("ИзменениеИКонтроль") && !extText.contains("ChangeAndValidate")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                return;
            }
            List<String> extLines = splitLines(extText);
            String baseText = Files.isRegularFile(baseFile)
                ? Files.readString(baseFile, StandardCharsets.UTF_8) : null;
            List<String> baseLines = baseText != null ? splitLines(baseText) : null;

            for (int i = 0; i < extLines.size(); i++)
            {
                Matcher m = INTERCEPT.matcher(extLines.get(i));
                if (!m.find())
                {
                    continue;
                }
                String targetMethod = m.group(1);
                int extStart = findNextMethodStart(extLines, i + 1);
                if (extStart < 0)
                {
                    continue;
                }
                int reportLine = i + 1;

                if (baseLines == null)
                {
                    findings.add(new Finding("ERROR", displayPath, reportLine, "intercept-mismatch", //$NON-NLS-1$ //$NON-NLS-2$
                        "&ИзменениеИКонтроль(\"" + targetMethod + "\"): base module not found.")); //$NON-NLS-1$ //$NON-NLS-2$
                    continue;
                }

                int baseStart = findMethodByName(baseLines, targetMethod);
                if (baseStart < 0)
                {
                    findings.add(new Finding("ERROR", displayPath, reportLine, "intercept-mismatch", //$NON-NLS-1$ //$NON-NLS-2$
                        "&ИзменениеИКонтроль(\"" + targetMethod + "\"): method not found in base module.")); //$NON-NLS-1$ //$NON-NLS-2$
                    continue;
                }

                String extParams = normalizeParams(extractDeclaration(extLines, extStart));
                String baseParams = normalizeParams(extractDeclaration(baseLines, baseStart));
                if (!extParams.equals(baseParams))
                {
                    findings.add(new Finding("ERROR", displayPath, reportLine, "intercept-mismatch", //$NON-NLS-1$ //$NON-NLS-2$
                        "&ИзменениеИКонтроль(\"" + targetMethod + "\"): parameter list differs from base (" //$NON-NLS-1$ //$NON-NLS-2$
                            + extParams + " vs " + baseParams + ").")); //$NON-NLS-1$ //$NON-NLS-2$
                }

                List<String> expected = normalizeBody(extractMethodBody(baseLines, baseStart));
                List<String> actual = normalizeBody(stripExtensionBlocks(extractMethodBody(extLines, extStart)));
                if (!expected.equals(actual))
                {
                    int diffIdx = firstDiffIndex(expected, actual);
                    String expLine = diffIdx < expected.size() ? expected.get(diffIdx) : "<end>"; //$NON-NLS-1$
                    String actLine = diffIdx < actual.size() ? actual.get(diffIdx) : "<end>"; //$NON-NLS-1$
                    findings.add(new Finding("ERROR", displayPath, reportLine, "intercept-mismatch", //$NON-NLS-1$ //$NON-NLS-2$
                        "&ИзменениеИКонтроль(\"" + targetMethod + "\") differs at stmt #" + (diffIdx + 1) //$NON-NLS-1$ //$NON-NLS-2$
                            + " | base: `" + truncate(expLine, 60) + "` | ext: `" + truncate(actLine, 60) + "`")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
            }
        }
        catch (IOException e)
        {
            Activator.logError("SiriusAuditChecks: failed checking intercepts for " + extFile, e); //$NON-NLS-1$
        }
    }

    private static IProject findBaseProject(IProject project)
    {
        try
        {
            // 1. DT-INF/PROJECT.PMF Base-Project header (standard EDT format)
            if (project.getLocation() != null)
            {
                Path pmf = project.getLocation().toFile().toPath().resolve("DT-INF").resolve("PROJECT.PMF"); //$NON-NLS-1$ //$NON-NLS-2$
                if (Files.isRegularFile(pmf))
                {
                    for (String line : Files.readAllLines(pmf, StandardCharsets.UTF_8))
                    {
                        Matcher m = BASE_PROJECT_PMF.matcher(line);
                        if (m.matches())
                        {
                            String baseName = m.group(1).trim();
                            IProject base = ResourcesPlugin.getWorkspace().getRoot().getProject(baseName);
                            if (base != null && base.exists() && base.isOpen())
                            {
                                return base;
                            }
                        }
                    }
                }
            }
            // 2. Eclipse referenced projects fallback
            IProject[] refs = project.getReferencedProjects();
            for (IProject ref : refs)
            {
                if (ref != null && ref.exists() && ref.isOpen())
                {
                    return ref;
                }
            }
        }
        catch (Exception e)
        {
            // Ignore workspace errors
        }
        return null;
    }

    private static List<IProject> findExtensionsReferencing(IProject baseProject)
    {
        List<IProject> result = new ArrayList<>();
        try
        {
            for (IProject candidate : ResourcesPlugin.getWorkspace().getRoot().getProjects())
            {
                if (!candidate.isOpen() || candidate.equals(baseProject))
                {
                    continue;
                }
                IProject itsBase = findBaseProject(candidate);
                if (baseProject.equals(itsBase))
                {
                    result.add(candidate);
                }
            }
        }
        catch (Exception e)
        {
            // Ignore
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // 4. Universal BSL checks & Managed Form check
    // -------------------------------------------------------------------------

    private static void checkBslFile(Path bslFile, String rel, List<Finding> findings)
    {
        try
        {
            List<String> lines = splitLines(Files.readString(bslFile, StandardCharsets.UTF_8));
            int methodStartLine = -1;
            String currentMethod = null;
            int privTrueLine = -1;
            boolean hasPrivFalse = false;

            for (int i = 0; i < lines.size(); i++)
            {
                String raw = lines.get(i);
                String noComment = stripLineComment(raw);

                Matcher ms = METHOD_START.matcher(noComment);
                if (ms.find())
                {
                    methodStartLine = i + 1;
                    currentMethod = ms.group(2);
                    privTrueLine = -1;
                    hasPrivFalse = false;
                    continue;
                }

                if (METHOD_END.matcher(noComment).find())
                {
                    if (privTrueLine > 0 && !hasPrivFalse)
                    {
                        findings.add(new Finding("WARNING", rel, privTrueLine, "privileged-mode-unpaired", //$NON-NLS-1$ //$NON-NLS-2$
                            "SetPrivilegedMode(True) in '" + currentMethod + "' has no matching SetPrivilegedMode(False).")); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    methodStartLine = -1;
                    currentMethod = null;
                    privTrueLine = -1;
                    hasPrivFalse = false;
                    continue;
                }

                if (methodStartLine > 0)
                {
                    if (PRIV_TRUE.matcher(noComment).find() && privTrueLine < 0)
                    {
                        privTrueLine = i + 1;
                    }
                    if (PRIV_FALSE.matcher(noComment).find())
                    {
                        hasPrivFalse = true;
                    }
                    Matcher sh = GLOBAL_SHADOW.matcher(noComment);
                    if (sh.find())
                    {
                        findings.add(new Finding("WARNING", rel, i + 1, "global-manager-shadowing", //$NON-NLS-1$ //$NON-NLS-2$
                            "Local variable '" + sh.group(1) + "' shadows global metadata manager in '" + currentMethod + "'.")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    }
                }
            }
        }
        catch (IOException e)
        {
            Activator.logError("SiriusAuditChecks: failed scanning BSL " + bslFile, e); //$NON-NLS-1$
        }
    }

    private static void checkFormSinglePage(Path formFile, String rel, List<Finding> findings)
    {
        try
        {
            Document doc = parseXml(formFile);
            NodeList all = doc.getElementsByTagName("*"); //$NON-NLS-1$
            for (int i = 0; i < all.getLength(); i++)
            {
                Node n = all.item(i);
                if (!(n instanceof Element))
                {
                    continue;
                }
                Element el = (Element) n;
                String xsiType = el.getAttribute("xsi:type"); //$NON-NLS-1$
                if (xsiType == null || !xsiType.contains("FormGroup")) //$NON-NLS-1$
                {
                    continue;
                }
                String groupType = directChildText(el, "type"); //$NON-NLS-1$
                if (!"Pages".equals(groupType)) //$NON-NLS-1$
                {
                    continue;
                }
                String pagesRep = "Auto"; //$NON-NLS-1$
                Element extInfo = directChildElement(el, "extInfo"); //$NON-NLS-1$
                if (extInfo != null)
                {
                    String rep = directChildText(extInfo, "pagesRepresentation"); //$NON-NLS-1$
                    if (rep != null && !rep.isEmpty())
                    {
                        pagesRep = rep;
                    }
                }
                if (!"Auto".equals(pagesRep)) //$NON-NLS-1$
                {
                    continue;
                }
                int pageCount = 0;
                String onlyPageName = null;
                NodeList kids = el.getChildNodes();
                for (int j = 0; j < kids.getLength(); j++)
                {
                    Node kid = kids.item(j);
                    if (kid instanceof Element && "items".equals(localName(kid))) //$NON-NLS-1$
                    {
                        if ("Page".equals(directChildText((Element) kid, "type"))) //$NON-NLS-1$ //$NON-NLS-2$
                        {
                            pageCount++;
                            onlyPageName = directChildText((Element) kid, "name"); //$NON-NLS-1$
                        }
                    }
                }
                if (pageCount == 1)
                {
                    String groupName = directChildText(el, "name"); //$NON-NLS-1$
                    findings.add(new Finding("WARNING", rel, null, "form-single-page-auto", //$NON-NLS-1$ //$NON-NLS-2$
                        "Pages group '" + groupName + "' has only 1 Page ('" + onlyPageName //$NON-NLS-1$ //$NON-NLS-2$
                            + "') with pagesRepresentation=Auto; 1C platform hides the tab header.")); //$NON-NLS-1$
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError("SiriusAuditChecks: failed checking form " + formFile, e); //$NON-NLS-1$
        }
    }

    // -------------------------------------------------------------------------
    // 5. Optional external bsl-language-server integration (zero hardcoded paths)
    // -------------------------------------------------------------------------

    private static void runExternalBslLs(Path projectDir, Path srcDir, List<Path> bslFiles, List<Finding> findings)
    {
        String jarPath = System.getProperty(PROP_BSL_LS_JAR);
        if (jarPath == null || jarPath.isBlank())
        {
            jarPath = System.getenv(ENV_BSL_LS_JAR);
        }
        if (jarPath == null || jarPath.isBlank() || !Files.isRegularFile(Path.of(jarPath)))
        {
            findings.add(new Finding("WARNING", "bsl-ls", null, "bsl-ls-not-configured", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "runBslLs=true requested, but neither -Dedt.mcp.bslLsJar nor BSL_LS_JAR points to an existing bsl-ls.jar.")); //$NON-NLS-1$
            return;
        }

        String configPath = System.getProperty(PROP_BSL_LS_CONFIG);
        if (configPath == null || configPath.isBlank())
        {
            configPath = System.getenv(ENV_BSL_LS_CONFIG);
        }
        if (configPath == null || configPath.isBlank())
        {
            configPath = findBslLsConfigUpwards(projectDir);
        }

        Path tempRoot = null;
        try
        {
            tempRoot = Files.createTempDirectory("edt-mcp-bslls-"); //$NON-NLS-1$
            Path tempSrc = tempRoot.resolve("src"); //$NON-NLS-1$
            Path outDir = tempRoot.resolve("out"); //$NON-NLS-1$
            Files.createDirectories(tempSrc);
            Files.createDirectories(outDir);

            for (Path bsl : bslFiles)
            {
                Path rel = srcDir.relativize(bsl);
                Path dst = tempSrc.resolve(rel);
                Files.createDirectories(dst.getParent());
                Files.copy(bsl, dst);
            }

            String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            List<String> cmd = new ArrayList<>();
            cmd.add(javaBin);
            cmd.add("-Dfile.encoding=UTF-8"); //$NON-NLS-1$
            cmd.add("-jar"); //$NON-NLS-1$
            cmd.add(jarPath);
            cmd.add("analyze"); //$NON-NLS-1$
            if (configPath != null && Files.isRegularFile(Path.of(configPath)))
            {
                cmd.add("-c"); //$NON-NLS-1$
                cmd.add(configPath);
            }
            cmd.add("-s"); //$NON-NLS-1$
            cmd.add(tempSrc.toString());
            cmd.add("-r"); //$NON-NLS-1$
            cmd.add("json"); //$NON-NLS-1$
            cmd.add("-o"); //$NON-NLS-1$
            cmd.add(outDir.toString());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)))
            {
                while (br.readLine() != null)
                {
                    // Drain output
                }
            }
            if (!p.waitFor(60, TimeUnit.SECONDS))
            {
                p.destroyForcibly();
                findings.add(new Finding("WARNING", "bsl-ls", null, "bsl-ls-timeout", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "bsl-language-server timed out after 60s.")); //$NON-NLS-1$
                return;
            }

            Path jsonReport = outDir.resolve("bsl-json.json"); //$NON-NLS-1$
            if (!Files.isRegularFile(jsonReport))
            {
                return;
            }
            parseBslLsJson(jsonReport, tempSrc, findings);
        }
        catch (Exception e)
        {
            Activator.logError("SiriusAuditChecks: bsl-ls failed", e); //$NON-NLS-1$
            findings.add(new Finding("WARNING", "bsl-ls", null, "bsl-ls-error", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "bsl-language-server failed: " + e.getMessage())); //$NON-NLS-1$
        }
        finally
        {
            if (tempRoot != null)
            {
                deleteRecursive(tempRoot);
            }
        }
    }

    private static void parseBslLsJson(Path jsonFile, Path tempSrc, List<Finding> findings) throws IOException
    {
        String content = Files.readString(jsonFile, StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(content).getAsJsonObject();
        JsonArray fileInfos = root.getAsJsonArray("fileinfos"); //$NON-NLS-1$
        if (fileInfos == null)
        {
            return;
        }
        for (JsonElement fe : fileInfos)
        {
            JsonObject fi = fe.getAsJsonObject();
            String rawPath = fi.has("path") ? fi.get("path").getAsString() : ""; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            String relPath = rawPath.replace('\\', '/');
            int idx = relPath.indexOf("/src/"); //$NON-NLS-1$
            if (idx >= 0)
            {
                relPath = relPath.substring(idx + 5);
            }
            JsonArray diags = fi.getAsJsonArray("diagnostics"); //$NON-NLS-1$
            if (diags == null)
            {
                continue;
            }
            for (JsonElement de : diags)
            {
                JsonObject d = de.getAsJsonObject();
                String code = d.has("code") ? d.get("code").getAsString() : "bsl-ls"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                String msg = d.has("message") ? d.get("message").getAsString() : ""; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                int sevNum = d.has("severity") ? d.get("severity").getAsInt() : 2; //$NON-NLS-1$
                String sev = sevNum == 1 ? "ERROR" : "WARNING"; //$NON-NLS-1$ //$NON-NLS-2$
                Integer line = null;
                if (d.has("range") && d.getAsJsonObject("range").has("start")) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                {
                    line = d.getAsJsonObject("range").getAsJsonObject("start").get("line").getAsInt() + 1; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                findings.add(new Finding(sev, relPath, line, "bsl-ls:" + code, msg)); //$NON-NLS-1$
            }
        }
    }

    private static String findBslLsConfigUpwards(Path startDir)
    {
        Path cur = startDir;
        for (int i = 0; i < 4 && cur != null; i++)
        {
            Path candidate = cur.resolve(".bsl-language-server.json"); //$NON-NLS-1$
            if (Files.isRegularFile(candidate))
            {
                return candidate.toString();
            }
            cur = cur.getParent();
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Helpers: BSL parsing, XML parsing, Git branch
    // -------------------------------------------------------------------------

    /** Reads the current git branch for {@code project}, or null if unavailable. */
    public static String readGitBranch(IProject project)
    {
        if (project == null)
        {
            return null;
        }
        IPath loc = project.getLocation();
        if (loc == null)
        {
            return null;
        }
        File dir = loc.toFile();
        if (!dir.isDirectory())
        {
            return null;
        }
        try
        {
            ProcessBuilder pb = new ProcessBuilder("git", "branch", "--show-current"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            pb.directory(dir);
            Process p = pb.start();
            String branch;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)))
            {
                branch = br.readLine();
            }
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0 && branch != null && !branch.isBlank())
            {
                return branch.trim();
            }
        }
        catch (Exception e)
        {
            // Ignore
        }
        return null;
    }

    /** Returns true for standard protected/release branch names (project-agnostic). */
    public static boolean isProtectedBranch(String branch)
    {
        if (branch == null || branch.isEmpty())
        {
            return false;
        }
        String b = branch.toLowerCase(Locale.ROOT);
        return "main".equals(b) || "master".equals(b) || "vendor".equals(b) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            || b.startsWith("release/") || b.startsWith("prod"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static List<String> splitLines(String text)
    {
        if (text.startsWith("\uFEFF")) //$NON-NLS-1$
        {
            text = text.substring(1);
        }
        String[] arr = text.split("\\r?\\n", -1); //$NON-NLS-1$
        List<String> list = new ArrayList<>(arr.length);
        Collections.addAll(list, arr);
        return list;
    }

    private static int findNextMethodStart(List<String> lines, int from)
    {
        for (int i = from; i < lines.size(); i++)
        {
            if (METHOD_START.matcher(lines.get(i)).find())
            {
                return i;
            }
        }
        return -1;
    }

    private static int findMethodByName(List<String> lines, String name)
    {
        String target = name.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lines.size(); i++)
        {
            Matcher m = METHOD_START.matcher(lines.get(i));
            if (m.find() && m.group(2).toLowerCase(Locale.ROOT).equals(target))
            {
                return i;
            }
        }
        return -1;
    }

    private static int findDeclarationEnd(List<String> lines, int start)
    {
        int depth = 0;
        for (int i = start; i < lines.size(); i++)
        {
            String s = lines.get(i);
            for (int k = 0; k < s.length(); k++)
            {
                char c = s.charAt(k);
                if (c == '(') depth++;
                else if (c == ')') depth--;
            }
            if (depth <= 0)
            {
                return i;
            }
        }
        return start;
    }

    private static String extractDeclaration(List<String> lines, int start)
    {
        int end = findDeclarationEnd(lines, start);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= end; i++)
        {
            if (i > start) sb.append(' ');
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    private static String normalizeParams(String decl)
    {
        int lp = decl.indexOf('(');
        int rp = decl.lastIndexOf(')');
        if (lp < 0 || rp <= lp)
        {
            return "()"; //$NON-NLS-1$
        }
        String sig = decl.substring(lp, rp + 1).replaceAll("\\s+", "").toLowerCase(Locale.ROOT); //$NON-NLS-1$ //$NON-NLS-2$
        return sig.replaceAll("=[^,)]*", ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static List<String> extractMethodBody(List<String> lines, int start)
    {
        int bodyStart = findDeclarationEnd(lines, start) + 1;
        List<String> body = new ArrayList<>();
        for (int i = bodyStart; i < lines.size(); i++)
        {
            if (METHOD_END.matcher(lines.get(i)).find())
            {
                break;
            }
            body.add(lines.get(i));
        }
        return body;
    }

    private static List<String> stripExtensionBlocks(List<String> body)
    {
        List<String> out = new ArrayList<>();
        boolean inInsert = false;
        for (String line : body)
        {
            String st = line.trim().toLowerCase(Locale.ROOT);
            if (st.startsWith("#вставка") || st.startsWith("#insert")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                inInsert = true;
            }
            else if (st.startsWith("#конецвставки") || st.startsWith("#endinsert")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                inInsert = false;
            }
            else if (st.startsWith("#удаление") || st.startsWith("#конецудаления") //$NON-NLS-1$ //$NON-NLS-2$
                || st.startsWith("#delete") || st.startsWith("#enddelete")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                // Keep lines inside #Удаление, drop only the preprocessor directive itself
            }
            else if (!inInsert)
            {
                out.add(line);
            }
        }
        return out;
    }

    private static List<String> normalizeBody(List<String> lines)
    {
        List<String> out = new ArrayList<>();
        for (String line : lines)
        {
            String s = stripLineComment(line).replaceAll("\\s+", " ").trim(); //$NON-NLS-1$ //$NON-NLS-2$
            if (!s.isEmpty())
            {
                out.add(s);
            }
        }
        return out;
    }

    private static String stripLineComment(String line)
    {
        boolean inStr = false;
        for (int i = 0; i < line.length(); i++)
        {
            char c = line.charAt(i);
            if (c == '"')
            {
                inStr = !inStr;
            }
            else if (!inStr && c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/')
            {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static int firstDiffIndex(List<String> a, List<String> b)
    {
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++)
        {
            if (!a.get(i).equals(b.get(i)))
            {
                return i;
            }
        }
        return n;
    }

    private static String truncate(String s, int max)
    {
        return s.length() > max ? s.substring(0, max - 3) + "..." : s; //$NON-NLS-1$
    }

    private static Document parseXml(Path path) throws Exception
    {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); //$NON-NLS-1$
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(path.toFile());
    }

    private static String localName(Node n)
    {
        return n.getLocalName() != null ? n.getLocalName() : n.getNodeName();
    }

    private static String directChildText(Element parent, String tag)
    {
        Element child = directChildElement(parent, tag);
        return child != null ? child.getTextContent() : null;
    }

    private static Element directChildElement(Element parent, String tag)
    {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++)
        {
            Node k = kids.item(i);
            if (k instanceof Element && tag.equals(localName(k)))
            {
                return (Element) k;
            }
        }
        return null;
    }

    private static void deleteRecursive(Path root)
    {
        try (Stream<Path> walk = Files.walk(root))
        {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try
                {
                    Files.deleteIfExists(p);
                }
                catch (IOException e)
                {
                    // Ignore cleanup error
                }
            });
        }
        catch (IOException e)
        {
            // Ignore
        }
    }
}
