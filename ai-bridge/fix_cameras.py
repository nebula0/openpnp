"""Check that OpenPnP finds its USB cameras, and fix their device paths when Windows changed them.

The LumenPnP cameras have no serial number, so Windows names each by its place in the USB
tree (e.g. 8&1654aaea). That path can change after a reboot or replugging, even in the same
port; OpenPnP then finds no camera (log: "No camera found with ID ...", red cross instead of
a picture) and homing fails with "FIDUCIAL-HOME no matches found". Run with OpenPnP closed:

    python ai-bridge/fix_cameras.py            # check only
    python ai-bridge/fix_cameras.py --apply    # write the new paths to machine.xml and commit
    python ai-bridge/fix_cameras.py --swap     # Top and Bottom came out the wrong way round

Both cameras are the same model, so which one is Top cannot be told from Windows: after
--apply, start OpenPnP, home and look at the Top camera. It must show the bed (at the home
fiducial: the cross-hair target) and change when the head moves. If it shows a still, blurred
view up at the head, or the Bottom view shows the bed upside down, run --swap. A successful
homing alone proves nothing: it once matched a fiducial in the wrong camera's picture.

Only the Python standard library is used.
"""
import argparse
import json
import os
import re
import subprocess
import sys

CAMERA_CLASS = 'org.openpnp.machine.reference.camera.OpenPnpCaptureCamera'
DEVICE_CLASS_GUID = '{65e8773d-8f56-11d0-a3b9-00a0c9223196}'


def powershell(command):
    return subprocess.run(['powershell', '-NoProfile', '-Command', command],
                          capture_output=True, text=True).stdout


def openpnp_running():
    out = powershell("Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | "
                     "Where-Object { $_.CommandLine -match 'openpnp-gui' } | Measure-Object | "
                     "Select-Object -ExpandProperty Count")
    return out.strip() not in ('', '0')


def present_cameras():
    """Windows USB cameras that are plugged in now: {path: unique id OpenPnP would use}."""
    out = powershell("Get-PnpDevice -Class Camera,Image -PresentOnly -ErrorAction SilentlyContinue"
                     " | Where-Object InstanceId -like 'USB\\*' | Select-Object FriendlyName,InstanceId"
                     " | ConvertTo-Json")
    devices = json.loads(out) if out.strip() else []
    if isinstance(devices, dict):
        devices = [devices]
    cameras = {}
    for d in devices:
        # USB\VID_058F&PID_0363&MI_00\8&1654AAEA&0&0000
        # -> <name> \\?\usb#vid_058f&pid_0363&mi_00#8&1654aaea&0&0000#{class guid}\global
        path = d['InstanceId'].lower().replace('\\', '#')
        cameras[path] = '%s \\\\?\\%s#%s\\global' % (d['FriendlyName'], path, DEVICE_CLASS_GUID)
    return cameras


def path_of(unique_id):
    match = re.search(r'\\\\\?\\(usb#[^#]+#[^#]+)#', unique_id)
    return match.group(1).lower() if match else None


def configured_cameras(xml):
    """[(name, looking, unique id, (start, end) of the unique id in xml)]"""
    cameras = []
    for tag in re.finditer(r'<camera [^>]*>', xml):
        text = tag.group(0)
        if 'class="%s"' % CAMERA_CLASS not in text:
            continue
        uid = re.search(r'unique-id="([^"]*)"', text)
        name = re.search(r' name="([^"]*)"', text).group(1)
        looking = re.search(r' looking="([^"]*)"', text).group(1)
        cameras.append((name, looking, uid.group(1).replace('&amp;', '&'),
                        (tag.start() + uid.start(1), tag.start() + uid.end(1))))
    return cameras


def write_ids(xml, cameras, new_ids):
    """Replace the unique ids, last first so that the offsets stay right."""
    for (name, _, _, (start, end)) in sorted(cameras, key=lambda c: -c[3][0]):
        xml = xml[:start] + new_ids[name].replace('&', '&amp;') + xml[end:]
    return xml


def commit(config_dir, message):
    if not os.path.isdir(os.path.join(config_dir, '.git')):
        return
    subprocess.run(['git', '-C', config_dir, 'add', 'machine.xml'], check=True)
    result = subprocess.run(['git', '-C', config_dir, 'commit', '-q', '-m', message])
    print('Committed in %s.' % config_dir if result.returncode == 0 else 'Nothing to commit.')


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--apply', action='store_true',
                        help='write the cameras found now to machine.xml (OpenPnP must be closed)')
    parser.add_argument('--swap', action='store_true',
                        help='swap the device paths of the two cameras (OpenPnP must be closed)')
    parser.add_argument('--sim', action='store_true', help='use the simulation config')
    args = parser.parse_args()
    config_dir = os.path.expanduser('~/openpnp-sim' if args.sim else '~/.openpnp2')
    machine_xml = os.path.join(config_dir, 'machine.xml')
    with open(machine_xml, encoding='utf-8', newline='') as f:
        xml = f.read()
    cameras = configured_cameras(xml)
    present = present_cameras()

    print('In machine.xml:')
    for name, looking, uid, _ in cameras:
        path = path_of(uid)
        print('  %-7s (looking %-4s) %s  %s' % (name, looking, path,
                                               'found' if path in present else 'NOT FOUND'))
    print('Plugged in now:')
    for path in present:
        print('  %s' % path)

    if (args.apply or args.swap) and openpnp_running():
        sys.exit('OpenPnP is running: close it first, it rewrites machine.xml when it closes.')

    if args.swap:
        if len(cameras) != 2:
            sys.exit('--swap needs exactly two cameras in machine.xml.')
        (a, _, uid_a, _), (b, _, uid_b, _) = cameras
        xml = write_ids(xml, cameras, {a: uid_b, b: uid_a})
        with open(machine_xml, 'w', encoding='utf-8', newline='') as f:
            f.write(xml)
        print('Swapped %s and %s.' % (a, b))
        commit(config_dir, 'Cameras: swap %s and %s device paths (fix_cameras.py --swap)' % (a, b))
        return

    missing = [c for c in cameras if path_of(c[2]) not in present]
    if not missing:
        print('OK: every camera is plugged in at the path machine.xml expects.')
        return
    in_use = {path_of(c[2]) for c in cameras}
    free = [p for p in present if p not in in_use]
    if len(free) < len(missing):
        sys.exit('Only %d unused camera(s) plugged in for %d missing: check the USB cables, '
                 'then run again.' % (len(free), len(missing)))
    if len(free) > len(missing):
        sys.exit('More unused cameras (%s) than missing ones: cannot tell which to use. '
                 'Unplug the others or choose in OpenPnP: Machine Setup > Cameras > Device.'
                 % ', '.join(free))
    new_ids = {c[0]: c[2] for c in cameras}
    for camera, path in zip(missing, sorted(free)):
        new_ids[camera[0]] = present[path]
        print('%s %s -> %s' % ('Writing' if args.apply else 'Would write', camera[0], path))
    if not args.apply:
        print('Run again with --apply (OpenPnP closed) to write this.')
        return
    with open(machine_xml, 'w', encoding='utf-8', newline='') as f:
        f.write(write_ids(xml, cameras, new_ids))
    commit(config_dir, 'Cameras: new USB device paths (fix_cameras.py)\n\n' + '\n'.join(
        '%s: %s -> %s' % (c[0], path_of(c[2]), path_of(new_ids[c[0]])) for c in missing))
    if len(missing) > 1:
        print('\nWhich camera is Top was guessed. Start OpenPnP, home, and check the Top camera '
              'shows the bed and moves with the head; if not, close OpenPnP and run --swap.')


if __name__ == '__main__':
    main()
