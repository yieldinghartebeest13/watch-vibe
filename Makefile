-include .env
export ANDROID_HOME JAVA_HOME
export PATH := $(JAVA_HOME)/bin:$(ANDROID_HOME)/platform-tools:$(PATH)

GRADLE    := ./gradlew --no-daemon
WEAR_DIR  := wear-vibe
PHONE_DIR := vibe-control
WEAR_APK  := $(WEAR_DIR)/app/build/outputs/apk/debug/app-debug.apk
PHONE_APK := $(PHONE_DIR)/app/build/outputs/apk/debug/app-debug.apk

ifeq ($(WEAR_SERIAL),)
  WEAR_SERIAL := $(shell adb devices -l 2>/dev/null | grep -i 'watch\|r11' | head -1 | awk '{print $$1}')
endif
ifeq ($(PHONE_SERIAL),)
  PHONE_SERIAL := $(shell adb devices -l 2>/dev/null | grep -iv 'watch\|r11' | grep -oP '^\S+' | tail -1)
endif

ADB_W   := adb -s $(WEAR_SERIAL)
ADB_P   := adb -s $(PHONE_SERIAL)
WEAR_PKG  := com.example.vibecontrol
PHONE_PKG := com.example.vibecontrol
WEAR_ACT  := $(WEAR_PKG)/.MainActivity
PHONE_ACT := $(PHONE_PKG)/.MainActivity

# Log grep patterns
WEAR_LOG_RE := VibeSvc|VibeAct|VibeDataLayer|VibratorEngine
PHONE_LOG_RE := VibeWearDL|VibeControl

PHONE_TMP := /data/local/tmp/app-debug.apk

.PHONY: all build build-wear build-phone install install-wear install-phone
.PHONY: launch launch-wear launch-phone stop stop-wear stop-phone
.PHONY: restart restart-wear restart-phone clear clear-wear clear-phone
.PHONY: logs logs-wear logs-phone debug debug-wear debug-phone
.PHONY: test test-wear test-phone test-e2e
.PHONY: tap-constant tap-intermittent tap-stop clear-logs send-test
.PHONY: clean info

# ═══════════════════════════════════════════════
# Build
# ═══════════════════════════════════════════════
all: build
build: build-wear build-phone

build-wear:
	cd $(WEAR_DIR) && $(GRADLE) assembleDebug
build-phone:
	cd $(PHONE_DIR) && $(GRADLE) assembleDebug

# ═══════════════════════════════════════════════
# Install
# ═══════════════════════════════════════════════
install: install-wear install-phone

install-wear: build-wear
	$(ADB_W) install -r $(WEAR_APK) || (sleep 2; $(ADB_W) install -r $(WEAR_APK))
install-phone: build-phone
	$(ADB_P) connect $(PHONE_SERIAL) 2>/dev/null
	$(ADB_P) push $(PHONE_APK) $(PHONE_TMP)
	$(ADB_P) shell pm install -r -d $(PHONE_TMP) || (sleep 2; $(ADB_P) shell pm install -r -d $(PHONE_TMP))

# ═══════════════════════════════════════════════
# Launch / Stop / Restart
# ═══════════════════════════════════════════════
launch: launch-wear launch-phone

launch-wear:
	$(ADB_W) shell am start -n $(WEAR_ACT)
launch-phone:
	$(ADB_P) shell am start -n $(PHONE_ACT)

stop: stop-wear stop-phone

stop-wear:
	$(ADB_W) shell am force-stop $(WEAR_PKG)
stop-phone:
	$(ADB_P) shell am force-stop $(PHONE_PKG)

restart: restart-wear restart-phone

restart-wear: stop-wear launch-wear
restart-phone: stop-phone launch-phone

# nuke Play Services cache + reinstall (for stale AppKey issues)
clear: clear-wear clear-phone

clear-wear: stop-wear
	$(ADB_W) uninstall $(WEAR_PKG) 2>/dev/null; true
	$(ADB_W) shell am force-stop com.google.android.gms
	sleep 2
	$(ADB_W) install -r $(WEAR_APK)
	$(ADB_W) shell am start -n $(WEAR_ACT)

clear-phone: stop-phone
	$(ADB_P) uninstall $(PHONE_PKG) 2>/dev/null; true
	$(ADB_P) connect $(PHONE_SERIAL) 2>/dev/null; sleep 1
	$(ADB_P) push $(PHONE_APK) $(PHONE_TMP)
	$(ADB_P) shell pm install -r -d $(PHONE_TMP) || (sleep 2; $(ADB_P) shell pm install -r -d $(PHONE_TMP))
	$(ADB_P) shell am start -n $(PHONE_ACT)

# ═══════════════════════════════════════════════
# Logs
# ═══════════════════════════════════════════════
logs: logs-wear logs-phone

logs-wear:
	$(ADB_W) logcat -c
	$(ADB_W) logcat -v time | grep -E '$(WEAR_LOG_RE)'

logs-phone:
	$(ADB_P) logcat -c
	$(ADB_P) logcat -v time | grep -E '$(PHONE_LOG_RE)'

# dump recent logs (non-streaming)
debug: debug-wear debug-phone

debug-wear:
	@echo "─── Watch ───"
	@$(ADB_W) logcat -d -v time 2>/dev/null | grep -E '$(WEAR_LOG_RE)' | tail -20
	@echo "─── End ───"
	@echo ""

debug-phone:
	@echo "─── Phone ───"
	@$(ADB_P) logcat -d -v time 2>/dev/null | grep -E '$(PHONE_LOG_RE)' | tail -20
	@echo "─── End ───"

# dump all recent app logs (no filter, all buffers)
debug-wear-all:
	@echo "─── Watch (all) ───"
	@$(ADB_W) logcat -d -b all -v time 2>/dev/null | grep -iE '$(WEAR_PKG)|VibeSvc|VibeAct|wearable.*fail|AppKey|/control' | tail -30
	@echo "─── End ───"

debug-phone-all:
	@echo "─── Phone (all) ───"
	@$(ADB_P) logcat -d -b all -v time 2>/dev/null | grep -iE '$(PHONE_PKG)|putData|sendMessage|dataItem|/control' | tail -30
	@echo "─── End ───"

# ═══════════════════════════════════════════════
# Quick test: clear logs → tap → show both
# ═══════════════════════════════════════════════
clear-logs:
	$(ADB_W) logcat -c
	$(ADB_P) logcat -c

send-test: clear-logs
	@echo "=== Tapping Constant ==="
	$(ADB_P) shell input tap 270 1285
	@sleep 4
	@echo "─── Phone sent ───"
	@$(ADB_P) logcat -d -v time 2>/dev/null | grep 'VibeWearDL' | tail -5
	@echo "─── Watch received ───"
	@$(ADB_W) logcat -d -v time 2>/dev/null | grep 'VibeDataLayer' | tail -5
	@echo "=== Done ==="

# ═══════════════════════════════════════════════
# Test
# ═══════════════════════════════════════════════
test: test-wear test-phone

test-wear: install-wear launch-wear
test-phone: install-phone launch-phone

# Full E2E: build → clear both → launch → tap → show logs
test-e2e: build
	@echo "=== Clearing & restarting both ==="
	$(MAKE) clear-wear
	$(MAKE) clear-phone
	sleep 4
	@echo "=== Clearing logs ==="
	$(ADB_W) logcat -c; $(ADB_P) logcat -c; sleep 1
	@echo "=== Tapping Constant ==="
	$(ADB_P) shell input tap 270 1285
	sleep 4
	@echo "=== Phone sent ==="
	@$(ADB_P) logcat -d -v time 2>/dev/null | grep 'VibeWearDL' | tail -5
	@echo "=== Watch received ==="
	@$(ADB_W) logcat -d -v time 2>/dev/null | grep 'VibeDataLayer' | tail -5
	@echo "=== Done ==="

# ═══════════════════════════════════════════════
# Quick taps (phone UI buttons)
# ═══════════════════════════════════════════════
tap-constant:
	$(ADB_P) shell input tap 270 1285
tap-intermittent:
	$(ADB_P) shell input tap 738 1285
tap-stop:
	$(ADB_P) shell input tap 504 1627

# ═══════════════════════════════════════════════
# Misc
# ═══════════════════════════════════════════════
clean:
	cd $(WEAR_DIR) && $(GRADLE) clean
	cd $(PHONE_DIR) && $(GRADLE) clean

info:
	@echo "Wear  : $(WEAR_SERIAL)"
	@echo "Phone : $(PHONE_SERIAL)"
	@adb devices -l
