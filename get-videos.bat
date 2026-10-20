@echo off
rem Downloads videos for Luisa's tablet into Videos\Luisa Videos (outside OneDrive, so they don't sync).
rem Format: MP4, H.264 up to 720p (what the old SM-T350 plays smoothly), plus a .jpg thumbnail for each.
rem Paste a video or playlist link, or drag a .txt file of links (one per line) onto this script.
set "OUT=%USERPROFILE%\Videos\Luisa Videos"
if not exist "%OUT%" mkdir "%OUT%"

if not "%~1"=="" (
  call :get -a "%~1"
  goto done
)

:ask
echo.
set "URL="
set /p "URL=Paste a YouTube link (or press Enter to finish): "
if not defined URL goto done
call :get "%URL%"
goto ask

:get
yt-dlp -f "bv*[vcodec^=avc1][height<=720]+ba[ext=m4a]/b[ext=mp4][height<=720]/b[ext=mp4]" --merge-output-format mp4 ^
  --write-thumbnail --convert-thumbnails jpg --no-mtime --download-archive "%OUT%\.downloaded.txt" ^
  -P "%OUT%" -o "%%(title).80B.%%(ext)s" %*
exit /b

:done
echo.
echo Videos are in: %OUT%
echo Copy the files into the "Luisa Videos" folder on the tablet's SD card.
start "" "%OUT%"
pause
