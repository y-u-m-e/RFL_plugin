#!/bin/sh
cd "$(dirname "$0")"
exec java -ea -cp "lib/*:lib" com.rfl.RflPluginLauncher
