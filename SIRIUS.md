# EDT-MCP — форк Sirius (`chernovsirius/EDT-MCP`, ветка `sirius`)

Форк [DitriXNew/EDT-MCP](https://github.com/DitriXNew/EDT-MCP) на базе релиза `v2.26.1` для работы с 1С:ERP в 1C:EDT 2026.2.

Главная архитектурная идея: **минимально-инвазивные хуки**. Лишние ~90 Java-классов апстрима физически не удаляются — они отсекаются на этапе регистрации через whitelist. Благодаря этому `git merge` новых релизов `upstream` проходит почти без конфликтов.

---

## 1. Реализованные доработки и затронутые файлы

### Новые файлы (конфликтов с апстримом не дают)
| Файл | Назначение |
|---|---|
| `mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/tools/SiriusProfile.java` | Whitelist из 20 боевых инструментов (`ENABLED_TOOLS`), рубильник возврата всех инструментов (`-Dedt.mcp.allTools=true` / `EDT_MCP_ALL_TOOLS=true`), список стилистических проверок `DEFAULT_NOISY_CHECKS`. |
| `mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/utils/SiriusCheckEnabler.java` | Временное включение check-компьютеров EDT (`LanguageCheckDerivedDataComputer`, `ModelCheckDerivedDataComputer`, `MarkerCleanerDerivedDataComputer`) на время точечной валидации по FQN, когда EDT запущена с `-DdisableProjectChecks=true`. |
| `mcp/bundles/com.ditrix.edt.mcp.server/src/com/ditrix/edt/mcp/server/tools/impl/CheckChangedTool.java` | Новый инструмент `check_changed`: `git status`/`git diff` → FQN объектов → точечный `revalidate_objects` → компактный отчёт с отметкой `*` у строк из diff. |

### Точечные хуки в файлах апстрима (6 файлов)
| Файл | Что изменено |
|---|---|
| `.../tools/BuiltInToolRegistrar.java` | Добавлена регистрация `new CheckChangedTool()` и фильтрация каталога через `SiriusProfile.filterCatalogue(catalogue)`. |
| `.../tools/impl/RevalidateObjectsTool.java` | В `revalidateSpecificObjects(...)` вызов `checkScheduler.scheduleValidation(...)` + `BuildUtils.waitForBuildAndDerivedData(...)` обёрнут в `SiriusCheckEnabler.withTargetedChecksEnabled(...)`; в FrontMatter добавлено поле `targetedChecksForced`. При полной перепроверке проекта (`objects` пустой) не срабатывает. |
| `.../tools/impl/GetProjectErrorsTool.java` | Добавлены параметры `includeNoisy` (по умолчанию `false`) и `excludeChecks`, `ThreadLocal` `CURRENT_EXCLUDED_CHECKS`, пропуск шумных не-критичных маркеров в `collectErrors(...)` и компактная строка-сводка `Hidden noisy checks (...)` в конце отчёта. |
| `.../tools/HostToolCapabilities.java` | Зарегистрирована запись `check_changed` (требует ratchet `BootstrapBuild`). |
| `.../tools/Toolsets.java` | Инструмент `check_changed` добавлен в toolset `PROJECT`. |
| `.../preferences/ToolGroup.java` и `ToolPreset.java` | Инструмент `check_changed` добавлен в группу `CORE` и в `READ_ONLY_KEEPS_DESPITE_WRITE_HINT`. |

---

## 2. Состав профиля Sirius (20 инструментов вместо 112)

Определён в `SiriusProfile.ENABLED_TOOLS`:

- **Семантика и проверки (8):** `check_changed`, `revalidate_objects`, `get_project_errors`, `validate_query`, `validate_form_model`, `find_references`, `get_method_call_hierarchy`, `get_check_description`.
- **Информационная база EDT (4):** `update_database`, `get_applications`, `infobase_sessions`, `infobase_extensions`.
- **Метаданные и 3-way merge на релизе (4):** `adopt_metadata_object`, `compare_configurations`, `get_comparison_node`, `merge_rules`.
- **Служебные (4):** `list_projects`, `clean_project`, `edt_dialog`, `get_server_status`.

Всё остальное (файловое чтение/запись/поиск, Git-команды, отладчик, профилировщик, YAXUnit, Workmate, переводы, локальные ИБ, standalone-сервер, ЖР/ТЖ) в реестр не попадает и не засоряет контекст ИИ-агента.

Если временно понадобятся все 112 инструментов апстрима — запустить EDT с `-Dedt.mcp.allTools=true` (или задать `EDT_MCP_ALL_TOOLS=true`).

---

## 3. Точечные проверки поверх `-DdisableProjectChecks=true`

При запуске EDT с `-DdisableProjectChecks=true` (ускорение сборки ~4.9×) EDT кеширует это значение в поле `private final Boolean disableProjectChecks` внутри трёх derived-data компьютеров:
- `LanguageCheckDerivedDataComputer`
- `ModelCheckDerivedDataComputer`
- `MarkerCleanerDerivedDataComputer`

При вызове `revalidate_objects` (с непустым списком `objects`) или `check_changed` класс `SiriusCheckEnabler`:
1. Под глобальным `synchronized` находит через `IDerivedDataManager → workerManager → computers` все уникальные check-компьютеры проекта (обычно 6 инстансов), где `disableProjectChecks == Boolean.TRUE`.
2. Временно переводит их в `Boolean.FALSE`.
3. Выполняет `ICheckScheduler.scheduleValidation(...)` только по запрошенным FQN и ждёт `waitForBuildAndDerivedData(...)`.
4. В блоке `finally` гарантированно возвращает `Boolean.TRUE`.

В логе `.metadata/.log` это видно строками `SiriusCheckEnabler: temporarily enabled 6 check computer(s)...` и `restored disableProjectChecks=true`.

---

## 4. Фильтр шума (`DEFAULT_NOISY_CHECKS`)

Фильтр **не зависит от количества повторений ошибки**. Он скрывает только 6 конкретных кодов стилистических предупреждений, которые массово срабатывают в типовом коде 1С:ERP:

1. `bsl-legacy-check-static-feature-access-for-unknown-left-part` — не удалось статически вывести тип слева от точки (например, параметр без аннотации типа);
2. `module-structure-method-in-regions` — метод вне области `#Область`;
3. `bsl-variable-name-invalid` — короткие имена переменных (`i`, `ТЗ`, `Стр` и т.п.);
4. `doc-comment-collection-item-type` — не указан тип элементов коллекции в документирующем комментарии;
5. `doc-comment-complex-type-with-link` — сложный тип в doc-комментарии без ссылки;
6. `constructor-function-return-section` — отсутствует секция `Возвращаемое значение:` в конструкторе.

**Правила фильтра:**
- `ERRORS`, `BLOCKER`, `CRITICAL` **никогда не скрываются**, независимо от кода проверки и количества срабатываний.
- Любые другие предупреждения (неиспользуемые методы, ошибки форм, запросы, свойства полей и т.д.) тоже всегда видны.
- Количество скрытых шумных предупреждений всегда выводится одной строкой внизу отчёта с разбивкой по кодам; передача `includeNoisy: true` показывает их в таблице полностью.

---

## 5. Инструмент `check_changed`

Параметры:
- `projectName` *(обязательный)* — имя проекта EDT (`srs-erp`, `srs-erp.СРС_Доработки`).
- `gitRef` *(опциональный)* — база для сравнения (например `HEAD~1`, `main`). Если не указан — проверяет все незакоммиченные изменения рабочего дерева (staged + unstaged + untracked).
- `onlyChangedLines` *(по умолчанию `false`)* — при `true` скрывает предупреждения вне строк `git diff` (`ERRORS` показываются всегда).
- `includeNoisy` *(по умолчанию `false`)* — показать шумные проверки из `DEFAULT_NOISY_CHECKS`.
- `limit` *(по умолчанию `30`, макс. `200`)* — лимит не-критичных предупреждений (`ERRORS` выводятся все).

В колонке `Line` звёздочкой (`15*`) помечаются строки, которые реально были изменены в `git diff`, и такие предупреждения сортируются в начало списка.

---

## 6. Сборка, обновление с апстрима и установка

### Требования на машине
- **JDK 25:** `C:\Program Files\BellSoft\LibericaJDK-25-Full`
- **Maven 3.9+:** `D:\tools\maven`
- **Git Bash:** `C:\Program Files\Git\bin\bash.exe`

### Обновление на новый релиз апстрима
```bash
cd /d/EDT/mcp/EDT-MCP
git fetch upstream --tags
git checkout sirius
git merge v2.XX.Y   # или upstream/master
```

### Сборка p2-архива
```bash
cd /d/EDT/mcp/EDT-MCP
./source/compile.sh --skip-tests --version 2.26.1-sirius --output-dir /d/EDT/mcp \
  --java-home '/c/Program Files/BellSoft/LibericaJDK-25-Full' \
  --maven-home /d/tools/maven
```

### Установка в EDT 2026.2 (при закрытой EDT)
Использовать именно консольный бинарник `1cedtc.exe` (`1cedt.exe` не пишет stdout в Windows):

```powershell
$edtC = "C:\Users\a.chernov\AppData\Local\1C\1cedtstart\installations\1C_EDT 2026.2\1cedt\1cedtc.exe"

# 1. Узнать установленную версию
(& $edtC -nosplash -application org.eclipse.equinox.p2.director -listInstalledRoots 2>&1) | Select-String "ditrix"

# 2. Заменить старую версию на новую одной командой
& $edtC -nosplash -application org.eclipse.equinox.p2.director `
  -repository "jar:file:/D:/EDT/mcp/MCP-EDT.v2.26.1-sirius.zip!/" `
  -uninstallIU com.ditrix.edt.mcp.server.feature.feature.group/<СТАРАЯ_ВЕРСИЯ> `
  -installIU com.ditrix.edt.mcp.server.feature.feature.group
```
