# EDT-MCP — форк Sirius (`chernovsirius/EDT-MCP`, ветка `sirius`)

Форк [DitriXNew/EDT-MCP](https://github.com/DitriXNew/EDT-MCP) на базе релиза `v2.26.1` для 1C:EDT 2026.2.

Принципы форка:
1. **Ноль хардкода под конкретный проект/конфигурацию** — все проверки и предохранители универсальны для любых конфигураций и расширений 1С в EDT.
2. **Минимально-инвазивные хуки** — лишние ~90 Java-классов апстрима физически не удаляются, а отсекаются на этапе регистрации через whitelist. `git merge` новых релизов `upstream` проходит почти без конфликтов.

---

## 1. Реализованные доработки и затронутые файлы

### Новые файлы (конфликтов с апстримом не дают)
| Файл | Назначение |
|---|---|
| `.../tools/SiriusProfile.java` | Whitelist из 24 инструментов (`ENABLED_TOOLS`), оверрайд всех инструментов (`-Dedt.mcp.allTools=true` / `EDT_MCP_ALL_TOOLS=true`), список косметических проверок `DEFAULT_NOISY_CHECKS`. |
| `.../utils/SiriusCheckEnabler.java` | 1) Временное включение check-компьютеров EDT на время точечной валидации по FQN поверх `-DdisableProjectChecks=true`; 2) Универсальное ожидание готовности проекта после старта EDT (`waitProjectReady`). |
| `.../utils/SiriusAuditChecks.java` | Универсальный статический аудит для `check_changed`: права ролей на новые объекты и HTTP-методы, висячие ссылки в `Rights.rights`/`Configuration.mdo`, сверка перехватов `&ИзменениеИКонтроль`/`&ChangeAndValidate` между базовым проектом и расширениями workspace, непарный `УстановитьПривилегированныйРежим(Истина)`, затенение глобальных менеджеров метаданных, одиночные `Page` в `Pages (Auto)`, опциональный запуск внешнего `bsl-language-server`. |
| `.../tools/impl/CheckChangedTool.java` | Инструмент `check_changed`: ожидание готовности → `git status/diff` → FQN → точечный `revalidate` → универсальный аудит → компактный отчёт с отметкой `*` на строках из diff. |

### Точечные хуки в файлах апстрима (7 файлов)
| Файл | Что изменено |
|---|---|
| `.../tools/BuiltInToolRegistrar.java` | Регистрация `new CheckChangedTool()` и фильтрация каталога через `SiriusProfile.filterCatalogue(catalogue)`. |
| `.../tools/impl/RevalidateObjectsTool.java` | Параметр `waitReadySec` (по умолчанию `30`), обёртка точечной валидации в `SiriusCheckEnabler.withTargetedChecksEnabled(...)`, поле `targetedChecksForced` в FrontMatter. |
| `.../tools/impl/GetProjectErrorsTool.java` | Параметры `includeNoisy` (по умолчанию `false`) и `excludeChecks`, пропуск косметических не-критичных проверок в `collectErrors(...)` + строка-сводка `Hidden noisy checks`. |
| `.../tools/impl/UpdateDatabaseTool.java` | Безопасный дефолт `externalInfobaseChanges="cancel"` (вместо `override`), поле `gitBranch` в `PREVIEW`, предупреждения при обновлении с защищённых веток (`main`/`master`/`vendor`/`release/*`/`prod*`) и при `fullUpdate=true`. |
| `.../tools/HostToolCapabilities.java` | Классификация `check_changed` (для ratchet `BootstrapBuild`). |
| `.../tools/Toolsets.java`, `.../preferences/ToolGroup.java`, `ToolPreset.java` | Регистрация `check_changed` в группах/пресетах. |

---

## 2. Состав профиля (24 инструмента вместо 112)

Определён в `SiriusProfile.ENABLED_TOOLS`:

- **Семантика и проверки (8):** `check_changed`, `revalidate_objects`, `get_project_errors`, `validate_query`, `validate_form_model`, `find_references`, `get_method_call_hierarchy`, `get_check_description`.
- **Информационная база EDT (5):** `update_database`, `get_applications`, `get_job_status`, `infobase_sessions`, `infobase_extensions`.
- **Метаданные и 3-way merge (7):** `get_metadata_details`, `create_metadata`, `modify_metadata`, `adopt_metadata_object`, `compare_configurations`, `get_comparison_node`, `merge_rules`.
- **Служебные (4):** `list_projects`, `clean_project`, `edt_dialog`, `get_server_status`.

Вернуть все 112 инструментов апстрима без пересборки: `-Dedt.mcp.allTools=true` или `EDT_MCP_ALL_TOOLS=true`.

---

## 3. Точечные проверки поверх `-DdisableProjectChecks=true` и `waitReadySec`

- При `-DdisableProjectChecks=true` EDT кеширует флаг в `private final Boolean disableProjectChecks` внутри `LanguageCheckDerivedDataComputer`, `ModelCheckDerivedDataComputer` и `MarkerCleanerDerivedDataComputer`. `SiriusCheckEnabler` под `synchronized` включает их только на время точечной валидации переданных FQN и гарантированно возвращает `true` в `finally`.
- Если EDT только что стартовала и проект ещё в `BEFORE_BUILD` / `context not started`, `check_changed` (`waitReadySec=60`) и `revalidate_objects` (`waitReadySec=30`) спокойно ждут готовности вместо мгновенного падения.

---

## 4. Универсальные проверки `check_changed` (`SiriusAuditChecks`)

Все проверки работают на любой конфигурации/расширении без привязки к именам:

| Код | Уровень | Что проверяет |
|---|---|---|
| `missing-role-rights` | WARNING | Новые объекты метаданных (кроме заимствованных `Adopted`) и все `URLTemplate`/`Method` изменённых `HTTPService`, на которые не выдано право ни в одной роли проекта (`src/Roles/*/Rights.rights`). |
| `dangling-role-object` | ERROR | В изменённых `Rights.rights` ссылки на объекты, которых нет ни в проекте, ни в его базовом проекте. |
| `dangling-config-object` | ERROR | В изменённом `Configuration.mdo` ссылки на объекты, каталоги которых отсутствуют в `src/`. |
| `intercept-mismatch` | ERROR | Сверка перехватов `&ИзменениеИКонтроль` / `&ChangeAndValidate` (параметры и тело без `#Вставка` с раскрытым `#Удаление`) между расширением и базовым проектом (работает в обе стороны: при правке расширения и при правке базовой конфигурации). |
| `privileged-mode-unpaired` | WARNING | `УстановитьПривилегированныйРежим(Истина)` / `SetPrivilegedMode(True)` без парного `Ложь`/`False` в том же методе. |
| `global-manager-shadowing` | WARNING | Локальная переменная с именем глобального менеджера (`Документы`, `Справочники`, `РегистрыСведений`, `Documents`, `Catalogs` и т.д.). |
| `form-single-page-auto` | WARNING | В изменённой `.form` группа `Pages` содержит ровно 1 `Page` при `pagesRepresentation=Auto` (платформа скрывает закладку). |
| `bsl-ls:<codeId>` | ERROR/WARNING | Опциональный запуск внешнего `bsl-language-server` (`runBslLs: true`), путь берётся из `-Dedt.mcp.bslLsJar` или `BSL_LS_JAR`, конфиг — из `-Dedt.mcp.bslLsConfig` / `BSL_LS_CONFIG` или автопоиском `.bsl-language-server.json` вверх по дереву каталогов. |

---

## 5. Фильтр шума (`DEFAULT_NOISY_CHECKS`)

Скрывает из таблицы по умолчанию только 6 косметических кодов проверок EDT (`bsl-legacy-check-static-feature-access-for-unknown-left-part`, `module-structure-method-in-regions`, `bsl-variable-name-invalid`, `doc-comment-collection-item-type`, `doc-comment-complex-type-with-link`, `constructor-function-return-section`).
- Количество повторений на скрытие не влияет.
- `ERRORS`, `BLOCKER`, `CRITICAL` не скрываются никогда.
- Скрытые проверки всегда отражаются одной строкой-сводкой внизу отчёта; `includeNoisy: true` показывает их целиком.

---

## 6. Сборка, обновление с апстрима и установка

### Обновление с апстрима
```bash
cd /d/EDT/mcp/EDT-MCP
git fetch upstream --tags
git checkout sirius
git merge v2.XX.Y   # или upstream/master
```

### Сборка p2-архива (JDK 25 + Maven 3.9+)
```bash
cd /d/EDT/mcp/EDT-MCP
./source/compile.sh --skip-tests --version 2.26.1-sirius.2 --output-dir /d/EDT/mcp \
  --java-home '/c/Program Files/BellSoft/LibericaJDK-25-Full' \
  --maven-home /d/tools/maven
```

### Установка в EDT 2026.2 (при закрытой EDT, через консольный `1cedtc.exe`)
```powershell
$edtC = "C:\Users\a.chernov\AppData\Local\1C\1cedtstart\installations\1C_EDT 2026.2\1cedt\1cedtc.exe"
$old = ((& $edtC -nosplash -application org.eclipse.equinox.p2.director -listInstalledRoots 2>&1) | Select-String "com\.ditrix\.edt\.mcp\.server\.feature\.feature\.group/[^\s]+").Matches.Value | Select-Object -First 1
& $edtC -nosplash -application org.eclipse.equinox.p2.director `
  -repository "jar:file:/D:/EDT/mcp/MCP-EDT.v2.26.1-sirius.2.zip!/" `
  -uninstallIU $old `
  -installIU com.ditrix.edt.mcp.server.feature.feature.group
```
