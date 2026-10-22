Aryan Overlay v2 - say exactly "Moon" -> comets + calligraphy "Yes Aryan" over your
real home screen (transparent, touch-through, lightly blurred) + "Yes Aryan" voice,
then ask your question; OpenRouter answers out loud. No Termux needed.

UPDATE STEPS (Termux, from your home folder):
  cp ~/storage/downloads/aryan_overlay.zip ~/        (use the new file name if it has (1))
  unzip -o aryan_overlay.zip
  cd aryan_overlay
  git add -A
  git commit -m "v2 listener"
  git push
Then GitHub -> Actions -> wait for green -> Artifacts -> AryanOverlay-apk.
UNINSTALL the old Aryan Overlay first, then install the new app-debug.apk.

FIRST RUN: allow Microphone, Display over other apps, Notifications.
Paste your OpenRouter key, tap Save. Phone settings: battery = Unrestricted for the app.
Say "Moon". Pull down the notification bar to see "Heard: ..." for debugging.
