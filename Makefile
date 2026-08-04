-include .env
export ANDROID_HOME JAVA_HOME
export PATH := $(JAVA_HOME)/bin:$(ANDROID_HOME)/platform-tools:$(PATH)

GRADLE    := ./gradlew --no-daemon
WEAR_DIR  := watch-vibe
PHONE_DIR := watch-vibe-control
WEAR_APK  := $(WEAR_DIR)/app/build/outputs/apk/debug/app-debug.apk
PHONE_APK := $(PHONE_DIR)/app/build/outputs/apk/debug/app-debug.apk

# Release (production) APK paths
WEAR_RELEASE_UNSIGNED  := $(WEAR_DIR)/app/build/outputs/apk/release/app-release-unsigned.apk
PHONE_RELEASE_UNSIGNED := $(PHONE_DIR)/app/build/outputs/apk/release/app-release-unsigned.apk
WEAR_RELEASE_APK       := $(WEAR_DIR)/app/build/outputs/apk/release/WatchVibe.apk
PHONE_RELEASE_APK      := $(PHONE_DIR)/app/build/outputs/apk/release/WatchVibeControl.apk
PHONE_RELEASE_TMP      := /data/local/tmp/WatchVibeControl.apk

# Keystore — override in .env
RELEASE_KEYSTORE           ?= release.keystore
RELEASE_KEYSTORE_PASSWORD  ?=
RELEASE_KEY_ALIAS          ?= release
RELEASE_KEY_PASSWORD       ?=

# ── Auto-detect devices ────────────────────────────────────
# Uses ro.build.characteristics=watch to identify Wear OS.
# Override with WEAR_SERIAL / PHONE_SERIAL in .env if needed.
# Re-evaluated on every invocation (lazy =) so TCP address
# changes are picked up automatically.
_ADB_DEVICES = $(shell adb devices 2>/dev/null | awk 'NR>1 && /device$$/ {print $$1}')

WEAR_SERIAL = $(or $(WEAR_SERIAL_ENV),$(shell for d in $(_ADB_DEVICES); do \
  adb -s $$d shell getprop ro.build.characteristics 2>/dev/null | grep -q watch && echo $$d && break; \
  done))
PHONE_SERIAL = $(or $(PHONE_SERIAL_ENV),$(shell for d in $(_ADB_DEVICES); do \
  [ "$$d" != "$(WEAR_SERIAL)" ] && echo $$d && break; \
  done))

# Capture .env overrides before they get clobbered by auto-detect
WEAR_SERIAL_ENV := $(WEAR_SERIAL)
PHONE_SERIAL_ENV := $(PHONE_SERIAL)

ADB_W   := adb -s $(WEAR_SERIAL)
ADB_P   := adb -s $(PHONE_SERIAL)
WEAR_PKG  := com.yieldinghartebeest13.watchvibe
PHONE_PKG := com.yieldinghartebeest13.watchvibe
WEAR_ACT  := $(WEAR_PKG)/.MainActivity
PHONE_ACT := $(PHONE_PKG)/.MainActivity

WEAR_LOG_RE  := VibeSvc|VibeAct|VibeWake|VibratorEngine
PHONE_LOG_RE := VibeWearDL

PHONE_TMP := /data/local/tmp/app-debug.apk

# ── Output helpers ─────────────────────────────────────────
# Use printf (not echo) for reliable escape-code rendering.
# Works both as a standalone recipe (@ prefix on caller) and
# inside shell pipelines (no @, since shell strips it).
_ok    = printf '  \033[32m✓\033[0m %s\n' '$(1)'
_fail  = printf '  \033[31m✗\033[0m %s\n' '$(1)' >&2
_info  = printf '  → %s\n' '$(1)'

.PHONY: all build build-wear build-phone build-release build-release-wear build-release-phone
.PHONY: sign-release guard-keystore
.PHONY: install install-wear install-phone install-release install-release-wear install-release-phone
.PHONY: launch launch-wear launch-phone stop stop-wear stop-phone
.PHONY: restart restart-wear restart-phone clear clear-wear clear-phone
.PHONY: logs logs-wear logs-phone debug debug-wear debug-phone
.PHONY: test test-wear test-phone test-e2e
.PHONY: tap-constant tap-intermittent tap-stop clear-logs send-test
.PHONY: clean info devices release

# ═══════════════════════════════════════════════
# Device discovery
# ═══════════════════════════════════════════════
devices:
	@echo "Auto-detected devices:"
	@echo "  Watch : $(if $(WEAR_SERIAL),$(WEAR_SERIAL),✗ not found)"
	@echo "  Phone : $(if $(PHONE_SERIAL),$(PHONE_SERIAL),✗ not found)"
	@echo ""
	@echo "Raw adb devices -l:"
	@adb devices -l 2>/dev/null | tail -n +2 | while read -r line; do \
		[ -z "$$line" ] && continue; \
		serial=$$(echo "$$line" | awk '{print $$1}'); \
		model=$$(echo "$$line" | grep -oP 'model:\S+' | cut -d: -f2); \
		type=$$(adb -s $$serial shell getprop ro.build.characteristics 2>/dev/null | grep -q watch && echo "⌚ WATCH" || echo "📱 PHONE"); \
		printf "  %s  %-20s  %s\n" "$$type" "$$serial" "$${model:-unknown}"; \
	done

# ═══════════════════════════════════════════════
# Build
# ═══════════════════════════════════════════════
all: build
build: build-wear build-phone

build-wear:
	@echo "🔨 Building watch-vibe..."
	@_log=$$(mktemp); \
	cd $(WEAR_DIR) && $(GRADLE) assembleDebug --console=plain >$$_log 2>&1; _rc=$$?; \
	if [ $$_rc -eq 0 ]; then \
		tail -1 $$_log; \
		$(call _ok,watch-vibe built); \
	else \
		echo "--- build output ---"; \
		grep -E '^[we]: |error:|FAILED|ERROR' $$_log || tail -30 $$_log; \
		echo "--- end ---"; \
		$(call _fail,watch-vibe build failed); \
		rm -f $$_log; exit $$_rc; \
	fi; rm -f $$_log

build-phone:
	@echo "🔨 Building watch-vibe-control..."
	@_log=$$(mktemp); \
	cd $(PHONE_DIR) && $(GRADLE) assembleDebug --console=plain >$$_log 2>&1; _rc=$$?; \
	if [ $$_rc -eq 0 ]; then \
		tail -1 $$_log; \
		$(call _ok,watch-vibe-control built); \
	else \
		echo "--- build output ---"; \
		grep -E '^[we]: |error:|FAILED|ERROR' $$_log || tail -30 $$_log; \
		echo "--- end ---"; \
		$(call _fail,watch-vibe-control build failed); \
		rm -f $$_log; exit $$_rc; \
	fi; rm -f $$_log

# ═══════════════════════════════════════════════
# Release (production signed)
# ═══════════════════════════════════════════════

guard-keystore:
	@if [ ! -f "$(RELEASE_KEYSTORE)" ]; then \
		$(call _fail,Keystore not found: $(RELEASE_KEYSTORE)); \
		echo "  Generate one: keytool -genkey -keystore $(RELEASE_KEYSTORE) ..."; \
		exit 1; \
	fi
	@if [ -z "$(RELEASE_KEYSTORE_PASSWORD)" ]; then \
		$(call _fail,RELEASE_KEYSTORE_PASSWORD not set); \
		echo "  Add it to .env"; \
		exit 1; \
	fi
	@if [ -z "$(RELEASE_KEY_PASSWORD)" ]; then \
		$(call _fail,RELEASE_KEY_PASSWORD not set); \
		echo "  Add it to .env"; \
		exit 1; \
	fi

build-release: build-release-wear build-release-phone

build-release-wear:
	@echo "🔨 Building watch-vibe (release)..."
	@_log=$$(mktemp); \
	cd $(WEAR_DIR) && $(GRADLE) assembleRelease --console=plain >$$_log 2>&1; _rc=$$?; \
	if [ $$_rc -eq 0 ]; then \
		tail -1 $$_log; \
		$(call _ok,watch-vibe release built); \
	else \
		echo "--- build output ---"; \
		grep -E '^[we]: |error:|FAILED|ERROR' $$_log || tail -30 $$_log; \
		echo "--- end ---"; \
		$(call _fail,watch-vibe release build failed); \
		rm -f $$_log; exit $$_rc; \
	fi; rm -f $$_log

build-release-phone:
	@echo "🔨 Building watch-vibe-control (release)..."
	@_log=$$(mktemp); \
	cd $(PHONE_DIR) && $(GRADLE) assembleRelease --console=plain >$$_log 2>&1; _rc=$$?; \
	if [ $$_rc -eq 0 ]; then \
		tail -1 $$_log; \
		$(call _ok,watch-vibe-control release built); \
	else \
		echo "--- build output ---"; \
		grep -E '^[we]: |error:|FAILED|ERROR' $$_log || tail -30 $$_log; \
		echo "--- end ---"; \
		$(call _fail,watch-vibe-control release build failed); \
		rm -f $$_log; exit $$_rc; \
	fi; rm -f $$_log

sign-release: guard-keystore build-release
	@echo "✍️  Signing APKs..."
	@$(ANDROID_HOME)/build-tools/34.0.0/apksigner sign \
		--ks "$(RELEASE_KEYSTORE)" \
		--ks-pass pass:"$(RELEASE_KEYSTORE_PASSWORD)" \
		--ks-key-alias "$(RELEASE_KEY_ALIAS)" \
		--key-pass pass:"$(RELEASE_KEY_PASSWORD)" \
		"$(WEAR_RELEASE_UNSIGNED)" \
		&& mv "$(WEAR_RELEASE_UNSIGNED)" "$(WEAR_RELEASE_APK)" \
		&& $(call _ok,signed WatchVibe.apk) \
		|| { $(call _fail,signing WatchVibe failed); exit 1; }
	@$(ANDROID_HOME)/build-tools/34.0.0/apksigner sign \
		--ks "$(RELEASE_KEYSTORE)" \
		--ks-pass pass:"$(RELEASE_KEYSTORE_PASSWORD)" \
		--ks-key-alias "$(RELEASE_KEY_ALIAS)" \
		--key-pass pass:"$(RELEASE_KEY_PASSWORD)" \
		"$(PHONE_RELEASE_UNSIGNED)" \
		&& mv "$(PHONE_RELEASE_UNSIGNED)" "$(PHONE_RELEASE_APK)" \
		&& $(call _ok,signed WatchVibeControl.apk) \
		|| { $(call _fail,signing WatchVibeControl failed); exit 1; }

install-release: install-release-wear install-release-phone

install-release-wear: guard-wear sign-release
	@echo "📥 Installing release on watch ($(WEAR_SERIAL))..."
	@if $(ADB_W) install -r "$(WEAR_RELEASE_APK)" 2>&1 | grep -q 'Success'; then \
		$(call _ok,release installed on watch); \
	else \
		sleep 2; \
		if $(ADB_W) install -r "$(WEAR_RELEASE_APK)" 2>&1 | grep -q 'Success'; then \
			$(call _ok,release installed on watch (retry)); \
		else \
			$(call _fail,release install on watch failed); exit 1; \
		fi; \
	fi

install-release-phone: guard-phone sign-release
	@echo "📥 Installing release on phone ($(PHONE_SERIAL))..."
	@$(ADB_P) connect $(PHONE_SERIAL) 2>/dev/null; true
	@if $(ADB_P) push "$(PHONE_RELEASE_APK)" $(PHONE_RELEASE_TMP) >/dev/null 2>&1; then \
		$(call _ok,pushed release APK); \
	else \
		$(call _fail,push failed); exit 1; \
	fi
	@if $(ADB_P) shell pm install -r -d $(PHONE_RELEASE_TMP) 2>&1 | grep -q 'Success'; then \
		$(call _ok,release installed on phone); \
	else \
		sleep 2; \
		if $(ADB_P) shell pm install -r -d $(PHONE_RELEASE_TMP) 2>&1 | grep -q 'Success'; then \
			$(call _ok,release installed on phone (retry)); \
		else \
			$(call _fail,release install on phone failed); exit 1; \
		fi; \
	fi

release: install-release launch
	@$(call _ok,release build + install + launch complete)

# ═══════════════════════════════════════════════
# Install
# ═══════════════════════════════════════════════
install: install-wear install-phone

install-wear: guard-wear build-wear
	@echo "📥 Installing on watch ($(WEAR_SERIAL))..."
	@if $(ADB_W) install -r $(WEAR_APK) 2>&1 | grep -q 'Success'; then \
		$(call _ok,installed on watch); \
	else \
		sleep 2; \
		if $(ADB_W) install -r $(WEAR_APK) 2>&1 | grep -q 'Success'; then \
			$(call _ok,installed on watch (retry)); \
		else \
			$(call _fail,install on watch failed); exit 1; \
		fi; \
	fi

install-phone: guard-phone build-phone
	@echo "📥 Installing on phone ($(PHONE_SERIAL))..."
	@$(ADB_P) connect $(PHONE_SERIAL) 2>/dev/null; true
	@if $(ADB_P) push $(PHONE_APK) $(PHONE_TMP) >/dev/null 2>&1; then \
		$(call _ok,pushed APK); \
	else \
		$(call _fail,push failed); exit 1; \
	fi
	@if $(ADB_P) shell pm install -r -d $(PHONE_TMP) 2>&1 | grep -q 'Success'; then \
		$(call _ok,installed on phone); \
	else \
		sleep 2; \
		if $(ADB_P) shell pm install -r -d $(PHONE_TMP) 2>&1 | grep -q 'Success'; then \
			$(call _ok,installed on phone (retry)); \
		else \
			$(call _fail,install on phone failed); exit 1; \
		fi; \
	fi

# ═══════════════════════════════════════════════
# Launch / Stop / Restart
# ═══════════════════════════════════════════════
launch: launch-wear launch-phone

launch-wear: guard-wear
	@echo "🚀 Launching watch..."
	@if $(ADB_W) shell am start -n $(WEAR_ACT) >/dev/null 2>&1; then \
		$(call _ok,watch launched); \
	else \
		$(call _fail,watch launch failed); exit 1; \
	fi

launch-phone: guard-phone
	@echo "🚀 Launching phone..."
	@if $(ADB_P) shell am start -n $(PHONE_ACT) >/dev/null 2>&1; then \
		$(call _ok,phone launched); \
	else \
		$(call _fail,phone launch failed); exit 1; \
	fi

stop: stop-wear stop-phone

stop-wear: guard-wear
	@echo "⏹  Stopping watch..."
	@$(ADB_W) shell am force-stop $(WEAR_PKG) 2>/dev/null; \
	$(call _ok,watch stopped)

stop-phone: guard-phone
	@echo "⏹  Stopping phone..."
	@$(ADB_P) shell am force-stop $(PHONE_PKG) 2>/dev/null; \
	$(call _ok,phone stopped)

restart: restart-wear restart-phone

restart-wear: stop-wear launch-wear
restart-phone: stop-phone launch-phone

# Full clear: uninstall, nuke Play Services cache, reinstall, launch
clear: clear-wear clear-phone

clear-wear: guard-wear stop-wear
	@echo "🧹 Clearing watch..."
	@$(ADB_W) uninstall $(WEAR_PKG) 2>/dev/null; true
	@$(ADB_W) shell am force-stop com.google.android.gms 2>/dev/null; \
	$(call _info,Play Services stopped)
	@sleep 2
	@if $(ADB_W) install -r $(WEAR_APK) 2>&1 | grep -q 'Success'; then \
		$(call _ok,watch reinstalled); \
	else \
		$(call _fail,watch reinstall failed); exit 1; \
	fi
	@if $(ADB_W) shell am start -n $(WEAR_ACT) >/dev/null 2>&1; then \
		$(call _ok,watch launched); \
	else \
		$(call _fail,watch launch failed); exit 1; \
	fi

clear-phone: guard-phone stop-phone
	@echo "🧹 Clearing phone..."
	@$(ADB_P) uninstall $(PHONE_PKG) 2>/dev/null; true
	@$(ADB_P) connect $(PHONE_SERIAL) 2>/dev/null; sleep 1
	@$(ADB_P) push $(PHONE_APK) $(PHONE_TMP) >/dev/null 2>&1; \
	$(call _ok,pushed APK)
	@if $(ADB_P) shell pm install -r -d $(PHONE_TMP) 2>&1 | grep -q 'Success'; then \
		$(call _ok,phone reinstalled); \
	else \
		sleep 2; \
		if $(ADB_P) shell pm install -r -d $(PHONE_TMP) 2>&1 | grep -q 'Success'; then \
			$(call _ok,phone reinstalled (retry)); \
		else \
			$(call _fail,phone reinstall failed); exit 1; \
		fi; \
	fi
	@if $(ADB_P) shell am start -n $(PHONE_ACT) >/dev/null 2>&1; then \
		$(call _ok,phone launched); \
	else \
		$(call _fail,phone launch failed); exit 1; \
	fi

# ═══════════════════════════════════════════════
# Logs
# ═══════════════════════════════════════════════
logs: logs-wear logs-phone

logs-wear: guard-wear
	@echo "📋 Streaming watch logs..."
	@$(ADB_W) logcat -c
	@$(ADB_W) logcat -v time 2>/dev/null | grep --color=always -E '$(WEAR_LOG_RE)'

logs-phone: guard-phone
	@echo "📋 Streaming phone logs..."
	@$(ADB_P) logcat -c
	@$(ADB_P) logcat -v time 2>/dev/null | grep --color=always -E '$(PHONE_LOG_RE)'

debug: debug-wear debug-phone

debug-wear: guard-wear
	@echo "─── Watch ───"
	@$(ADB_W) logcat -d -v time 2>/dev/null | grep -E '$(WEAR_LOG_RE)' | tail -20 || echo "  (no matching logs)"
	@echo "─── End ───"
	@echo ""

debug-phone: guard-phone
	@echo "─── Phone ───"
	@$(ADB_P) logcat -d -v time 2>/dev/null | grep -E '$(PHONE_LOG_RE)' | tail -20 || echo "  (no matching logs)"
	@echo "─── End ───"

debug-wear-all: guard-wear
	@echo "─── Watch (all buffers) ───"
	@$(ADB_W) logcat -d -b all -v time 2>/dev/null | grep -iE '$(WEAR_PKG)|VibeSvc|VibeAct|wearable.*fail|AppKey|/control' | tail -30 || echo "  (nothing)"
	@echo "─── End ───"

debug-phone-all: guard-phone
	@echo "─── Phone (all buffers) ───"
	@$(ADB_P) logcat -d -b all -v time 2>/dev/null | grep -iE '$(PHONE_PKG)|putData|sendMessage|dataItem|/control' | tail -30 || echo "  (nothing)"
	@echo "─── End ───"

# ═══════════════════════════════════════════════
# Quick test
# ═══════════════════════════════════════════════
clear-logs: guard-wear guard-phone
	@$(ADB_W) logcat -c
	@$(ADB_P) logcat -c
	@$(call _ok,logs cleared)

send-test: clear-logs
	@echo "═══ Quick test: Constant mode ═══"
	@$(ADB_P) shell input tap 270 1285
	@sleep 3
	@echo "─── Phone sent ───"
	@$(ADB_P) logcat -d -v time 2>/dev/null | grep 'VibeWearDL' | tail -5 || echo "  (nothing)"
	@echo "─── Watch received ───"
	@$(ADB_W) logcat -d -v time 2>/dev/null | grep -E '$(WEAR_LOG_RE)' | tail -5 || echo "  (nothing)"
	@echo "═══ Done ═══"

# ═══════════════════════════════════════════════
# Test
# ═══════════════════════════════════════════════
test: test-wear test-phone

test-wear: install-wear launch-wear
test-phone: install-phone launch-phone

# Full E2E: build → clear → launch → tap → logs
test-e2e: build
	@echo "═══ E2E Test ═══"
	@echo ""
	$(MAKE) --no-print-directory clear-wear
	$(MAKE) --no-print-directory clear-phone
	@sleep 4
	$(MAKE) --no-print-directory clear-logs
	@sleep 1
	@echo "─── Tapping Constant ───"
	@$(ADB_P) shell input tap 270 1285
	@sleep 3
	@echo "─── Phone sent ───"
	@$(ADB_P) logcat -d -v time 2>/dev/null | grep 'VibeWearDL' | tail -5 || echo "  (nothing)"
	@echo "─── Watch received ───"
	@$(ADB_W) logcat -d -v time 2>/dev/null | grep -E '$(WEAR_LOG_RE)' | tail -5 || echo "  (nothing)"
	@echo ""
	@echo "═══ E2E Done ═══"

# ═══════════════════════════════════════════════
# Quick taps (phone UI)
# ═══════════════════════════════════════════════
tap-constant: guard-phone
	@echo "👆 Tapping Constant..."
	@$(ADB_P) shell input tap 270 1285 && $(call _ok,tapped Constant)

tap-intermittent: guard-phone
	@echo "👆 Tapping Intermittent..."
	@$(ADB_P) shell input tap 738 1285 && $(call _ok,tapped Intermittent)

tap-stop: guard-phone
	@echo "👆 Tapping Stop..."
	@$(ADB_P) shell input tap 504 1627 && $(call _ok,tapped Stop)

# ═══════════════════════════════════════════════
# Misc
# ═══════════════════════════════════════════════
clean:
	@echo "🧹 Cleaning build dirs..."
	@cd $(WEAR_DIR) && $(GRADLE) clean --console=plain 2>&1 | tail -1
	@cd $(PHONE_DIR) && $(GRADLE) clean --console=plain 2>&1 | tail -1
	@$(call _ok,build dirs cleaned)

info:
	@echo "Wear  : $(if $(WEAR_SERIAL),$(WEAR_SERIAL),✗ not found)"
	@echo "Phone : $(if $(PHONE_SERIAL),$(PHONE_SERIAL),✗ not found)"
	@adb devices -l

# ═══════════════════════════════════════════════
# Pre-flight device guards
# ═══════════════════════════════════════════════
guard-wear:
	@if [ -z "$(WEAR_SERIAL)" ]; then \
		$(call _fail,No watch detected — is it connected?); \
		echo "  Run 'make devices' to see connected devices."; \
		exit 1; \
	fi
	@if ! $(ADB_W) shell echo ok >/dev/null 2>&1; then \
		$(call _fail,Watch $(WEAR_SERIAL) is offline or unauthorized); \
		echo "  Run 'make devices' to check status."; \
		exit 1; \
	fi

guard-phone:
	@if [ -z "$(PHONE_SERIAL)" ]; then \
		$(call _fail,No phone detected — is it connected?); \
		echo "  Run 'make devices' to see connected devices."; \
		exit 1; \
	fi
	@if ! $(ADB_P) shell echo ok >/dev/null 2>&1; then \
		$(call _fail,Phone $(PHONE_SERIAL) is offline or unauthorized); \
		echo "  Run 'make devices' to check status."; \
		exit 1; \
	fi
