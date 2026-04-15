@echo off
powershell -ExecutionPolicy Bypass -File "%~dp0scripts\export_resume_pdf.ps1"
if errorlevel 1 (
  echo.
  echo PDF export failed.
  pause
  exit /b %errorlevel%
)
echo.
echo PDF generated successfully.
pause
