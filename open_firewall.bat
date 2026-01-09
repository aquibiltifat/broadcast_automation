@echo off
echo Opening Windows Firewall for Python Backend (port 3002)...
netsh advfirewall firewall add rule name="Group Weaver Python Backend" dir=in action=allow protocol=TCP localport=3002
echo.
echo Done! The firewall rule has been added.
echo Your Android app should now be able to connect to http://192.168.1.68:3002
pause
