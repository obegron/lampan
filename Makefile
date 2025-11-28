APP_ID := com.egron.lampan
ACTIVITY := .MainActivity

.PHONY: all build install run clean logcat

all: build

build:
	./gradlew assembleDebug

install:
	./gradlew installDebug

run: install
	adb shell am start -n $(APP_ID)/$(ACTIVITY)

clean:
	./gradlew clean

test:
	./gradlew test

logcat:
	adb logcat -s Lampan
