/**
 * MCP Server for EDT - Sirius Fork
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com.ditrix.edt.mcp.server.Activator;

/**
 * Temporarily enables EDT check derived-data computers for targeted object revalidation
 * when EDT is started with {@code -DdisableProjectChecks=true}.
 * <p>
 * EDT 2026.2 caches {@code Boolean.getBoolean("disableProjectChecks")} in a {@code private final Boolean}
 * field on {@code LanguageCheckDerivedDataComputer}, {@code ModelCheckDerivedDataComputer}, and
 * {@code MarkerCleanerDerivedDataComputer}. Leaving that flag {@code true} gives a ~4.9x speedup
 * on project startup and builds; toggling it to {@code Boolean.FALSE} ONLY around a targeted
 * {@code ICheckScheduler.scheduleValidation(...)} + {@code waitForBuildAndDerivedData(...)} call
 * lets on-demand validation of 1..N edited objects run full BSL + model checks and immediately
 * restores {@code Boolean.TRUE} in {@code finally}.
 */
public final class SiriusCheckEnabler
{
    private static final String PROP_DISABLE_PROJECT_CHECKS = "disableProjectChecks"; //$NON-NLS-1$
    private static final String FIELD_WORKER_MANAGER = "workerManager"; //$NON-NLS-1$
    private static final String FIELD_COMPUTERS = "computers"; //$NON-NLS-1$
    private static final String FIELD_DISABLE_CHECKS = "disableProjectChecks"; //$NON-NLS-1$

    /** Functional interface for a checked action executed while targeted checks are enabled. */
    @FunctionalInterface
    public interface CheckedAction
    {
        void run() throws Exception;
    }

    private static final class ToggleRecord
    {
        final Object computer;
        final Field field;
        final Object previousValue;

        ToggleRecord(Object computer, Field field, Object previousValue)
        {
            this.computer = computer;
            this.field = field;
            this.previousValue = previousValue;
        }
    }

    private SiriusCheckEnabler()
    {
        // Utility class
    }

    /** Whether the JVM was launched with {@code -DdisableProjectChecks=true}. */
    public static boolean isDisableProjectChecksPropertySet()
    {
        return Boolean.getBoolean(PROP_DISABLE_PROJECT_CHECKS);
    }

    /**
     * Waits up to {@code waitReadySec} seconds for {@code projectName} to finish initial startup/build
     * (i.e. {@link ProjectStateChecker#buildingErrorOrNull(String)} is {@code null} and EDT has started
     * its {@code IDtProject} context). Returns immediately when already ready or when {@code waitReadySec <= 0}.
     */
    public static void waitProjectReady(String projectName, int waitReadySec)
    {
        if (projectName == null || projectName.isEmpty() || waitReadySec <= 0)
        {
            return;
        }
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists() || !ctx.isOpen())
        {
            return;
        }
        IProject project = ctx.project();
        long deadline = System.currentTimeMillis() + (long) Math.min(waitReadySec, 300) * 1000L;
        while (System.currentTimeMillis() < deadline)
        {
            boolean building = ProjectStateChecker.buildingErrorOrNull(projectName) != null;
            IDtProjectManager dtMgr = Activator.getDefault() != null
                ? Activator.getDefault().getDtProjectManager() : null;
            boolean contextStarted = (dtMgr == null) || (dtMgr.getDtProject(project) != null);
            if (!building && contextStarted)
            {
                return;
            }
            try
            {
                Thread.sleep(2000L);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Executes {@code action} with check derived-data computers temporarily enabled for {@code project}.
     * Synchronized globally so concurrent calls never overwrite each other's restore state.
     *
     * @param project the EDT project being targeted
     * @param action the validation + wait action to run
     * @return number of derived-data computers temporarily enabled (0 if not needed or unavailable)
     * @throws Exception rethrows any exception from {@code action}
     */
    public static synchronized int withTargetedChecksEnabled(IProject project, CheckedAction action) throws Exception
    {
        List<ToggleRecord> toggled = enableCheckComputers(project);
        try
        {
            action.run();
            return toggled.size();
        }
        finally
        {
            restoreCheckComputers(toggled);
        }
    }

    private static List<ToggleRecord> enableCheckComputers(IProject project)
    {
        if (project == null || !isDisableProjectChecksPropertySet())
        {
            return Collections.emptyList();
        }

        IDerivedDataManager ddManager = BuildUtils.resolveDerivedDataManager(project);
        if (ddManager == null)
        {
            return Collections.emptyList();
        }

        List<ToggleRecord> toggled = new ArrayList<>();
        try
        {
            Object workerManager = readField(ddManager, FIELD_WORKER_MANAGER);
            if (workerManager == null)
            {
                return Collections.emptyList();
            }

            Object computersObj = readField(workerManager, FIELD_COMPUTERS);
            if (!(computersObj instanceof Map<?, ?>))
            {
                return Collections.emptyList();
            }

            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            Map<?, ?> outer = (Map<?, ?>) computersObj;
            for (Object innerObj : outer.values())
            {
                if (!(innerObj instanceof Map<?, ?>))
                {
                    continue;
                }
                for (Object computer : ((Map<?, ?>) innerObj).values())
                {
                    if (computer == null || !seen.add(computer))
                    {
                        continue;
                    }
                    Field flagField = findField(computer.getClass(), FIELD_DISABLE_CHECKS);
                    if (flagField == null)
                    {
                        continue;
                    }
                    flagField.setAccessible(true);
                    Object current = flagField.get(computer);
                    if (Boolean.TRUE.equals(current))
                    {
                        flagField.set(computer, Boolean.FALSE);
                        toggled.add(new ToggleRecord(computer, flagField, current));
                    }
                }
            }

            if (!toggled.isEmpty())
            {
                Activator.logInfo("SiriusCheckEnabler: temporarily enabled " + toggled.size() //$NON-NLS-1$
                    + " check computer(s) for targeted validation of " + project.getName()); //$NON-NLS-1$
            }
        }
        catch (Throwable t)
        {
            // Never fail validation if reflection is blocked on a future EDT version:
            // restore anything already toggled and proceed normally.
            restoreCheckComputers(toggled);
            Activator.logError("SiriusCheckEnabler: failed to toggle disableProjectChecks", t); //$NON-NLS-1$
            return Collections.emptyList();
        }
        return toggled;
    }

    private static void restoreCheckComputers(List<ToggleRecord> toggled)
    {
        if (toggled == null || toggled.isEmpty())
        {
            return;
        }
        for (ToggleRecord rec : toggled)
        {
            try
            {
                rec.field.set(rec.computer, rec.previousValue);
            }
            catch (Throwable t)
            {
                Activator.logError("SiriusCheckEnabler: failed to restore disableProjectChecks on " //$NON-NLS-1$
                    + rec.computer.getClass().getSimpleName(), t);
            }
        }
        Activator.logInfo("SiriusCheckEnabler: restored disableProjectChecks=true on " //$NON-NLS-1$
            + toggled.size() + " check computer(s)"); //$NON-NLS-1$
    }

    private static Object readField(Object target, String fieldName) throws IllegalAccessException
    {
        Field f = findField(target.getClass(), fieldName);
        if (f == null)
        {
            return null;
        }
        f.setAccessible(true);
        return f.get(target);
    }

    private static Field findField(Class<?> clazz, String fieldName)
    {
        Class<?> cur = clazz;
        while (cur != null && cur != Object.class)
        {
            try
            {
                return cur.getDeclaredField(fieldName);
            }
            catch (NoSuchFieldException e)
            {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }
}
