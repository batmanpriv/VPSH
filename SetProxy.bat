@echo off
title VPSH - VPN Proxy Share Client
setlocal enabledelayedexpansion

set "SCRIPT_NAME=%~nx0"
set "COMMAND=%~1"
set "ARG1=%~2"
set "ARG2=%~3"

if "%COMMAND%"=="" goto INTERACTIVE
if /I "%COMMAND%"=="connect" goto CONNECT
if /I "%COMMAND%"=="disconnect" goto DISCONNECT
if /I "%COMMAND%"=="status" goto STATUS
if /I "%COMMAND%"=="test" goto TEST
if /I "%COMMAND%"=="help" goto HELP
if /I "%COMMAND%"=="-h" goto HELP
if /I "%COMMAND%"=="--help" goto HELP
goto HELP

:INTERACTIVE
cls
echo.
echo ============================================================
echo    VPSH - VPN Proxy Share Client
echo    Windows Proxy Manager
echo ============================================================
echo.
echo  [1] Connect to Proxy
echo  [2] Disconnect Proxy
echo  [3] Show Status
echo  [4] Test Connection
echo  [5] Exit
echo.
set /p "CHOICE=  Choose an option: "

if "%CHOICE%"=="1" goto CONNECT_INTERACTIVE
if "%CHOICE%"=="2" goto DISCONNECT
if "%CHOICE%"=="3" goto STATUS
if "%CHOICE%"=="4" goto TEST
if "%CHOICE%"=="5" goto END
goto INTERACTIVE

:CONNECT_INTERACTIVE
cls
echo.
echo ============================================================
echo    Connect to Proxy
echo ============================================================
echo.
set /p "PROXY_IP=  Enter Proxy IP Address: "
if "%PROXY_IP%"=="" goto CONNECT_INTERACTIVE

set /p "PROXY_PORT=  Enter Proxy Port: "
if "%PROXY_PORT%"=="" goto CONNECT_INTERACTIVE

set /p "PROXY_USER=  Enter Username (optional): "
set /p "PROXY_PASS=  Enter Password (optional): "

if not "%PROXY_USER%"=="" (
    set "PROXY_STRING=%PROXY_USER%:%PROXY_PASS%@%PROXY_IP%:%PROXY_PORT%"
) else (
    set "PROXY_STRING=%PROXY_IP%:%PROXY_PORT%"
)

goto DO_CONNECT

:CONNECT
if "%ARG1%"=="" goto HELP
if "%ARG2%"=="" goto HELP

set "PROXY_IP=%ARG1%"
set "PROXY_PORT=%ARG2%"
set "PROXY_USER="
set "PROXY_PASS="

if not "%~4"=="" set "PROXY_USER=%~4"
if not "%~5"=="" set "PROXY_PASS=%~5"

if not "%PROXY_USER%"=="" (
    set "PROXY_STRING=%PROXY_USER%:%PROXY_PASS%@%PROXY_IP%:%PROXY_PORT%"
) else (
    set "PROXY_STRING=%PROXY_IP%:%PROXY_PORT%"
)

:DO_CONNECT
cls
echo.
echo ============================================================
echo    Connecting to Proxy
echo ============================================================
echo.

reg add "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v ProxyEnable /t REG_DWORD /d 1 /f >nul 2>&1
reg add "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v ProxyServer /t REG_SZ /d "%PROXY_IP%:%PROXY_PORT%" /f >nul 2>&1
reg add "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v ProxyOverride /t REG_SZ /d "localhost;127.0.0.1;*.local;<local>" /f >nul 2>&1

setx HTTP_PROXY "http://%PROXY_STRING%" >nul 2>&1
setx HTTPS_PROXY "http://%PROXY_STRING%" >nul 2>&1
setx FTP_PROXY "http://%PROXY_STRING%" >nul 2>&1
setx ALL_PROXY "http://%PROXY_STRING%" >nul 2>&1

if not "%PROXY_USER%"=="" (
    setx PROXY_USER "%PROXY_USER%" >nul 2>&1
    setx PROXY_PASS "%PROXY_PASS%" >nul 2>&1
)

rundll32.exe inetcpl.cpl,ClearMyTracksByProcess 8 >nul 2>&1
rundll32.exe inetcpl.cpl,ClearMyTracksByProcess 1 >nul 2>&1

echo  [OK] Proxy enabled successfully
echo.
echo  Address: %PROXY_IP%:%PROXY_PORT%
if not "%PROXY_USER%"=="" echo  User: %PROXY_USER%
echo.
echo  Commands:
echo    Test:      %SCRIPT_NAME% test
echo    Status:    %SCRIPT_NAME% status
echo    Disconnect:%SCRIPT_NAME% disconnect
echo.
pause
goto END

:DISCONNECT
cls
echo.
echo ============================================================
echo    Disconnecting Proxy
echo ============================================================
echo.

reg add "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v ProxyEnable /t REG_DWORD /d 0 /f >nul 2>&1
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v ProxyServer /f >nul 2>&1

setx HTTP_PROXY "" >nul 2>&1
setx HTTPS_PROXY "" >nul 2>&1
setx FTP_PROXY "" >nul 2>&1
setx ALL_PROXY "" >nul 2>&1
setx PROXY_USER "" >nul 2>&1
setx PROXY_PASS "" >nul 2>&1

echo  [OK] Proxy disabled successfully
echo.
pause
goto END

:STATUS
cls
echo.
echo ============================================================
echo    Proxy Status
echo ============================================================
echo.

for /f "tokens=3" %%A in ('reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v ProxyEnable 2^>nul ^| find "ProxyEnable"') do set "ENABLED=%%A"

if "%ENABLED%"=="0x1" (
    echo  Status: [CONNECTED]
    echo.
    for /f "tokens=3*" %%A in ('reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v ProxyServer 2^>nul ^| find "ProxyServer"') do (
        echo  Proxy Server: %%B
    )
    
    for /f "tokens=3*" %%A in ('reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v ProxyOverride 2^>nul ^| find "ProxyOverride"') do (
        echo  Bypass List: %%B
    )
    
    echo.
    echo  Environment Variables:
    echo    HTTP_PROXY  = %HTTP_PROXY%
    echo    HTTPS_PROXY = %HTTPS_PROXY%
) else (
    echo  Status: [DISCONNECTED]
)
echo.
pause
goto END

:TEST
cls
echo.
echo ============================================================
echo    Testing Proxy Connection
echo ============================================================
echo.

echo  Checking current proxy settings...
for /f "tokens=3" %%A in ('reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v ProxyEnable 2^>nul ^| find "ProxyEnable"') do set "ENABLED=%%A"

if not "%ENABLED%"=="0x1" (
    echo  [FAIL] Proxy is not enabled
    echo.
    echo  Run: %SCRIPT_NAME% connect IP PORT
    pause
    goto END
)

for /f "tokens=3*" %%A in ('reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v ProxyServer 2^>nul ^| find "ProxyServer"') do set "PROXY_SERVER=%%B"

if "%PROXY_SERVER%"=="" (
    echo  [FAIL] Proxy server not configured
    pause
    goto END
)

echo  Testing connection through %PROXY_SERVER%...
echo.

set "TEST_URL=https://api.ipify.org"
echo  Getting your public IP...

set "RESPONSE="
for /f "delims=" %%i in ('curl -s --proxy "http://%PROXY_SERVER%" "%TEST_URL%" 2^>nul') do set "RESPONSE=%%i"

if "%RESPONSE%"=="" (
    echo  [FAIL] Connection failed!
    echo.
    echo  Troubleshooting:
    echo    - Check proxy server is running
    echo    - Verify IP address is correct
    echo    - Check firewall settings
    echo    - Verify network connectivity
) else (
    echo  [SUCCESS] Connection successful!
    echo.
    echo  Your Public IP: %RESPONSE%
    echo.
    echo  [OK] Proxy is working correctly
)

echo.
pause
goto END

:HELP
cls
echo.
echo ============================================================
echo    VPSH - VPN Proxy Share Client v2.0
echo    Windows Proxy Manager
echo ============================================================
echo.
echo  USAGE:
echo.
echo    %SCRIPT_NAME% connect IP PORT [USER] [PASS]
echo    %SCRIPT_NAME% disconnect
echo    %SCRIPT_NAME% status
echo    %SCRIPT_NAME% test
echo    %SCRIPT_NAME% help
echo.
echo  EXAMPLES:
echo.
echo    %SCRIPT_NAME% connect 192.168.1.100 8888
echo    %SCRIPT_NAME% connect 10.0.0.1 1080 myuser mypass
echo    %SCRIPT_NAME% disconnect
echo    %SCRIPT_NAME% status
echo.
echo  INTERACTIVE MODE:
echo.
echo    Double-click %SCRIPT_NAME% or run without arguments
echo.
pause
goto END

:END
endlocal
exit /b 0
