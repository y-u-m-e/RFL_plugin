RFL Audit - test build
======================

1. Install Java 17 if you don't have it:
   https://adoptium.net  (click the big download button, install, done)

2. Unzip this folder anywhere.

3. Double-click run.bat.
   RuneLite opens with RFL Audit already in the side panel.
   (Mac/Linux: open a terminal in this folder and run  ./run.sh )


Logging in with a Jagex account (one time only)
-----------------------------------------------
This RuneLite can't do the Jagex login screen by itself, so borrow your normal login once:

  a. In the Start menu, open "RuneLite (configure)".
  b. In the "Client arguments" box, type:  --insecure-write-credentials
  c. Click Save.
  d. Start RuneLite from the Jagex Launcher like normal, then close it.
  e. Now run.bat logs you in.

Old-style (non-Jagex) accounts can skip this and just log in.


Sending results back
--------------------
In the RFL Audit side panel, click "Open folder".
Zip that folder and send it back.
