@echo off
setlocal
cd /d "%~dp0"
echo ============================================
echo   SMART PARKING MANAGEMENT SYSTEM
echo ============================================
where java >nul 2>nul
if errorlevel 1 (
  echo ERROR: Java was not found. Install JDK 17 or later.
  pause
  exit /b 1
)
echo Java version:
java -version
echo.
echo Starting the Java backend from this project folder...
echo Open http://localhost:8081 in your browser.
echo Keep this window open. Press Ctrl+C to stop the server.
echo.
java ParkingServer.java 8081
if errorlevel 1 (
  echo.
  echo The server did not start. If port 8081 is already in use, close the old server window
  echo or edit this file to use another port and open that same port in your browser.
  pause
)
endlocal
