#!/usr/bin/env bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/home/user/android-sdk
export LANG=C.UTF-8 LC_ALL=C.UTF-8
export PATH=$JAVA_HOME/bin:$PATH
cd /home/user/CallShift
./gradlew "$@" --no-daemon --max-workers=1 --console=plain
