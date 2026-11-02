#!/bin/sh
# Tests tools/plume-install.sh against a fake adb (the file "adb" next to this one), so that no TV is needed.
# Run:  sh tools/plume-install-test/run.sh      (needs python3; prints the failed checks and exits 1 if there are any)
#       TESTSH=dash sh tools/plume-install-test/run.sh   runs the script under another shell
# Each test starts from a fresh fake TV. The fake adb is described at the top of the file "adb".

HERE=$(cd "$(dirname "$0")" && pwd)
SCRIPT=$HERE/../plume-install.sh
WORK=$(mktemp -d) || exit 2
trap 'rm -rf "$WORK"' EXIT
mkdir "$WORK/bin"
cp "$HERE/adb" "$WORK/bin/adb"
printf '#!/bin/sh\nexit 0\n' > "$WORK/bin/sleep" # the script waits for the TV; the fake TV answers at once
chmod +x "$WORK/bin/adb" "$WORK/bin/sleep"
PATH=$WORK/bin:$PATH
FAKE_STATE=$WORK/tv.json
export PATH FAKE_STATE
STATE=/data/local/tmp/plume-install.state
SVC=dev.plume/dev.plume.HomeOverride
OTHER=com.example/com.example.Other
N=0 BAD=0

# new KIND [JSON]: a fresh TV of that kind, with nothing set up.
new() {
  rm -f "$FAKE_STATE"
  unset FAKE_INIT FAKE_FAIL FAKE_FAIL_SILENT FAKE_NOALARM FAKE_HOME_BROKEN
  FAKE_KIND=$1; export FAKE_KIND
  [ -z "$2" ] || { FAKE_INIT=$2; export FAKE_INIT; }
}
# run ARGS...: the script with -y on the fake TV; its output is in $OUT and its exit status in $RC.
run() { OUT=$(${TESTSH:-sh} "$SCRIPT" -y "$@" 10.0.0.2 2>&1); RC=$?; }
# st KEY: a value from the fake TV's state.
st() { python3 -c 'import json, sys; v = json.load(open(sys.argv[1]))[sys.argv[2]]; print(v if not isinstance(v, dict) else v.get(sys.argv[3], ""), end="")' "$FAKE_STATE" "$1" "$2"; }
record() { st files $STATE; }
check() { # check "what" 'shell test'
  N=$((N + 1))
  if ! eval "$2"; then BAD=$((BAD + 1)); printf 'FAIL: %s\n    test: %s\n' "$1" "$2"; printf '%s\n' "$OUT" | sed 's/^/    | /'; fi
}
has() { printf '%s\n' "$1" | grep -qF -- "$2"; }

# ---- a TV with nothing set up -------------------------------------------------------------------------------------
new fire7
run -n
check "-n changes nothing" '[ $RC = 0 ] && [ -z "$(st enabled)" ] && [ "$(st granted)" = False ]'
check "-n says what it found" 'has "$OUT" "Fire TV (Fire OS 7, Android 9)"'

new fire7
run
check "install on Fire OS 7 works" '[ $RC = 0 ] && ! has "$OUT" FAILED'
check "install: service listed, grant given" 'has "$(st enabled)" $SVC && [ "$(st granted)" = True ]'
check "install: Plume set min_futurity" 'has "$(st global)" min_futurity=0'
check "install: the record lists service and grant" 'has "$(record)" service=1 && has "$(record)" granted=1'
run --undo
check "undo works" '[ $RC = 0 ] && ! has "$OUT" FAILED'
check "undo: service off, grant back, alarm back" '[ -z "$(st enabled)" ] && [ "$(st granted)" = False ] && ! has "$(st global)" min_futurity'
check "undo: the APK stays installed and the record is gone" '[ "$(st installed)" = True ] && [ -z "$(record)" ] && has "$OUT" "still installed"'
run --undo
check "undo twice says there is nothing to undo" '[ $RC = 0 ] && has "$OUT" "Nothing to undo"'

new fire8
run
check "Fire OS 8 install works" '[ $RC = 0 ] && has "$OUT" "Fire OS 8" && has "$(st global)" min_futurity=0'

new fire14
run
check "Fire OS 14 fast Home is capability gated" '[ $RC = 0 ] && [ "$(st marker)" = 34 ] && [ "$(st futurity)" = 0 ] && [ "$(st exact)" = allow ]'
run --undo
check "modern Fire TV undo restores capabilities" '[ $RC = 0 ] && [ "$(st marker)" = null ] && [ "$(st futurity)" = null ] && [ "$(st exact)" = default ] && [ "$(st restricted)" = default ]'

new fire14
FAKE_FAIL_SILENT='device_config get'; export FAKE_FAIL_SILENT
run
check "a failed configuration read cannot lose the original timing" '[ $RC != 0 ] && [ "$(st futurity)" = null ] && [ "$(st marker)" = null ] && ! has "$(record)" futurity='
unset FAKE_FAIL_SILENT
run --undo
check "undo after a refused capability read restores appops" '[ $RC = 0 ] && [ "$(st exact)" = default ] && [ "$(st restricted)" = default ]'

new fire14 '{"restricted": "ignore", "exact": "deny", "futurity": "3000", "marker": "30"}'
run
run
run --undo
check "repeated modern setup keeps the original baseline" '[ $RC = 0 ] && [ "$(st restricted)" = ignore ] && [ "$(st exact)" = deny ] && [ "$(st futurity)" = 3000 ] && [ "$(st marker)" = 30 ]'

new fire14 '{"futurity": "broken"}'
run
check "invalid DeviceConfig is left unchanged" '[ $RC != 0 ] && [ "$(st futurity)" = broken ] && [ "$(st marker)" = null ]'

new fire14
run
FAKE_FAIL_SILENT='device_config get'; export FAKE_FAIL_SILENT
run --undo
check "failed alarm read during undo keeps its recovery value" '[ $RC != 0 ] && has "$(record)" futurity=null && [ "$(st futurity)" = 0 ]'
unset FAKE_FAIL_SILENT
run --undo
check "undo retries safely after an unreadable setting" '[ $RC = 0 ] && [ "$(st futurity)" = null ] && [ -z "$(record)" ]'

new fire14
run
FAKE_FAIL_SILENT='settings get global plume_home_alarm_sdk'; export FAKE_FAIL_SILENT
run --undo
check "failed marker read during undo keeps the marker record" '[ $RC != 0 ] && has "$(record)" marker=null && [ "$(st marker)" = 34 ]'

new google '{"native": true, "sole": true}'
run
check "sole native Home needs no grant or accessibility service" '[ $RC = 0 ] && [ -z "$(st enabled)" ] && [ "$(st granted)" = False ]'

new google '{"native": true}'
run
check "reported Google TV default with another Home still receives its workaround" '[ $RC = 0 ] && has "$(st enabled)" $SVC'

new fire7 '{"native": true}'
run
check "reported Fire TV default with another Home still receives its workaround" '[ $RC = 0 ] && has "$(st enabled)" $SVC'

for kind in android xiaomi xiaomi_google fire16; do
  new "$kind" '{"native": true}'
  run
  check "$kind supports coexistence without disabling stock" '[ $RC = 0 ] && has "$(st enabled)" $SVC && [ "$(st disabled)" = "[]" ]'
  new "$kind" '{"native": true, "sole": true}'
  run
  check "$kind also supports an optional sole native launcher" '[ $RC = 0 ] && [ -z "$(st enabled)" ] && [ "$(st granted)" = False ]'
done

new fire7 '{"native": true, "sole": true}'
run --disable-stock-launcher
check "an absent stock launcher is never disabled" '[ $RC != 0 ] && [ "$(st disabled)" = "[]" ] && [ -z "$(st enabled)" ]'

new google '{"bound_dump": "User state[attributes:{id=0, currentUser=true}\n Bound services:{}\n Enabled services:{dev.plume/.HomeOverride}\nUser state[attributes:{id=10, currentUser=false}\n Bound services:{dev.plume/.HomeOverride}"}'
run
check "configured or other-user services do not count as bound" '[ $RC != 0 ] && has "$OUT" "does not show"'

new fire7 '{"bound_dump": "User state[attributes:{id=0, currentUser=true}\n services:{\n Service[label=Plume: Home button,feedbackType[]]\n}"}'
run
check "legacy multiline bound service can use its exact English label" '[ $RC = 0 ]'

new google '{"bound_dump": "User state[attributes:{id=0, currentUser=true}\n Bound services:{Service[label=Plume : bouton Accueil,feedbackType[]]}"}'
run
check "bound service can use its exact French label" '[ $RC = 0 ]'

new google '{"bound_dump": "User state[attributes:{id=0, currentUser=true}\n Bound services:{Service[label=Plume: Home button impostor,feedbackType[]]}"}'
run
check "another service with a similar label is never accepted" '[ $RC != 0 ]'

new google '{"bound_dump": "unexpected output Plume"}'
run
run --undo
check "unknown service state keeps recovery record" '[ $RC != 0 ] && has "$(record)" service=1'

new google
run
check "Google TV install works" '[ $RC = 0 ] && ! has "$OUT" FAILED && has "$(st enabled)" $SVC'
run --undo
check "Google TV undo works" '[ $RC = 0 ] && [ -z "$(st enabled)" ]'

# ---- a service that is on already ---------------------------------------------------------------------------------
new fire7 "{\"enabled\": \"$OTHER:$SVC\", \"granted\": true}"
run
check "an existing service is left alone" '[ $RC = 0 ] && ! has "$(record)" service=1 && ! has "$(record)" granted=1'
run --undo
check "undo leaves an existing service and its grant on" '[ $RC = 0 ] && has "$(st enabled)" $SVC && has "$(st enabled)" $OTHER && [ "$(st granted)" = True ]'
check "undo says that the service was not turned on by the script" 'has "$OUT" "Nothing to undo" && has "$OUT" "stays on"'

new fire7 "{\"enabled\": \"$OTHER\"}"
run
check "another service stays in the list after install" '[ $RC = 0 ] && has "$(st enabled)" $OTHER && has "$(st enabled)" $SVC'
run --undo
check "another service stays in the list after undo" '[ $RC = 0 ] && [ "$(st enabled)" = $OTHER ]'

new fire7 "{\"enabled\": \"$SVC\"}"
run
run --undo
check "a service that was on without a grant: the grant given by the script is taken back, the service stays" 'has "$(st enabled)" $SVC && [ "$(st granted)" = False ]'

new fire7 "{\"global\": \"min_futurity=3000,other=1\"}"
run
run --undo
check "a min_futurity the TV had comes back" '[ "$(st global)" = "min_futurity=3000,other=1" ]'

new fire7 "{\"global\": \"min_futurity=0\"}"
run
run --undo
check "a min_futurity that was 0 before is not reported as a problem" '[ $RC = 0 ] && ! has "$OUT" FAILED'

# ---- a Plume without the alarm change -----------------------------------------------------------------------------
new fire7
FAKE_NOALARM=1; export FAKE_NOALARM
run
check "no alarm change: installed, with a note and not a failure" '[ $RC = 0 ] && ! has "$OUT" FAILED && has "$OUT" "does not have the alarm change"'
run --undo
check "no alarm change: undo works" '[ $RC = 0 ] && [ -z "$(st enabled)" ]'

# ---- commands that fail -------------------------------------------------------------------------------------------
new fire7
FAKE_FAIL="pm grant"; export FAKE_FAIL
run
check "a refused grant fails the run and leaves no record" '[ $RC = 1 ] && has "$OUT" "the grant was refused" && ! has "$(record)" granted=1'

new fire7
FAKE_FAIL_SILENT="pm grant"; export FAKE_FAIL_SILENT
run
check "a grant that fails with only an exit status is caught" '[ $RC = 1 ] && has "$OUT" "the grant was refused" && [ "$(st granted)" = False ]'

new fire7
FAKE_FAIL_SILENT="settings put secure enabled_accessibility_services"; export FAKE_FAIL_SILENT
run
check "a setting the TV did not keep fails the run and leaves no service record" '[ $RC = 1 ] && has "$OUT" "did not keep the setting" && ! has "$(record)" service=1 && [ -z "$(st enabled)" ]'

new fire7
FAKE_FAIL="accessibility_enabled"; export FAKE_FAIL
run
check "accessibility_enabled that is refused is caught" '[ $RC = 1 ] && has "$OUT" "did not keep the setting"'

new fire7
FAKE_FAIL="service=1"; export FAKE_FAIL
run
check "no recovery record for the service: the service is not switched on" '[ $RC = 1 ] && has "$OUT" "recovery record" && [ -z "$(st enabled)" ]'

new fire7
run
FAKE_FAIL="pm revoke"; export FAKE_FAIL
run --undo
check "a grant that cannot be taken back fails undo and keeps the record" '[ $RC = 1 ] && has "$OUT" "could not take the grant back" && has "$(record)" granted=1'
unset FAKE_FAIL
run --undo
check "undo can be run again after a failure" '[ $RC = 0 ] && [ "$(st granted)" = False ] && [ -z "$(record)" ]'

# ---- the stock launcher -------------------------------------------------------------------------------------------
new fire7
run --disable-stock-launcher
check "stock launcher off: Plume answers Home" '[ $RC = 0 ] && has "$OUT" "Home opens Plume" && has "$(st disabled)" com.amazon.tv.launcher'
check "stock launcher off: the record names it" 'has "$(record)" stock=com.amazon.tv.launcher'
run --undo
check "undo turns the stock launcher on again" '[ $RC = 0 ] && [ "$(st disabled)" = "[]" ] && [ -z "$(st enabled)" ]'

new fire7
run
check "without the option the stock launcher stays on" '[ "$(st disabled)" = "[]" ]'

new fire7
FAKE_FAIL="stock="; export FAKE_FAIL
run --disable-stock-launcher
check "no recovery record: the stock launcher is not turned off" '[ $RC = 1 ] && has "$OUT" "recovery record" && [ "$(st disabled)" = "[]" ]'

new fire7
FAKE_FAIL_SILENT="pm disable-user"; export FAKE_FAIL_SILENT
run --disable-stock-launcher
check "a launcher that cannot be turned off leaves no record" '[ $RC = 1 ] && [ "$(st disabled)" = "[]" ] && ! has "$(record)" stock='

new fire7
FAKE_HOME_BROKEN=1; export FAKE_HOME_BROKEN
run --disable-stock-launcher
check "Home does not open Plume: the stock launcher is on again" '[ $RC = 1 ] && has "$OUT" "launcher is on again" && [ "$(st disabled)" = "[]" ] && ! has "$(record)" stock='
check "Home does not open Plume: the service stays on, as asked" 'has "$(st enabled)" $SVC'

new fire7
FAKE_HOME_BROKEN=1; FAKE_FAIL="pm enable"; export FAKE_HOME_BROKEN FAKE_FAIL
run --disable-stock-launcher
check "a launcher that cannot be restored says how to do it by hand" '[ $RC = 1 ] && has "$OUT" "adb shell pm enable com.amazon.tv.launcher" && has "$(record)" stock='

new fire7
FAKE_FAIL="pm grant"; export FAKE_FAIL
run --disable-stock-launcher
check "a step that failed first: the stock launcher stays on" '[ $RC = 1 ] && [ "$(st disabled)" = "[]" ]'

new google
run --disable-stock-launcher
check "Google TV replacement disables stock and recovery without accessibility grants" '[ $RC = 0 ] && has "$(st disabled)" com.google.android.apps.tv.launcherx && has "$(st disabled)" com.google.android.tungsten.setupwraith && [ -z "$(st enabled)" ] && [ "$(st granted)" = False ]'

# ---- the questions the script asks -------------------------------------------------------------------------------
new fire7 '{"installed": false}'
run
check "Plume not installed and no -a: the script stops and changes nothing" '[ $RC != 0 ] && has "$OUT" "not installed" && [ -z "$(st enabled)" ]'

new fire7
OUT=$(printf 'n\n' | sh "$SCRIPT" 10.0.0.2 2>&1); RC=$?
check "answering n changes nothing" '[ $RC = 0 ] && has "$OUT" "Nothing changed" && [ -z "$(st enabled)" ] && [ "$(st granted)" = False ]'

# Google TV replacement must be reversible without changing pre-existing disabled packages.
new xiaomi_google '{"fallback": true}'
run --disable-stock-launcher
check "Google TV replacement accepts Android fallback Home" '[ $RC = 0 ] && [ "$(st role)" = dev.plume ] && has "$(record)" google_home='
run --undo
check "Google TV undo restores packages, original role and default enabled state" '[ $RC = 0 ] && [ "$(st disabled)" = "[]" ] && [ "$(st role)" = com.google.android.apps.tv.launcherx ] && [ "$(st package_states com.google.android.apps.tv.launcherx)" = 0 ] && [ -z "$(record)" ]'

new google '{"package_states": {"com.google.android.apps.tv.launcherx": 1}, "fallback": true}'
run --disable-stock-launcher
run --disable-stock-launcher
run --undo
check "repeated replacement retains the original explicitly enabled state" '[ $RC = 0 ] && [ "$(st package_states com.google.android.apps.tv.launcherx)" = 1 ]'

new google '{"disabled": ["com.google.android.tungsten.setupwraith"]}'
run --disable-stock-launcher
run --undo
check "undo preserves recovery disabled before installer use" '[ $RC = 0 ] && [ "$(st disabled)" = "['"'"'com.google.android.tungsten.setupwraith'"'"']" ]'

new google '{"setup_complete": "0"}'
run --disable-stock-launcher
check "replacement refuses unfinished setup without changing packages" '[ $RC != 0 ] && [ "$(st disabled)" = "[]" ] && [ -z "$(record)" ]'

new google
run -n --disable-stock-launcher
check "Google TV replacement dry run changes nothing" '[ $RC = 0 ] && [ "$(st disabled)" = "[]" ] && [ -z "$(record)" ]'

new google
FAKE_FAIL=google_home=; export FAKE_FAIL
run --disable-stock-launcher
check "missing recovery record prevents Google TV package changes" '[ $RC != 0 ] && [ "$(st disabled)" = "[]" ]'

new google
FAKE_FAIL_SILENT='pm disable-user --user 0 com.google.android.apps.tv.launcherx'; export FAKE_FAIL_SILENT
run --disable-stock-launcher
check "stock disable failure restores recovery and retains the old Home role" '[ $RC != 0 ] && [ "$(st disabled)" = "[]" ] && [ "$(st role)" = com.google.android.apps.tv.launcherx ] && [ -z "$(record)" ]'

new google
FAKE_FAIL_SILENT='cmd package set-home-activity'; export FAKE_FAIL_SILENT
run --disable-stock-launcher
check "Home assignment failure restores both packages" '[ $RC != 0 ] && [ "$(st disabled)" = "[]" ] && [ -z "$(record)" ]'

new google '{"extra_home": "com.other/.Home"}'
run --disable-stock-launcher
check "an unexpected remaining launcher aborts and rolls back" '[ $RC != 0 ] && [ "$(st disabled)" = "[]" ] && [ -z "$(record)" ]'

new google '{"fallback": true}'
FAKE_HOME_BROKEN=1; export FAKE_HOME_BROKEN
run --disable-stock-launcher
check "failed Home key test rolls back Google TV replacement" '[ $RC != 0 ] && [ "$(st disabled)" = "[]" ] && [ -z "$(record)" ]'

new google
run --disable-stock-launcher
FAKE_FAIL_SILENT='pm default-state'; export FAKE_FAIL_SILENT
run --undo
check "failed Google TV restore keeps its recovery record" '[ $RC != 0 ] && has "$(record)" google_home= && has "$(record)" google_pkg='
unset FAKE_FAIL_SILENT
run --undo
check "Google TV undo can retry after a refused package restore" '[ $RC = 0 ] && [ "$(st disabled)" = "[]" ] && [ -z "$(record)" ]'

new google '{"native": true, "sole": true, "fallback": true}'
run
check "existing sole Plume plus Android fallback needs no new grant" '[ $RC = 0 ] && [ -z "$(st enabled)" ] && [ "$(st granted)" = False ]'

echo "$N checks, $BAD failed"
[ $BAD = 0 ]
