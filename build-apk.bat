@echo off
rem ===========================================================================
rem  Nova: сборка APK под Windows (cmd.exe).
rem
rem  Обёртка над gradlew.bat: проверяет JDK и Android SDK, собирает нужный
rem  вариант и показывает, где лежит готовый файл. Подробности — docs\BUILD.md.
rem
rem  Примеры:
rem    build-apk.bat                    отладочная сборка github/debug
rem    build-apk.bat /release           выпуск github/release, нужен ключ
rem    build-apk.bat /fdroid /release   сборка для каталога F-Droid
rem    build-apk.bat /clean /install    с очисткой и установкой на телефон
rem    build-apk.bat /help              все ключи
rem
rem  В репозитории файл хранится с переводами строк CRLF, см. .gitattributes:
rem  cmd.exe читает .bat построчно и на LF-файле спотыкается о метки.
rem ===========================================================================

setlocal EnableExtensions

rem Консоль переводим в UTF-8, иначе русский текст выводится мусором.
rem Исходную кодовую страницу запоминаем и возвращаем перед выходом.
for /f "tokens=2 delims=:" %%c in ('chcp') do set "OLD_CP=%%c"
set "OLD_CP=%OLD_CP: =%"
chcp 65001 >nul 2>nul
title Nova: сборка APK
rem Окно в конце не закрывается само и не закрывается клавишей: оно ждёт
rem крестик. Иначе сообщение об ошибке исчезает раньше, чем его прочитали,
rem а при двойном щелчке — ещё и без возможности что-то скопировать.
rem Для скриптов и CI есть /nohold: тогда окно не держим.
set "HOLD_AT_END=1"

set "FLAVOR=github"
set "FLAVOR_TASK=Github"
set "TYPE=debug"
set "TYPE_TASK=Debug"
set "CLEAN_TASK="
set "EXTRA_FLAGS="
set "DO_INSTALL=0"
set "JDK_ARG="
set "START_TIME=%TIME%"

cd /d "%~dp0"
if errorlevel 1 goto no_folder
if not exist "gradlew.bat" goto wrong_folder

rem --- ключи командной строки ------------------------------------------------
:args
if "%~1"=="" goto args_done
if /i "%~1"=="/help" goto usage
if /i "%~1"=="-h" goto usage
if /i "%~1"=="/?" goto usage
if /i "%~1"=="/release" (set "TYPE=release" & set "TYPE_TASK=Release" & shift & goto args)
if /i "%~1"=="/debug" (set "TYPE=debug" & set "TYPE_TASK=Debug" & shift & goto args)
if /i "%~1"=="/fdroid" (set "FLAVOR=fdroid" & set "FLAVOR_TASK=Fdroid" & shift & goto args)
if /i "%~1"=="/github" (set "FLAVOR=github" & set "FLAVOR_TASK=Github" & shift & goto args)
if /i "%~1"=="/clean" (set "CLEAN_TASK=clean" & shift & goto args)
if /i "%~1"=="/fast" (set "EXTRA_FLAGS=%EXTRA_FLAGS% -PnovaFastBuild" & shift & goto args)
if /i "%~1"=="/diag" (set "EXTRA_FLAGS=%EXTRA_FLAGS% -PnovaDiagnostics" & shift & goto args)
if /i "%~1"=="/install" (set "DO_INSTALL=1" & shift & goto args)
if /i "%~1"=="/jdk" (set "JDK_ARG=%~2" & shift & shift & goto args)
if /i "%~1"=="/trace" (set "EXTRA_FLAGS=%EXTRA_FLAGS% --stacktrace" & shift & goto args)
if /i "%~1"=="/nohold" (set "HOLD_AT_END=0" & shift & goto args)
echo.
echo   Неизвестный ключ: %~1
goto usage

:args_done
echo.
echo   Nova: сборка APK
echo   ---------------
echo   вариант: %FLAVOR%, тип: %TYPE%

rem --- 1. JDK ----------------------------------------------------------------
rem Версию проверяем не из вредности: Gradle 9.3.1 запускается на JDK 17-25
rem (JDK 26 — это Gradle 9.4, JDK 27 — 9.8), а на более новом падает на
rem разрешении плагинов с невнятным «plugin ... was not found». Проекту нужен
rem JDK 17, поэтому при чужой версии ищем подходящую рядом и берём её.
echo.
echo [1/3] Проверяю JDK
set "JAVA_EXE=java"
if defined JDK_ARG set "JAVA_EXE=%JDK_ARG%\bin\java.exe"
if defined JDK_ARG set "JAVA_HOME=%JDK_ARG%"
if not defined JDK_ARG if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
"%JAVA_EXE%" -version >nul 2>nul
if errorlevel 1 goto no_java
call :read_java_version
echo     %JAVA_EXE%
"%JAVA_EXE%" -version 2>&1 | findstr /i /c:"version"
if not defined JAVA_MAJOR goto jdk_ok
if %JAVA_MAJOR% GEQ 17 if %JAVA_MAJOR% LEQ 25 goto jdk_ok
echo     JDK %JAVA_MAJOR% не подходит: Gradle 9.3.1 работает на JDK 17-25.
call :find_jdk
if not defined JDK_FOUND goto jdk_ok
echo     Беру подходящую JDK с этой машины: %JDK_FOUND%
set "JAVA_HOME=%JDK_FOUND%"
set "JAVA_EXE=%JDK_FOUND%\bin\java.exe"
call :read_java_version
if not defined JAVA_MAJOR goto jdk_ok
if %JAVA_MAJOR% GEQ 17 if %JAVA_MAJOR% LEQ 25 goto jdk_ok
echo     Она тоже вне диапазона (JDK %JAVA_MAJOR%) — пробую сборку всё равно.

:jdk_ok

rem --- 2. Android SDK --------------------------------------------------------
rem Gradle берёт путь к SDK из ANDROID_HOME либо из local.properties в корне.
rem Проверяем оба источника, а если SDK нашёлся по стандартному пути Android
rem Studio — подставляем его в окружение, и тогда local.properties не нужен.
echo.
echo [2/3] Проверяю Android SDK
set "SDK_FOUND="
if exist "%ANDROID_HOME%\platforms" set "SDK_FOUND=%ANDROID_HOME%"
if not defined SDK_FOUND if exist "%ANDROID_SDK_ROOT%\platforms" set "SDK_FOUND=%ANDROID_SDK_ROOT%"
if not defined SDK_FOUND if exist "%LOCALAPPDATA%\Android\Sdk\platforms" set "SDK_FOUND=%LOCALAPPDATA%\Android\Sdk"
if not defined SDK_FOUND goto sdk_unknown
if not defined ANDROID_HOME set "ANDROID_HOME=%SDK_FOUND%"
echo     %SDK_FOUND%
call :check_sdk_components
goto sdk_checked

:sdk_unknown
if exist "local.properties" (
    echo     путь указан в local.properties
    goto sdk_checked
)
echo     Android SDK не найден.
echo     Поставьте Android Studio — она создаст local.properties при первом
echo     открытии проекта; либо задайте переменную ANDROID_HOME; либо впишите
echo     в local.properties строку
echo         sdk.dir=C\:\\Users\\%USERNAME%\\AppData\\Local\\Android\\Sdk
goto fail

:sdk_checked
rem --- 3. Подпись и сборка ---------------------------------------------------
echo.
if not "%TYPE%"=="release" goto sign_ok
echo [3/3] Проверяю подпись выпуска
if exist "keystore.properties" echo     keystore.properties на месте
if not exist "keystore.properties" echo     keystore.properties нет: APK соберётся неподписанным
if not exist "keystore.properties" echo     и не установится. Как подписать — docs\BUILD.md.
goto sign_done

:sign_ok
echo [3/3] Отладочная подпись, готовить ничего не нужно

:sign_done
echo.
echo   Запускаю: gradlew.bat %CLEAN_TASK% assemble%FLAVOR_TASK%%TYPE_TASK%%EXTRA_FLAGS%
echo.
call gradlew.bat %CLEAN_TASK% assemble%FLAVOR_TASK%%TYPE_TASK%%EXTRA_FLAGS% --console=plain
if errorlevel 1 goto build_failed

rem --- результат ------------------------------------------------------------
set "APK_DIR=app\build\outputs\apk\%FLAVOR%\%TYPE%"
set "APK="
if exist "%APK_DIR%" for %%f in ("%APK_DIR%\*.apk") do set "APK=%%~ff"
if not defined APK goto no_apk

for %%f in ("%APK%") do set "APK_NAME=%%~nf"
for %%f in ("%APK%") do set "APK_SIZE=%%~zf"
set "VERSION=%APK_NAME:Nova_=%"
set "COPY="
set "RESULT=out\Nova_%VERSION%-%FLAVOR%-%TYPE%.apk"
if not exist "out" mkdir "out" >nul 2>nul
if not exist "out" goto copied
copy /y "%APK%" "%RESULT%" >nul 2>nul
if errorlevel 1 goto copied
set "COPY=%CD%\%RESULT%"

:copied
if "%DO_INSTALL%"=="1" (
    echo.
    echo   Устанавливаю на подключённое устройство
    where adb >nul 2>nul
    if errorlevel 1 (
        echo     adb не найден в PATH — он лежит в platform-tools пакета SDK
    ) else (
        adb install -r "%APK%"
        if errorlevel 1 echo     Установка не удалась: включена ли отладка по USB?
    )
)

echo.
echo   Готово за %START_TIME% - %TIME%
echo   APK:    %APK%
echo   Размер: %APK_SIZE% байт
if defined COPY echo   Копия:  %COPY%
goto finish_ok

rem --- ошибки ---------------------------------------------------------------
:build_failed
echo.
echo   Gradle завершился с ошибкой. Проверяю, что видно с этой машины:
echo.
where curl >nul 2>nul
if errorlevel 1 (
    echo     curl не найден — проверку доступа к репозиториям пропускаю
) else (
    call :probe "https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/9.1.0/gradle-9.1.0.pom" "Google Maven — dl.google.com"
    call :probe "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/2.2.10/kotlin-gradle-plugin-2.2.10.pom" "Maven Central — repo1.maven.org"
    call :probe "https://plugins.gradle.org/m2/org/jetbrains/kotlin/android/org.jetbrains.kotlin.android.gradle.plugin/2.2.10/org.jetbrains.kotlin.android.gradle.plugin-2.2.10.pom" "портал плагинов — plugins.gradle.org"
    call :probe "https://services.gradle.org/distributions/gradle-9.3.1-bin.zip" "дистрибутив Gradle 9.3.1 — services.gradle.org"
)
if defined SDK_MISSING echo     - В наборе Android SDK не хватает компонентов: список в разделе [2/3].
echo.
echo   Частые причины:
echo     - JDK новее 25. Gradle 9.3.1 на такой JDK падает как раз с «plugin
echo       ... was not found», хотя плагин на месте. Нужен JDK 17:
echo         winget install -e --id EclipseAdoptium.Temurin.17.JDK
echo       затем перезапустите скрипт; свою JDK можно указать явно:
echo         build-apk.bat /jdk "C:\Program Files\Eclipse Adoptium\jdk-17"
echo     - Maven Central или портал плагинов не открываются — выше будет
echo       НЕДОСТУПЕН. Помогает VPN или прокси, прописанный в
echo       %USERPROFILE%\.gradle\gradle.properties:
echo         systemProp.https.proxyHost=... systemProp.https.proxyPort=...
echo     - Нет NDK 27.2.12479018 или CMake 3.22.1: проект собирает JNI-слой
echo       tun2proxy из src\main\cpp, а версии зашиты в app\build.gradle.kts.
echo       NDK 28+ не подойдёт: нужна именно 27.2.12479018 и CMake 3.22.1.
echo       Поставить: см. раздел [2/3] выше либо Android Studio, SDK Tools.
echo     - Android SDK не найден: docs\BUILD.md, «Что должно быть установлено».
echo     - Выпуск: нет keystore.properties либо ключ релея выписан на другую
echo       версию, см. docs\BUILD.md, «Подпись релиза» и «Ключ релея».
echo.
echo   Подробный вывод: build-apk.bat /trace   (добавляет --stacktrace)
goto fail_silent

:no_folder
echo   Не удалось перейти в папку скрипта: %~dp0
goto fail_silent

:wrong_folder
echo.
echo   Рядом со скриптом нет gradlew.bat.
echo   Положите build-apk.bat в корень проекта Nova и запустите оттуда.
goto fail_silent

:no_java
echo     JDK не найден.
echo.
echo   Нужен JDK 17: winget install -e --id EclipseAdoptium.Temurin.17.JDK
echo   После установки откройте новую консоль — старая новых переменных не
echo   увидит — либо задайте JAVA_HOME на папку с bin\java.exe.
echo   Полная установка инструментов: tools\setup_android_tools.ps1
goto fail_silent

:no_apk
echo.
echo   Сборка прошла, но APK не найден в %APK_DIR%.
echo   Загляните в app\build\outputs\apk: возможно, сменились flavor и тип.
goto fail_silent

:fail
echo.
echo   Сборка не выполнена.

:fail_silent
chcp %OLD_CP% >nul 2>nul
call :hold_window
endlocal
exit /b 1

:finish_ok
chcp %OLD_CP% >nul 2>nul
call :hold_window
endlocal
exit /b 0

:usage
echo.
echo   Nova: сборка APK под Windows
echo.
echo   build-apk.bat [ключи]
echo.
echo   Без ключей собирается github/debug: такую сборку можно сразу ставить
echo   на телефон, подписана она отладочным ключом.
echo.
echo   Ключи:
echo     /debug     тип debug, по умолчанию
echo     /release   тип release: подписанный выпуск, нужен keystore.properties
echo     /github    вариант github, по умолчанию: со встроенным обновлением
echo     /fdroid    вариант fdroid: обновления выдаёт каталог F-Droid
echo     /clean     сначала очистить прошлую сборку
echo     /fast      без R8 и lint: быстро, но такой APK не публикуют
echo     /diag      добавить экран самодиагностики
echo     /install   установить готовый APK на устройство через adb
echo     /jdk ПУТЬ  собрать этой JDK, например /jdk "C:\Program Files\Eclipse
echo                Adoptium\jdk-17"; нужно, когда java в PATH новее 25
echo     /trace     добавить --stacktrace для разбора падений
echo     /nohold    не держать окно открытым (для скриптов и CI)
echo     /help      эта справка
echo.
echo   Готовый файл остаётся в app\build\outputs\apk и копируется в out\.
echo   Подробности: docs\BUILD.md
echo.
echo   Окно закрывается только крестиком [X], клавиша его не закрывает.
echo.
chcp %OLD_CP% >nul 2>nul
call :hold_window
endlocal
exit /b 0

rem --- вспомогательное ------------------------------------------------------
rem До сюда управление доходит только через call.

:hold_window
rem Ждём крестик, а не клавишу: клавиша окно больше не закрывает. Выхода из
rem этой подпрограммы нет, если не задан /nohold.
if "%HOLD_AT_END%"=="0" goto :eof
rem Вызывают нас уже после возврата исходной кодовой страницы, а текст ниже
rem русский. Без этого он выводится мусором ровно в том сообщении, ради
rem которого окно и держится.
chcp 65001 >nul 2>nul
if not "%HOLD_SHOWN%"=="1" echo.
if not "%HOLD_SHOWN%"=="1" echo   Окно закрывается только крестиком [X] в правом верхнем углу.
if not "%HOLD_SHOWN%"=="1" echo   Клавиша его не закрывает: дочитайте и нажмите [X].
if not "%HOLD_SHOWN%"=="1" echo   Следующий запуск без удержания: build-apk.bat /nohold
set "HOLD_SHOWN=1"
:hold_window_wait
%SystemRoot%\System32\timeout.exe /t 3600 /nobreak >nul 2>nul
if errorlevel 1 %SystemRoot%\System32\ping.exe -n 3600 127.0.0.1 >nul 2>nul
goto hold_window_wait

:check_sdk_components
rem Состав SDK проверяем поимённо. «Gradle ругается» чаще всего на NDK
rem 27.2.12479018 и CMake 3.22.1: Android Studio их не ставит, пока в SDK
rem Tools не отметить галочки, а сообщение при этом невнятное.
set "SDK_MISSING="
if exist "%SDK_FOUND%\platforms\android-35" echo     platform android-35: есть
if not exist "%SDK_FOUND%\platforms\android-35" call :need_component "platforms;android-35" "Android SDK Platform 35"
dir /b /ad "%SDK_FOUND%\build-tools" >nul 2>nul
if errorlevel 1 call :need_component "build-tools;35.0.0" "Android SDK Build-Tools"
if not errorlevel 1 echo     build-tools: есть
if exist "%SDK_FOUND%\platform-tools\adb.exe" echo     platform-tools (adb): есть
if not exist "%SDK_FOUND%\platform-tools\adb.exe" call :need_component "platform-tools" "platform-tools (нужен для /install)"
if exist "%SDK_FOUND%\ndk\27.2.12479018" echo     NDK 27.2.12479018: есть
if not exist "%SDK_FOUND%\ndk\27.2.12479018" call :need_component "ndk;27.2.12479018" "NDK 27.2.12479018"
if exist "%SDK_FOUND%\cmake\3.22.1" echo     CMake 3.22.1: есть
if not exist "%SDK_FOUND%\cmake\3.22.1" call :need_component "cmake;3.22.1" "CMake 3.22.1"
if not defined SDK_MISSING echo     все нужные компоненты на месте
if not defined SDK_MISSING goto :eof
echo.
echo     Не хватает перечисленного - сборка упадёт именно на нём. Поставить
echo     можно одной командой (подставьте путь к sdkmanager со своей машины):
echo         sdkmanager "platforms;android-35" "build-tools;35.0.0" "ndk;27.2.12479018" "cmake;3.22.1"
echo     Либо в Android Studio: Settings - Languages and Frameworks - Android SDK -
echo     SDK Tools, галочка Show Package Details, затем NDK (Side by side) 27.2
echo     и CMake 3.22.1.
goto :eof

:need_component
rem Первый параметр - идентификатор пакета sdkmanager, второй - имя для журнала.
set "SDK_MISSING=1"
echo     %~2: НЕТ, нужен пакет %~1
goto :eof

:read_java_version
rem Ставит JAVA_MAJOR по выводу java -version. Пишем в файл: у самого вывода
rem кавычки, а путь к java.exe может содержать пробелы.
set "JAVA_MAJOR="
set "JAVA_RAW="
set "JAVA_VER_FILE=%TEMP%\nova-java-version.txt"
"%JAVA_EXE%" -version 2>"%JAVA_VER_FILE%" >nul
for /f "tokens=3" %%v in ('findstr /i /c:"version" "%JAVA_VER_FILE%"') do set "JAVA_RAW=%%~v"
del "%JAVA_VER_FILE%" >nul 2>nul
if not defined JAVA_RAW goto :eof
for /f "tokens=1 delims=." %%m in ("%JAVA_RAW%") do set "JAVA_MAJOR=%%m"
if "%JAVA_MAJOR%"=="1" set "JAVA_MAJOR=8"
goto :eof

:find_jdk
rem Ищет установленную JDK 17 или 21: у Android Studio своя (jbr), у остальных
rem — Adoptium, Oracle или Microsoft.
set "JDK_FOUND="
for /d %%d in ("%PROGRAMFILES%\Eclipse Adoptium\jdk-17*") do if exist "%%~fd\bin\java.exe" set "JDK_FOUND=%%~fd"
if defined JDK_FOUND goto :eof
for /d %%d in ("%PROGRAMFILES%\Java\jdk-17*") do if exist "%%~fd\bin\java.exe" set "JDK_FOUND=%%~fd"
if defined JDK_FOUND goto :eof
for /d %%d in ("%PROGRAMFILES%\Microsoft\jdk-17*") do if exist "%%~fd\bin\java.exe" set "JDK_FOUND=%%~fd"
if defined JDK_FOUND goto :eof
for /d %%d in ("%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-17*") do if exist "%%~fd\bin\java.exe" set "JDK_FOUND=%%~fd"
if defined JDK_FOUND goto :eof
for /d %%d in ("%PROGRAMFILES%\Eclipse Adoptium\jdk-21*") do if exist "%%~fd\bin\java.exe" set "JDK_FOUND=%%~fd"
if defined JDK_FOUND goto :eof
for /d %%d in ("%PROGRAMFILES%\Java\jdk-21*") do if exist "%%~fd\bin\java.exe" set "JDK_FOUND=%%~fd"
if defined JDK_FOUND goto :eof
for /d %%d in ("%PROGRAMFILES%\Android\Android Studio\jbr") do if exist "%%~fd\bin\java.exe" set "JDK_FOUND=%%~fd"
if defined JDK_FOUND goto :eof
for /d %%d in ("%LOCALAPPDATA%\Programs\Android Studio\jbr") do if exist "%%~fd\bin\java.exe" set "JDK_FOUND=%%~fd"
goto :eof

:probe
rem Однострочные if, а не блок: подпись подставляется в echo, и скобка из
rem подстановки закрыла бы блок раньше времени.
curl -s -f -o nul -m 12 "%~1" >nul 2>nul
if errorlevel 1 echo     %~2 — НЕДОСТУПЕН
if not errorlevel 1 echo     %~2 — ok
goto :eof
