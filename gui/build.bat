@echo off
setlocal
cd /d "%~dp0"

where javac >nul 2>nul || (echo [ERROR] javac not found on PATH. Install JDK 11+. & exit /b 1)
where jar   >nul 2>nul || (echo [ERROR] jar not found on PATH. Install JDK 11+.   & exit /b 1)

if not exist build mkdir build

echo Compiling...
javac -encoding UTF-8 -d build CquptVpn.java || exit /b 1

echo Packaging...
jar cfm CquptVpn.jar manifest.txt -C build . || exit /b 1

rmdir /s /q build
echo Done: %cd%\CquptVpn.jar
endlocal
