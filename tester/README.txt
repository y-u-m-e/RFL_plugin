RFL Audit - test build
======================

1. Install Java 11 or newer if you don't have it: https://adoptium.net (Temurin 17 is fine).
2. Unzip this folder anywhere and double-click run.bat (Mac/Linux: run ./run.sh in a terminal).
   A RuneLite window opens with RFL Audit already loaded (look for it in the side panel).

Jagex account login
-------------------
This RuneLite can't log in to a Jagex account by itself. Do this once:
  - Open "RuneLite (configure)" from the Start menu.
  - In "Client arguments" add:  --insecure-write-credentials
  - Save, then launch RuneLite through the Jagex Launcher as usual and close it once you're in.
  - Now run.bat logs you straight in.
Remove the argument again afterwards if you like; the saved login keeps working until it expires.
Legacy (non-Jagex) accounts just log in normally.

Sending results back
--------------------
Everything the plugin records stays on your computer, in:
  %USERPROFILE%\.runelite\rfl
The RFL Audit side panel has an "Open folder" button for it. Zip that folder and send it over.
