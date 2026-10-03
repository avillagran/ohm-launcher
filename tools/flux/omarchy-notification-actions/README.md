# Omarchy notification action buttons

The Omarchy shell installed on October 2, 2026 advertised notification action
support but rendered only the notification body and close button. A body click
invoked an action named `default`, or focused the application and dismissed the
notification. Flux's touchpad approval uses two explicit actions, `Approve` and
`Deny`, with request-specific identifiers. Clicking the notification body could
therefore dismiss the request without approving it.

This patch adds labeled buttons using Omarchy's existing button component. It
keeps body-click behavior and resolves every explicit click against the exact
live notification owner and current action identifier. QObject references stay
outside ListModel roles. Restored/history notifications carry no action owner;
stale clicks, reused server IDs and notifications closed before deferred display
cannot invoke an action. No synthetic D-Bus approval or credential bypass is
provided.

Only a patch and its regression tests are stored here. Omarchy's full plugin is
obtained from the installation, rather than copied into this repository.

## Reproduce validation

Node 18 or newer with `node:test`, Git, and the three source files named in the
patch are sufficient for the regression tests. Copy the installed **unmodified**
plugin into a temporary directory, then run from the Ohm repository root:

```sh
ohm_notification_stage=$(mktemp -d)
cp -a /usr/share/omarchy/shell/plugins/notifications/. "$ohm_notification_stage/"
git -C "$ohm_notification_stage" apply --check "$PWD/tools/flux/omarchy-notification-actions/notification-actions.patch"
git -C "$ohm_notification_stage" apply "$PWD/tools/flux/omarchy-notification-actions/notification-actions.patch"
OHM_FLUX_NOTIFICATION_PLUGIN="$ohm_notification_stage" node --test tools/flux/omarchy-notification-actions/notification-actions.test.cjs
```

To test an already patched clone, set `OHM_FLUX_NOTIFICATION_PLUGIN` directly to
its directory and omit patch application. The ten tests cover scalar snapshots,
restored metadata, exact action selection, unknown/default actions, stale owner
tokens, server-ID reuse during invocation, sender closure, action updates,
counter exhaustion and closure before deferred insertion. They execute the
patched service functions with controlled models; they do not grant input access.

On the deployed system, Qt's `qmlformat --ignore-settings` parsed `Service.qml`
and `components/NotificationCard.qml` successfully, and `qmllint` returned exit 0
with zero errors. Static warnings about dynamic theme properties and outer QML
IDs remained. For static imports, create a temporary `imports/qs/` directory
with `Commons` and `Ui` symlinks to the corresponding installed shell directories,
then pass its parent as `qmllint -I imports`. Parse without writing formatted code:

```sh
/usr/lib/qt6/bin/qmlformat --ignore-settings "$ohm_notification_stage/Service.qml" >/dev/null
/usr/lib/qt6/bin/qmlformat --ignore-settings "$ohm_notification_stage/components/NotificationCard.qml" >/dev/null
```

Static parsing and lint did not detect an initial assignment to `Flow`'s
read-only `implicitHeight`. A real shell restart rejected that component and
prevented the notification service from loading. The corrected patch lets
`Flow` calculate its implicit height. The runtime check constructs the patched
card with two fixture actions in an isolated offscreen Quickshell config:

```sh
bash tools/flux/omarchy-notification-actions/runtime-check.sh "$ohm_notification_stage" /usr/share/omarchy/shell
```

This check disconnects both D-Bus addresses and display variables and uses a
private runtime/cache directory. It has no notification server, visible window or action handler. It
detects component-construction errors that static validation missed and does not
approve a real Flux request. A successful running desktop service and physical
button interaction still require separate observations.

The isolated check passed against the installed corrected card with exit 0.
Reintroducing the previous read-only assignment in a temporary copy made the
same check fail with exit 1 and the native `implicitHeight` error. Applying the
stored patch to the three original files reproduced the corrected installed
candidate byte for byte, and all ten Node tests passed again.

## Apply through a personal plugin

The official `omarchy plugin clone omarchy.notifications` command creates
`~/.config/omarchy/plugins/<username>.notifications` and records
`omarchy.clonedFrom: omarchy.notifications` in its manifest. It copies dependencies
and preserves routing of the original plugin's IPC calls. Keep this generated
manifest; the patch changes only three implementation files.

For a new installation, clone through that command, then disable the clone with
`omarchy plugin disable <username>.notifications` while preparing the replacement.
This restores the built-in service. Copy the personal clone into a hidden staging
directory under `~/.config/omarchy/plugins/`, apply and validate the patch there,
and preserve the original clone as a hidden backup before moving the stage into
its place. Hidden stages avoid the shell's automatic plugin reload while files
are being changed. Run `omarchy-shell shell rescanPlugins`, then
`omarchy plugin enable <username>.notifications` once the complete clone is ready.
Do not overwrite an existing personal clone containing other changes.

The patch was validated against these original SHA-256 values:

| Source | SHA-256 |
| --- | --- |
| `Service.qml` | `11665542e70df80ccd3c785a6143dee2daefd2082e4f75c740adc2c9947cf7c2` |
| `NotificationLogic.js` | `99e82df6524ee1ee1d52673b2a1496f62b14b220288f186c41fd8deed77a69bd` |
| `components/NotificationCard.qml` | `e1b5bae943d2e3b495741bc845b4fd981c274be538ea01799c46026f9db0fe49` |

If these sources differ, review applicability before applying; the patch is
specific to this Omarchy shell implementation.

## Validation and rollback

The patched personal clone was installed through the official clone command,
staged replacement and shell rescan. The built-in notification plugin was retained.
The installed implementation matched these validated source hashes:

| Source | SHA-256 |
| --- | --- |
| `Service.qml` | `a5c713ee712dd03c1bd7508683f18379aafdb7c9151b99595e3d1946a2ebfd17` |
| `NotificationLogic.js` | `809adb3bd72f46e59e5ed17548b69401ae4bcdb6b2449a5a2527209bb7d611c5` |
| `components/NotificationCard.qml` | `601ce6daf215884db74781295f9eb25278d46043ec0583e7424dfc0027b1b3c0` |

To restore the original built-in service, disable the personal clone:

```sh
omarchy plugin disable <username>.notifications
```

Replace the placeholder with the generated plugin ID. The command retains the
personal source for inspection. Keep a backup of any existing custom clone before
replacement. A fresh Flux request is required after a notification-service change;
restored notification buttons do not convey approval.

After the corrected card loaded, physical validation observed an authenticated
Approve action from the real notification server. The user completed the phone’s
private credential prompt; controlled opposite pointer gestures moved and restored
the desktop cursor. Closing the touchpad retained pairing and connectivity and
left persistent remote input disabled. See the [integration validation scope](../../../docs/flux-interoperability.md)
for the distinction between physical observations and isolated automated checks.
