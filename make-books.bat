@echo off
rem Converts the books in books\luisa, books\orlando and books\both for the app (into books\shelf).
rem Needs uv (winget install astral-sh.uv). Afterwards: npm run sync in luisa-tablet for her tablet.
cd /d "%~dp0"
uv run make-books.py
pause
