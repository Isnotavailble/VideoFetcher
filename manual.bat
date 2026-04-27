@echo off
setlocal enabledelayedexpansion

:: ==========================================
:: CONFIGURATION (ဗဟိုလမ်းကြောင်း သတ်မှတ်ချက်)
:: ==========================================
set "TOOLS_PATH=C:\Users\AnyaWalker\Desktop\AndriodStudioCLI"
set "SDK_ROOT=%TOOLS_PATH%\android-sdk"
set "GRADLE_BIN=%TOOLS_PATH%\gradle-8.7\bin\gradle.bat"
set "SDK_MANAGER=%SDK_ROOT%\cmdline-tools\latest\bin\sdkmanager.bat"

:MENU
cls
echo ==========================================
echo       ANDROID MODULAR BUILDER SYSTEM
echo ==========================================
echo  1. Setup Environment (Properties Files)
echo  2. Check/Install SDK Components
echo  3. Build Debug APK (Offline Mode)
echo  4. Build Debug APK (Online Mode - First Time)
echo  5. Exit
echo ==========================================
set /p choice="Enter your choice (1-5): "

if "%choice%"=="1" goto SETUP
if "%choice%"=="2" goto CHECK_SDK
if "%choice%"=="3" goto BUILD_OFFLINE
if "%choice%"=="4" goto BUILD_ONLINE
if "%choice%"=="5" exit
goto MENU

:SETUP
echo [*] Configuring Project Properties...
set "SDK_DIR_PROP=%TOOLS_PATH:\=/%/android-sdk"
echo sdk.dir=%SDK_DIR_PROP%> local.properties
(
    echo org.gradle.java.home=C:/Program Files/Java/jdk-21
    echo org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
    echo org.gradle.workers.max=4
    echo org.gradle.parallel=false
    echo android.useAndroidX=true
    echo android.nonTransitiveRClass=true
) > gradle.properties
echo [V] Setup Complete!
pause
goto MENU

:CHECK_SDK
echo [*] Checking SDK Components...
if not exist "%SDK_MANAGER%" (
    echo [X] ERROR: sdkmanager.bat not found at %SDK_MANAGER%
    pause & goto MENU
)
set "PT=%SDK_ROOT%\platform-tools"
set "BT=%SDK_ROOT%\build-tools\34.0.0"
set "PL=%SDK_ROOT%\platforms\android-34"

if exist "%PT%\" (echo [V] platform-tools OK) else (echo [!] Downloading platform-tools... & call "%SDK_MANAGER%" "platform-tools")
if exist "%BT%\" (echo [V] build-tools OK) else (echo [!] Downloading build-tools... & call "%SDK_MANAGER%" "build-tools;34.0.0")
if exist "%PL%\" (echo [V] platforms OK) else (echo [!] Downloading platforms... & call "%SDK_MANAGER%" "platforms;android-34")
pause
goto MENU

:BUILD_OFFLINE
echo [*] Building APK (Offline Mode)...
call "%GRADLE_BIN%" assembleDebug --offline
if %ERRORLEVEL% EQU 0 (echo [V] BUILD SUCCESSFUL!) else (echo [X] BUILD FAILED!)
pause
goto MENU

:BUILD_ONLINE
echo [*] Building APK (Online Mode - Downloading Dependencies)...
call "%GRADLE_BIN%" assembleDebug
if %ERRORLEVEL% EQU 0 (echo [V] BUILD SUCCESSFUL!) else (echo [X] BUILD FAILED!)
pause
goto MENU