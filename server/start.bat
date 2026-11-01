@echo off
title Penthouse SMP
:start
java -Xms2G -Xmx4G -jar paper.jar --nogui
echo.
echo A szerver leallt. Ujrainditas 5 masodperc mulva (Ctrl+C a kilepeshez)...
timeout /t 5
goto start
