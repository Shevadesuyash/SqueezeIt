@echo off
title SqueezeIt Launcher
echo.
echo  ==========================================
echo    SqueezeIt - Local File Compressor
echo    Starting application...
echo  ==========================================
echo.

cd /d "%~dp0"

:: Build the classpath from Maven local repo
set M2=%USERPROFILE%\.m2\repository
set FX=%M2%\org\openjfx
set VER=21.0.2

set MODPATH=%FX%\javafx-graphics\%VER%\javafx-graphics-%VER%-win.jar;%FX%\javafx-base\%VER%\javafx-base-%VER%-win.jar;%FX%\javafx-controls\%VER%\javafx-controls-%VER%-win.jar;%FX%\javafx-fxml\%VER%\javafx-fxml-%VER%-win.jar

set TM=%M2%\com\twelvemonkeys
set PDF=%M2%\org\apache\pdfbox
set SLF=%M2%\org\slf4j

set CP=target\classes
set CP=%CP%;%FX%\javafx-controls\%VER%\javafx-controls-%VER%-win.jar
set CP=%CP%;%FX%\javafx-graphics\%VER%\javafx-graphics-%VER%-win.jar
set CP=%CP%;%FX%\javafx-base\%VER%\javafx-base-%VER%-win.jar
set CP=%CP%;%FX%\javafx-fxml\%VER%\javafx-fxml-%VER%-win.jar
set CP=%CP%;%PDF%\pdfbox\3.0.2\pdfbox-3.0.2.jar
set CP=%CP%;%PDF%\pdfbox-io\3.0.2\pdfbox-io-3.0.2.jar
set CP=%CP%;%PDF%\fontbox\3.0.2\fontbox-3.0.2.jar
set CP=%CP%;%M2%\commons-logging\commons-logging\1.3.0\commons-logging-1.3.0.jar
set CP=%CP%;%TM%\imageio\imageio-jpeg\3.12.0\imageio-jpeg-3.12.0.jar
set CP=%CP%;%TM%\imageio\imageio-core\3.12.0\imageio-core-3.12.0.jar
set CP=%CP%;%TM%\imageio\imageio-metadata\3.12.0\imageio-metadata-3.12.0.jar
set CP=%CP%;%TM%\common\common-lang\3.12.0\common-lang-3.12.0.jar
set CP=%CP%;%TM%\common\common-io\3.12.0\common-io-3.12.0.jar
set CP=%CP%;%TM%\common\common-image\3.12.0\common-image-3.12.0.jar
set CP=%CP%;%TM%\imageio\imageio-webp\3.12.0\imageio-webp-3.12.0.jar
set CP=%CP%;%SLF%\slf4j-simple\2.0.13\slf4j-simple-2.0.13.jar
set CP=%CP%;%SLF%\slf4j-api\2.0.13\slf4j-api-2.0.13.jar

java ^
  --module-path "%MODPATH%" ^
  --add-modules javafx.controls,javafx.fxml ^
  --add-opens javafx.base/com.sun.javafx.event=ALL-UNNAMED ^
  --add-opens javafx.graphics/com.sun.javafx.application=ALL-UNNAMED ^
  -cp "%CP%" ^
  com.squeezeit.MainApp

if errorlevel 1 (
  echo.
  echo  ERROR: Application failed to start.
  echo  See the message above for details.
  echo.
  pause
)
