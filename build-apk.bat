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
rem Двойной щелчок по файлу запускает его в отдельном окне, которое cmd
rem закрывает сразу по завершении — тогда ждём нажатия клавиши.
set "PAUSE_AT_END=0"
echo "%CMDCMDLINE%" | find /i "%~nx0" >nul 2>nul
if not errorlevel 1 set "PAUSE_AT_END=1"

set "FLAVOR=github"
set "FLAVOR_TASK=Github"
set "TYPE=debug"
set "TYPE_TASK=Debug"
set "CLEAN_TASK="
set "EXTRA_FLAGS="
set "DO_INSTALL=0"
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
echo.
echo   Неизвестный ключ: %~1
goto usage

:args_done
echo.
echo   Nova: сборка APK
echo   ---------------
echo   вариант: %FLAVOR%, тип: %TYPE%

rem --- 1. JDK ----------------------------------------------------------------
echo.
echo [1/3] Проверяю JDK
set "JAVA_EXE=java"
if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
"%JAVA_EXE%" -version >nul 2>nul
if errorlevel 1 goto no_java
echo     %JAVA_EXE%
"%JAVA_EXE%" -version 2>&1 | findstr /i /c:"version"

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
echo   Нужен JDK 17: winget install EclipseAdoptium.Temurin.17
echo   После установки откройте новую консоль — старая новых переменных не
echo   увидит — либо задайте JAVA_HOME на папку с bin\java.exe.
echo   Полная установка инструментов: tools\setup_android_tools.ps1
goto fail_silent

:build_failed
echo.
echo   Gradle завершился с ошибкой. Частые причины:
echo     - JDK не 17: сверьтесь со строкой версии выше;
echo     - SDK не найден: docs\BUILD.md, «Что должно быть установлено»;
echo     - выпуск: нет keystore.properties либо ключ релея выписан на другую
echo       версию, см. docs\BUILD.md, «Подпись релиза» и «Ключ релея».
echo.
echo   Быстрая проверочная сборка без R8 и lint:  build-apk.bat /release /fast
echo   Сборка со стороны, без выпускных секретов:
echo       set NOVA_ALLOW_UNSIGNED_RELEASE=1
echo       set NOVA_ALLOW_UNPUBLISHABLE_BUILD=1
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
if "%PAUSE_AT_END%"=="1" pause
endlocal
exit /b 1

:finish_ok
chcp %OLD_CP% >nul 2>nul
if "%PAUSE_AT_END%"=="1" pause
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
echo     /help      эта справка
echo.
echo   Готовый файл остаётся в app\build\outputs\apk и копируется в out\.
echo   Подробности: docs\BUILD.md
echo.
echo   Скрипт можно запустить двойным щелчком: окно останется открытым
echo   до нажатия клавиши.
echo.
chcp %OLD_CP% >nul 2>nul
endlocal
exit /b 0
