@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem
@rem SPDX-License-Identifier: Apache-2.0
@rem

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  gdx-ui-markup-preview startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables, and ensure extensions are enabled
setlocal EnableExtensions

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem This is normally unused
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%..

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem Add default JVM options here. You can also use JAVA_OPTS and GDX_UI_MARKUP_PREVIEW_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS="--enable-native-access=ALL-UNNAMED"

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH. 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

"%COMSPEC%" /c exit 1

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

echo. 1>&2
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME% 1>&2
echo. 1>&2
echo Please set the JAVA_HOME variable in your environment to match the 1>&2
echo location of your Java installation. 1>&2

"%COMSPEC%" /c exit 1

:execute
@rem Setup the command line

set CLASSPATH=%APP_HOME%\lib\gdx-ui-markup-preview-0.1.0.jar;%APP_HOME%\lib\gdx-ui-markup-harness-0.1.0.jar;%APP_HOME%\lib\gdx-ui-markup-0.1.0.jar;%APP_HOME%\lib\harness-lwjgl3-1.0.0.jar;%APP_HOME%\lib\gdx-backend-lwjgl3-1.14.2.jar;%APP_HOME%\lib\harness-scene2d-1.0.0.jar;%APP_HOME%\lib\harness-mcp-1.0.0.jar;%APP_HOME%\lib\harness-protocol-1.0.0.jar;%APP_HOME%\lib\harness-core-1.0.0.jar;%APP_HOME%\lib\jackson-core-2.22.1.jar;%APP_HOME%\lib\jackson-databind-2.22.1.jar;%APP_HOME%\lib\gdx-platform-1.14.2-natives-desktop.jar;%APP_HOME%\lib\slf4j-nop-2.0.17.jar;%APP_HOME%\lib\gdx-1.14.2.jar;%APP_HOME%\lib\lwjgl-glfw-3.3.3.jar;%APP_HOME%\lib\lwjgl-glfw-3.3.3-natives-linux.jar;%APP_HOME%\lib\lwjgl-glfw-3.3.3-natives-linux-arm32.jar;%APP_HOME%\lib\lwjgl-glfw-3.3.3-natives-linux-arm64.jar;%APP_HOME%\lib\lwjgl-glfw-3.3.3-natives-macos.jar;%APP_HOME%\lib\lwjgl-glfw-3.3.3-natives-macos-arm64.jar;%APP_HOME%\lib\lwjgl-glfw-3.3.3-natives-windows.jar;%APP_HOME%\lib\lwjgl-glfw-3.3.3-natives-windows-x86.jar;%APP_HOME%\lib\lwjgl-jemalloc-3.3.3.jar;%APP_HOME%\lib\lwjgl-jemalloc-3.3.3-natives-linux.jar;%APP_HOME%\lib\lwjgl-jemalloc-3.3.3-natives-linux-arm32.jar;%APP_HOME%\lib\lwjgl-jemalloc-3.3.3-natives-linux-arm64.jar;%APP_HOME%\lib\lwjgl-jemalloc-3.3.3-natives-macos.jar;%APP_HOME%\lib\lwjgl-jemalloc-3.3.3-natives-macos-arm64.jar;%APP_HOME%\lib\lwjgl-jemalloc-3.3.3-natives-windows.jar;%APP_HOME%\lib\lwjgl-jemalloc-3.3.3-natives-windows-x86.jar;%APP_HOME%\lib\lwjgl-openal-3.3.3.jar;%APP_HOME%\lib\lwjgl-openal-3.3.3-natives-linux.jar;%APP_HOME%\lib\lwjgl-openal-3.3.3-natives-linux-arm32.jar;%APP_HOME%\lib\lwjgl-openal-3.3.3-natives-linux-arm64.jar;%APP_HOME%\lib\lwjgl-openal-3.3.3-natives-macos.jar;%APP_HOME%\lib\lwjgl-openal-3.3.3-natives-macos-arm64.jar;%APP_HOME%\lib\lwjgl-openal-3.3.3-natives-windows.jar;%APP_HOME%\lib\lwjgl-openal-3.3.3-natives-windows-x86.jar;%APP_HOME%\lib\lwjgl-opengl-3.3.3.jar;%APP_HOME%\lib\lwjgl-opengl-3.3.3-natives-linux.jar;%APP_HOME%\lib\lwjgl-opengl-3.3.3-natives-linux-arm32.jar;%APP_HOME%\lib\lwjgl-opengl-3.3.3-natives-linux-arm64.jar;%APP_HOME%\lib\lwjgl-opengl-3.3.3-natives-macos.jar;%APP_HOME%\lib\lwjgl-opengl-3.3.3-natives-macos-arm64.jar;%APP_HOME%\lib\lwjgl-opengl-3.3.3-natives-windows.jar;%APP_HOME%\lib\lwjgl-opengl-3.3.3-natives-windows-x86.jar;%APP_HOME%\lib\lwjgl-stb-3.3.3.jar;%APP_HOME%\lib\lwjgl-stb-3.3.3-natives-linux.jar;%APP_HOME%\lib\lwjgl-stb-3.3.3-natives-linux-arm32.jar;%APP_HOME%\lib\lwjgl-stb-3.3.3-natives-linux-arm64.jar;%APP_HOME%\lib\lwjgl-stb-3.3.3-natives-macos.jar;%APP_HOME%\lib\lwjgl-stb-3.3.3-natives-macos-arm64.jar;%APP_HOME%\lib\lwjgl-stb-3.3.3-natives-windows.jar;%APP_HOME%\lib\lwjgl-stb-3.3.3-natives-windows-x86.jar;%APP_HOME%\lib\lwjgl-3.3.3.jar;%APP_HOME%\lib\lwjgl-3.3.3-natives-linux.jar;%APP_HOME%\lib\lwjgl-3.3.3-natives-linux-arm32.jar;%APP_HOME%\lib\lwjgl-3.3.3-natives-linux-arm64.jar;%APP_HOME%\lib\lwjgl-3.3.3-natives-macos.jar;%APP_HOME%\lib\lwjgl-3.3.3-natives-macos-arm64.jar;%APP_HOME%\lib\lwjgl-3.3.3-natives-windows.jar;%APP_HOME%\lib\lwjgl-3.3.3-natives-windows-x86.jar;%APP_HOME%\lib\jlayer-1.0.1-gdx.jar;%APP_HOME%\lib\jorbis-0.0.17.jar;%APP_HOME%\lib\mcp-2.0.0.jar;%APP_HOME%\lib\mcp-json-jackson3-2.0.0.jar;%APP_HOME%\lib\mcp-core-2.0.0.jar;%APP_HOME%\lib\json-schema-validator-3.0.0.jar;%APP_HOME%\lib\slf4j-api-2.0.17.jar;%APP_HOME%\lib\gdx-jnigen-loader-2.5.2.jar;%APP_HOME%\lib\jackson-dataformat-yaml-3.0.3.jar;%APP_HOME%\lib\jackson-core-3.0.3.jar;%APP_HOME%\lib\jackson-databind-3.0.3.jar;%APP_HOME%\lib\jackson-annotations-2.22.jar;%APP_HOME%\lib\reactor-core-3.7.0.jar;%APP_HOME%\lib\itu-1.14.0.jar;%APP_HOME%\lib\reactive-streams-1.0.4.jar;%APP_HOME%\lib\snakeyaml-engine-2.10.jar


@rem Execute gdx-ui-markup-preview
@rem endlocal doesn't take effect until after the line is parsed and variables are expanded
@rem which allows us to clear the local environment before executing the java command
endlocal & "%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GDX_UI_MARKUP_PREVIEW_OPTS%  -classpath "%CLASSPATH%" dev.gdx.markup.preview.PreviewApp %* & call :exitWithErrorLevel

:exitWithErrorLevel
@rem Use "%COMSPEC%" /c exit to allow operators to work properly in scripts
"%COMSPEC%" /c exit %ERRORLEVEL%
