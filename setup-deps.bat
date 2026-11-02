@echo off
rem ===========================================================================
rem  Nova: зависимости для сборки APK (Windows, cmd.exe).
rem
rem  Ставит то, чего нет на чистой машине и что Android Studio не ставит сама:
rem  Command-line Tools, Platform 35, Build-Tools, platform-tools, NDK
rem  27.2.12479018 и CMake 3.22.1. Версии не выдуманы: скрипт читает их из
rem  app\build.gradle.kts (compileSdk, ndkVersion, cmake.version) и печатает
rem  то, что нашёл, чтобы расхождение с проектом было видно сразу.
rem
rem  Примеры:
rem    setup-deps.bat                    поставить всё в стандартный SDK
rem    setup-deps.bat /sdk "D:\Android\Sdk"
rem    setup-deps.bat /jdk "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.1-hotspot"
rem    setup-deps.bat /winget            нет JDK - поставить Temurin 17 через winget
rem    setup-deps.bat /build             поставить зависимости и сразу собрать APK
rem    setup-deps.bat /help              все ключи
rem
rem  Файл хранится с переводами строк CRLF, см. .gitattributes: cmd.exe читает
rem  .bat построчно и на LF-файле спотыкается о метки.
rem ===========================================================================

setlocal EnableExtensions

rem Консоль переводим в UTF-8, иначе русский текст выводится мусором.
for /f "tokens=2 delims=:" %%c in ('chcp') do set "OLD_CP=%%c"
set "OLD_CP=%OLD_CP: =%"
chcp 65001 >nul 2>nul
title Nova: зависимости для сборки

rem Окно в конце не закрывается само и не закрывается клавишей: только
rem крестик, чтобы вывод можно было дочитать и скопировать.
set "HOLD_AT_END=1"
set "HOLD_SHOWN="

set "JDK_ARG="
set "SDK_ARG="
set "DO_BUILD=0"
set "DO_WINGET=0"
set "DO_ENV=0"
set "FAILED="
set "START_TIME=%TIME%"

cd /d "%~dp0"
if errorlevel 1 goto no_folder
if not exist "app\build.gradle.kts" goto wrong_folder

rem --- ключи командной строки ------------------------------------------------
:args
if "%~1"=="" goto args_done
if /i "%~1"=="/help" goto usage
if /i "%~1"=="-h" goto usage
if /i "%~1"=="/?" goto usage
if /i "%~1"=="/nohold" (set "HOLD_AT_END=0" & shift & goto args)
if /i "%~1"=="/build" (set "DO_BUILD=1" & shift & goto args)
if /i "%~1"=="/winget" (set "DO_WINGET=1" & shift & goto args)
if /i "%~1"=="/env" (set "DO_ENV=1" & shift & goto args)
if /i "%~1"=="/jdk" (set "JDK_ARG=%~2" & shift & shift & goto args)
if /i "%~1"=="/sdk" (set "SDK_ARG=%~2" & shift & shift & goto args)
echo.
echo   Неизвестный ключ: %~1
goto usage

:args_done
echo.
echo   Nova: зависимости для сборки
echo   -----------------------------

rem --- 1. версии из проекта --------------------------------------------------
rem Читаем из app\build.gradle.kts, а не держим в голове: compileSdk уезжает
rem вверх вместе с проектом, ndkVersion и cmake.version меняются вместе с ним.
echo.
echo [1/6] Версии, которые нужны проекту
set "PLATFORM_NEED=35"
set "NDK_NEED=27.2.12479018"
set "CMAKE_NEED=3.22.1"
for /f "tokens=2 delims== " %%v in ('findstr /r /c:"compileSdk *= *[0-9]" "app\build.gradle.kts"') do set "PLATFORM_NEED=%%~v"
for /f "tokens=2 delims== " %%v in ('findstr /r /c:"ndkVersion *=" "app\build.gradle.kts"') do set "NDK_NEED=%%~v"
for /f "tokens=2 delims== " %%v in ('findstr /c:"version = " "app\build.gradle.kts"') do set "CMAKE_NEED=%%~v"
set "BUILDTOOLS_NEED=%PLATFORM_NEED%.0.0"
echo     platforms;android-%PLATFORM_NEED%
echo     build-tools;%BUILDTOOLS_NEED%
echo     platform-tools
echo     ndk;%NDK_NEED%
echo     cmake;%CMAKE_NEED%

rem --- 2. JDK ----------------------------------------------------------------
rem Gradle 9.3.1 запускается на JDK 17-25 (JDK 26 - это Gradle 9.4, JDK 27 -
rem 9.8), а проекту нужна 17. На более новой JDK сборка падает не с понятным
rem "нужен другой Gradle", а на разрешении плагинов.
echo.
echo [2/6] JDK
set "JDK_HOME="
set "JDK_FALLBACK="
if defined JDK_ARG set "JDK_HOME=%JDK_ARG%"
if defined JDK_HOME goto jdk_pick
if not exist "%JAVA_HOME%\bin\java.exe" goto jdk_search
set "JDK_HOME=%JAVA_HOME%"
call :java_major_of
if not defined JAVA_MAJOR goto jdk_pick
if %JAVA_MAJOR% GEQ 17 if %JAVA_MAJOR% LEQ 25 goto jdk_pick
rem JAVA_HOME вне диапазона: держим его про запас и ищем подходящую JDK.
echo     JAVA_HOME указывает на JDK %JAVA_MAJOR%, а Gradle 9.3.1 работает на 17-25
set "JDK_FALLBACK=%JDK_HOME%"
set "JDK_HOME="

:jdk_search
call :find_jdk
if defined JDK_HOME goto jdk_pick
if not defined JDK_FALLBACK goto no_jdk
echo     подходящей JDK на машине нет - собираю тем, что есть
set "JDK_HOME=%JDK_FALLBACK%"

:jdk_pick
if not exist "%JDK_HOME%\bin\java.exe" goto no_jdk
echo     %JDK_HOME%
call :print_java_version
set "JAVA_HOME=%JDK_HOME%"
set "PATH=%JDK_HOME%\bin;%PATH%"
goto jdk_ok

:no_jdk
echo     JDK не найден.
if "%DO_WINGET%"=="1" goto jdk_winget
echo     Поставьте Temurin 17 и запустите скрипт снова:
echo         winget install -e --id EclipseAdoptium.Temurin.17.JDK
echo     Либо укажите папку с bin\java.exe:
echo         setup-deps.bat /jdk "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.1-hotspot"
goto fail

:jdk_winget
where winget >nul 2>nul
if errorlevel 1 goto jdk_winget_missing
echo     Ставлю Eclipse Temurin 17 через winget...
winget install -e --id EclipseAdoptium.Temurin.17.JDK --silent --accept-package-agreements --accept-source-agreements
call :find_jdk
if defined JDK_HOME goto jdk_winget_ok
rem Повторять установку по кругу нельзя: снимаем признак, и no_jdk уже
rem просто расскажет, где взять JDK.
set "DO_WINGET=0"
goto no_jdk

:jdk_winget_ok
echo     %JDK_HOME%
call :print_java_version
set "JAVA_HOME=%JDK_HOME%"
set "PATH=%JDK_HOME%\bin;%PATH%"
goto jdk_ok

:jdk_winget_missing
echo     winget на этой машине нет: поставьте JDK 17 вручную по ссылке
echo     https://adoptium.net/temurin/releases/?version=17&os=windows&arch=x64
goto fail

:jdk_ok

rem --- 3. Android SDK --------------------------------------------------------
echo.
echo [3/6] Android SDK
set "SDK="
if defined SDK_ARG set "SDK=%SDK_ARG%"
if not defined SDK if defined ANDROID_HOME set "SDK=%ANDROID_HOME%"
if not defined SDK if exist "local.properties" call :read_props_sdk
if not defined SDK if defined PROPS_SDK set "SDK=%PROPS_SDK%"
if not defined SDK if defined ANDROID_SDK_ROOT set "SDK=%ANDROID_SDK_ROOT%"
if not defined SDK if exist "%LOCALAPPDATA%\Android\Sdk" set "SDK=%LOCALAPPDATA%\Android\Sdk"
if not defined SDK set "SDK=%LOCALAPPDATA%\Android\Sdk"
echo     %SDK%
if exist "%SDK%" goto sdk_ready
echo     папки нет - создаю
mkdir "%SDK%" >nul 2>nul
if not exist "%SDK%" goto sdk_no_mkdir
:sdk_ready
set "ANDROID_HOME=%SDK%"
set "ANDROID_SDK_ROOT=%SDK%"

rem --- 4. sdkmanager ---------------------------------------------------------
echo.
echo [4/6] Command-line Tools (sdkmanager)
set "SDKMGR="
if exist "%SDK%\cmdline-tools\latest\bin\sdkmanager.bat" set "SDKMGR=%SDK%\cmdline-tools\latest\bin\sdkmanager.bat"
if not defined SDKMGR for /d %%d in ("%SDK%\cmdline-tools\*") do if exist "%%~fd\bin\sdkmanager.bat" set "SDKMGR=%%~fd\bin\sdkmanager.bat"
if defined SDKMGR goto sdkmanager_ready
echo     нет - скачиваю официальный архив Google
call :download_cmdline_tools
if errorlevel 1 goto sdkmanager_failed
if not exist "%SDK%\cmdline-tools\latest\bin\sdkmanager.bat" goto sdkmanager_failed
set "SDKMGR=%SDK%\cmdline-tools\latest\bin\sdkmanager.bat"
:sdkmanager_ready
echo     %SDKMGR%

rem --- 5. лицензии и пакеты --------------------------------------------------
echo.
echo [5/6] Лицензии и пакеты
echo     принимаю лицензии
(for /l %%i in (1,1,40) do @echo y) | "%SDKMGR%" --licenses --sdk_root="%SDK%" >nul 2>nul
echo     ставлю пакеты: это надолго, NDK весит около гигабайта
"%SDKMGR%" --sdk_root="%SDK%" "platform-tools" "platforms;android-%PLATFORM_NEED%" "build-tools;%BUILDTOOLS_NEED%" "ndk;%NDK_NEED%" "cmake;%CMAKE_NEED%"
if errorlevel 1 goto packages_failed
echo.
echo     проверяю, что стоит
call :check "platforms\android-%PLATFORM_NEED%" "platform android-%PLATFORM_NEED%"
call :check "platform-tools\adb.exe" "platform-tools (adb)"
call :check "ndk\%NDK_NEED%" "NDK %NDK_NEED%"
call :check "cmake\%CMAKE_NEED%" "CMake %CMAKE_NEED%"
dir /b /ad "%SDK%\build-tools" >nul 2>nul
if errorlevel 1 echo     build-tools: НЕТ
if errorlevel 1 set "FAILED=1"
if not errorlevel 1 echo     build-tools: есть

rem --- 6. local.properties ---------------------------------------------------
rem Gradle берёт путь к SDK отсюда, если не задан ANDROID_HOME. Строку
rem дописываем в конец, а не перезаписываем файл: в нём могут лежать чужие
rem ключи (секреты релея и подписи WSS), и терять их нельзя.
echo.
echo [6/6] Файл local.properties
set "SDK_ESC=%SDK%"
set "SDK_ESC=%SDK_ESC:\=\\%"
set "SDK_ESC=%SDK_ESC::=\:%"
findstr /b /c:"sdk.dir=" "local.properties" >nul 2>nul
if errorlevel 1 goto props_append
call :read_props_sdk
if not defined PROPS_SDK goto props_append
if /i "%PROPS_SDK%"=="%SDK%" goto props_exist
echo     sdk.dir указывает на %PROPS_SDK%, а ставили в %SDK%
echo     дописываю верный путь: последняя строка в файле важнее первой
goto props_append

:props_append
>> "local.properties" echo sdk.dir=%SDK_ESC%
echo     записан sdk.dir=%SDK_ESC%
goto props_done

:props_exist
echo     sdk.dir уже указывает на этот SDK - не трогаю

:props_done
echo.
if defined FAILED goto deps_failed
echo   Все зависимости на месте (%START_TIME% - %TIME%).
if "%DO_ENV%"=="1" call :set_user_env
if "%DO_BUILD%"=="1" goto run_build
echo   Дальше: build-apk.bat
goto finish_ok

:run_build
echo   Запускаю build-apk.bat /nohold
echo.
call "%~dp0build-apk.bat" /nohold
if errorlevel 1 goto build_failed
echo.
echo   Сборка прошла.
goto finish_ok

rem --- ошибки ---------------------------------------------------------------
:packages_failed
echo.
echo   sdkmanager завершился с ошибкой. Частые причины:
echo     - нет сети до dl.google.com (сюда он и качает пакеты);
echo     - не приняты лицензии: запустите и подтвердите вручную
echo         "%SDKMGR%" --licenses --sdk_root="%SDK%"
echo     - мало места на диске: NDK 27 занимает около 4 ГБ.
goto fail

:sdkmanager_failed
echo.
echo   Не удалось получить sdkmanager. Два пути:
echo     1) Android Studio: SDK Manager - SDK Tools - Android SDK Command-line Tools;
echo     2) скачать вручную и распаковать так, чтобы получилось
echo        %SDK%\cmdline-tools\latest\bin\sdkmanager.bat
echo   Архив: https://developer.android.com/studio#command-tools
goto fail

:sdk_no_mkdir
echo     не удалось создать папку SDK: %SDK%
echo     Укажите другой путь: setup-deps.bat /sdk "D:\Android\Sdk"
goto fail

:build_failed
echo.
echo   Зависимости поставлены, но сборка упала: разбор в выводе выше.
echo   Повторить сборку отдельно: build-apk.bat   (или build-apk.bat /trace)
goto fail

:deps_failed
echo   Часть компонентов не установилась: строки "НЕТ" выше.
goto fail

:no_folder
echo   Не удалось перейти в папку скрипта: %~dp0
goto fail_silent

:wrong_folder
echo.
echo   Рядом со скриптом нет app\build.gradle.kts.
echo   Положите setup-deps.bat в корень проекта Nova и запустите оттуда.
goto fail_silent

:fail
echo.
echo   Подготовка зависимостей не завершена.
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
echo   Nova: зависимости для сборки (Windows)
echo.
echo   setup-deps.bat [ключи]
echo.
echo   Ставит в Android SDK: Command-line Tools, Platform из compileSdk,
echo   Build-Tools, platform-tools, NDK и CMake тех версий, что зашиты в
echo   app\build.gradle.kts. Проверяет JDK и дописывает local.properties.
echo.
echo   Ключи:
echo     /sdk ПУТЬ  ставить в этот SDK, а не в стандартный
echo                %LOCALAPPDATA%\Android\Sdk
echo     /jdk ПУТЬ  взять эту JDK, например /jdk "C:\Program Files\Eclipse
echo                Adoptium\jdk-17.0.20.1-hotspot"
echo     /winget    JDK не найдена - поставить Temurin 17 через winget
echo     /env       прописать ANDROID_HOME в переменные пользователя (setx)
echo     /build     после установки сразу собрать APK
echo     /nohold    не держать окно открытым (для скриптов и CI)
echo     /help      эта справка
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
if not "%HOLD_SHOWN%"=="1" echo   Следующий запуск без удержания: setup-deps.bat /nohold
set "HOLD_SHOWN=1"
:hold_window_wait
%SystemRoot%\System32\timeout.exe /t 3600 /nobreak >nul 2>nul
if errorlevel 1 %SystemRoot%\System32\ping.exe -n 3600 127.0.0.1 >nul 2>nul
goto hold_window_wait

:find_jdk
rem Ищем JDK 17 (проектная версия), затем 21 и jbr из Android Studio: любая
rem из них годится для Gradle 9.3.1.
set "JDK_HOME="
for /d %%d in ("%PROGRAMFILES%\Eclipse Adoptium\jdk-17*") do if exist "%%~fd\bin\java.exe" set "JDK_HOME=%%~fd"
if defined JDK_HOME goto :eof
for /d %%d in ("%PROGRAMFILES%\Java\jdk-17*") do if exist "%%~fd\bin\java.exe" set "JDK_HOME=%%~fd"
if defined JDK_HOME goto :eof
for /d %%d in ("%PROGRAMFILES%\Microsoft\jdk-17*") do if exist "%%~fd\bin\java.exe" set "JDK_HOME=%%~fd"
if defined JDK_HOME goto :eof
for /d %%d in ("%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-17*") do if exist "%%~fd\bin\java.exe" set "JDK_HOME=%%~fd"
if defined JDK_HOME goto :eof
for /d %%d in ("%PROGRAMFILES%\Eclipse Adoptium\jdk-21*") do if exist "%%~fd\bin\java.exe" set "JDK_HOME=%%~fd"
if defined JDK_HOME goto :eof
for /d %%d in ("%PROGRAMFILES%\Java\jdk-21*") do if exist "%%~fd\bin\java.exe" set "JDK_HOME=%%~fd"
if defined JDK_HOME goto :eof
for /d %%d in ("%PROGRAMFILES%\Android\Android Studio\jbr") do if exist "%%~fd\bin\java.exe" set "JDK_HOME=%%~fd"
if defined JDK_HOME goto :eof
for /d %%d in ("%LOCALAPPDATA%\Programs\Android Studio\jbr") do if exist "%%~fd\bin\java.exe" set "JDK_HOME=%%~fd"
goto :eof

:java_major_of
rem Ставит JAVA_RAW (как в выводе java -version) и JAVA_MAJOR по %JDK_HOME%.
rem Версию пишем в файл: у самого вывода кавычки, а путь может быть с пробелами.
set "JAVA_RAW="
set "JAVA_MAJOR="
set "JAVA_VER_FILE=%TEMP%\nova-java-version.txt"
"%JDK_HOME%\bin\java.exe" -version 2>"%JAVA_VER_FILE%" >nul
for /f "tokens=3" %%v in ('findstr /i /c:"version" "%JAVA_VER_FILE%"') do if not defined JAVA_RAW set "JAVA_RAW=%%~v"
del "%JAVA_VER_FILE%" >nul 2>nul
if not defined JAVA_RAW goto :eof
for /f "tokens=1 delims=." %%m in ("%JAVA_RAW%") do set "JAVA_MAJOR=%%m"
if "%JAVA_MAJOR%"=="1" set "JAVA_MAJOR=8"
goto :eof

:print_java_version
call :java_major_of
if not defined JAVA_RAW echo     версия: определить не удалось
if defined JAVA_RAW echo     версия: %JAVA_RAW%
if not defined JAVA_MAJOR goto :eof
if %JAVA_MAJOR% GEQ 17 if %JAVA_MAJOR% LEQ 25 goto :eof
echo     внимание: JDK %JAVA_MAJOR% вне диапазона 17-25, Gradle 9.3.1 на такой
echo     не запустится. Нужна 17 (или 21). Запасной путь: ключ /jdk.
goto :eof

:read_props_sdk
rem Путь из local.properties в PROPS_SDK: в .properties обратные косые черты
rem удвоены, а двоеточие экранировано - снимаем оба экранирования. Глобальный
rem SDK здесь не трогаем: функцию зовут и на проверку, и на выбор пути.
set "PROPS_SDK="
set "PROPS_RAW="
for /f "tokens=1,* delims==" %%a in ('findstr /b /c:"sdk.dir=" "local.properties" 2^>nul') do set "PROPS_RAW=%%b"
if not defined PROPS_RAW goto :eof
set "PROPS_RAW=%PROPS_RAW:\\=\%"
set "PROPS_RAW=%PROPS_RAW:\:=:%"
set "PROPS_SDK=%PROPS_RAW%"
goto :eof

:download_cmdline_tools
rem Официальный архив Google. Номер сборки в имени файла меняется, суффикс
rem _latest постоянный. Если ссылка перестанет открываться, свежую берут
rem на https://developer.android.com/studio#command-tools
set "CLT_URL=https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
set "CLT_ZIP=%TEMP%\nova-cmdline-tools.zip"
set "CLT_TMP=%TEMP%\nova-cmdline-tools"
echo     %CLT_URL%
if exist "%CLT_ZIP%" del /q "%CLT_ZIP%" >nul 2>nul
where curl >nul 2>nul
if errorlevel 1 goto clt_ps_download
curl -L --fail --silent --show-error -o "%CLT_ZIP%" "%CLT_URL%"
goto clt_downloaded

:clt_ps_download
powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri '%CLT_URL%' -OutFile '%CLT_ZIP%'"

:clt_downloaded
if not exist "%CLT_ZIP%" goto clt_fail
if exist "%CLT_TMP%" rmdir /s /q "%CLT_TMP%" >nul 2>nul
powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -LiteralPath '%CLT_ZIP%' -DestinationPath '%CLT_TMP%' -Force"
if not exist "%CLT_TMP%\cmdline-tools" goto clt_fail
if exist "%SDK%\cmdline-tools\latest" rmdir /s /q "%SDK%\cmdline-tools\latest" >nul 2>nul
if not exist "%SDK%\cmdline-tools" mkdir "%SDK%\cmdline-tools" >nul 2>nul
move /y "%CLT_TMP%\cmdline-tools" "%SDK%\cmdline-tools\latest" >nul 2>nul
if not exist "%SDK%\cmdline-tools\latest\bin\sdkmanager.bat" goto clt_fail
del /q "%CLT_ZIP%" >nul 2>nul
exit /b 0

:clt_fail
echo     скачать и распаковать не удалось
exit /b 1

:check
rem %1 - путь внутри SDK, %2 - как называть в отчёте. Пропуск попадает в FAILED.
if exist "%SDK%\%~1" echo     %~2: есть
if not exist "%SDK%\%~1" echo     %~2: НЕТ
if not exist "%SDK%\%~1" set "FAILED=1"
goto :eof

:set_user_env
rem Только для новых консолей: текущая и уже открытые окна переменных не увидят.
echo   Прописываю ANDROID_HOME в переменные пользователя...
setx ANDROID_HOME "%SDK%" >nul 2>nul
if errorlevel 1 echo     не удалось: запустите от администратора или без /env
if not errorlevel 1 echo     ANDROID_HOME=%SDK%
goto :eof
