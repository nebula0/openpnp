"""Set up strip feeders from their QR labels, without the AI.

The top camera moves along the row of QR labels, one at the start of each cut tape strip,
reads which part each strip holds, finds the strip's sprocket holes and parts next to it, and
updates or adds a strip feeder per label. OpenPnP must be running with the AI bridge
(ai-bridge/start-openpnp.bat) and the machine homed.

    python ai-bridge/scan_feeders.py --y 199.5 --from 120 --to 160            # dry run
    python ai-bridge/scan_feeders.py --y 199.5 --from 120 --to 160 --apply    # change feeders
    python ai-bridge/scan_feeders.py --y 199.5 --from 120 --to 160 --apply --verify

Each run that moves the machine writes ai-bridge/reports/<time>-scan_feeders/report.json
(format: report.py). With --verify, each new feeder (added disabled) is then checked and enabled only if all of
this passes: its pick Z is measured with the vacuum sensor (probe, written as contact - 0.1),
one part is picked, its bottom vision finds it with the part size check passing, and the part
is discarded. --verify-all checks the updated feeders too (they keep their enabled state).
A part that is picked is always discarded: strip feeders cannot take parts back.

Only the Python standard library is used. The machine moves only after you confirm the plan.
"""
import argparse
import datetime
import json
import os
import sys
import urllib.error
import urllib.request

import report as reports

PICK_Z_BELOW_CONTACT = 0.1


def call(config_dir, path, body, timeout=900):
    """POSTs body to the bridge, or GETs if body is None."""
    info = json.load(open(os.path.join(config_dir, 'ai-bridge', 'bridge.json')))
    request = urllib.request.Request(
        'http://127.0.0.1:%d%s' % (info['port'], path),
        data=None if body is None else json.dumps(body).encode('utf-8'),
        method='GET' if body is None else 'POST',
        headers={'X-AI-Bridge-Token': info['token'], 'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode('utf-8'))


def verify(config_dir, label, args):
    """Measures Z, picks, runs bottom vision and discards for one feeder.

    Returns (passed, list of steps as text)."""
    feeder_id = label['feederId']
    steps = []
    why = 'scan_feeders.py --verify: check the new feeder %s' % label['feederName']
    probe = call(config_dir, '/feeder/probe-z', {
        'feederId': feeder_id, 'contactDrop': args.contact_drop,
        'floorZ': max(args.min_floor_z, label['z'] - 1), 'confirm': True, 'reason': why})
    if probe.get('status') != 'ok':
        steps.append('probe failed: %s' % probe.get('error'))
        return False, steps
    nozzle = probe['nozzleName']
    if probe.get('partStuckOnNozzle'):
        call(config_dir, '/nozzle/discard', {'nozzleId': nozzle, 'confirm': True, 'reason': why})
        steps.append('probe: the part came up with the nozzle, discarded')
        return False, steps
    z = round(probe['contactZ'] - PICK_Z_BELOW_CONTACT, 3)
    update = call(config_dir, '/feeder/update', {'feederId': feeder_id, 'z': z,
                                                 'reason': why + ': contact %.2f' % probe['contactZ']})
    if update.get('status') != 'ok':
        steps.append('setting Z failed: %s' % update.get('error'))
        return False, steps
    steps.append('contact at Z %.2f, pick Z set to %.2f' % (probe['contactZ'], z))

    pick = call(config_dir, '/feeder/pick', {'feederId': feeder_id, 'nozzleId': nozzle,
                                             'returnPart': False, 'confirm': True, 'reason': why})
    if pick.get('status') != 'ok':
        steps.append('pick failed: %s' % pick.get('error'))
        call(config_dir, '/nozzle/discard', {'nozzleId': nozzle, 'confirm': True, 'reason': why})
        return False, steps
    try:
        vision = call(config_dir, '/vision/bottom/test', {'nozzleId': nozzle, 'confirm': True,
                                                          'reason': why})
    finally:
        discard = call(config_dir, '/nozzle/discard', {'nozzleId': nozzle, 'confirm': True,
                                                       'reason': why + ': discard the test part'})
    if vision.get('status') != 'ok':
        steps.append('bottom vision failed: %s' % vision.get('error'))
        return False, steps
    shots = vision.get('shots', [])
    found = shots and all(s.get('found') for s in shots)
    checks = [s.get('sizeCheck') for s in shots]
    shot = shots[0] if shots else {}
    steps.append('bottom vision (%s, threshold %s): %s, %.2f x %.2f mm, %.1f deg, size check %s'
                 % (vision.get('visionSettings'), vision.get('thresholds'),
                    'found' if found else 'NOT found', shot.get('widthMm', 0),
                    shot.get('heightMm', 0), shot.get('angle', 0), ', '.join(map(str, checks))))
    if discard.get('status') != 'ok':
        steps.append('discard failed: %s' % discard.get('error'))
        return False, steps
    if not found:
        return False, steps
    if any(c == 'disabled' for c in checks):
        steps.append('the size check is off for this part, so an empty nozzle could pass too: '
                     'turn it on (update_part / update_package checkPartSize) and verify again')
        return False, steps
    if any(c != 'ok' for c in checks):
        return False, steps
    return True, steps


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--y', type=float, required=True, help='y of the row of label centers')
    parser.add_argument('--from', dest='x_from', type=float, required=True)
    parser.add_argument('--to', dest='x_to', type=float, required=True)
    parser.add_argument('--step', type=float, default=10)
    parser.add_argument('--y-tolerance', type=float, default=4,
                        help='labels may be this far off --y (three rows of photos; 0: one row)')
    parser.add_argument('--tape-offset', type=float, default=-14,
                        help='mm from a label center to the first photo of its strip, along y '
                             '(negative: the strip runs on below the label)')
    parser.add_argument('--search-length', type=float, default=80,
                        help='how far along the strip to look for the first part')
    parser.add_argument('--z', type=float, help='tape surface Z for new feeders')
    parser.add_argument('--apply', action='store_true', help='update or add the feeders')
    parser.add_argument('--verify', action='store_true',
                        help='after --apply, check the new feeders and enable those that pass')
    parser.add_argument('--verify-all', action='store_true',
                        help='like --verify, for the updated feeders too')
    parser.add_argument('--contact-drop', type=float,
                        help='vacuum drop below the level in the air that counts as contact when '
                             'probing (default: the nozzle tip\'s part on level; with the '
                             'two-byte vacuum reading a part drops it by about 600)')
    parser.add_argument('--min-floor-z', type=float, default=7.0,
                        help='never probe below this Z (default 7.0)')
    parser.add_argument('--sim', action='store_true', help='use the simulation config')
    parser.add_argument('--yes', action='store_true', help='do not ask before moving')
    args = parser.parse_args()
    if (args.verify or args.verify_all) and not args.apply:
        parser.error('--verify needs --apply')
    config_dir = os.path.expanduser('~/openpnp-sim' if args.sim else '~/.openpnp2')
    body = {'y': args.y, 'xFrom': args.x_from, 'xTo': args.x_to, 'step': args.step,
            'yTolerance': args.y_tolerance,
            'tapeOffset': args.tape_offset, 'searchLength': args.search_length, 'z': args.z,
            'apply': args.apply,
            'reason': 'scan_feeders.py: set up strip feeders from their QR labels'}

    plan = call(config_dir, '/feeder/scan-qr', body)
    if plan.get('status') != 'needs_confirmation':
        print(json.dumps(plan, ensure_ascii=False, indent=1))
        return 1
    print(plan['plan'])
    if args.verify or args.verify_all:
        print('Then for each %s feeder: probe its pick Z, pick one part, run bottom vision, '
              'discard the part, and enable the feeder if all passed.'
              % ('new or updated' if args.verify_all else 'new'))
    if not args.yes and input('Make sure nothing is in the way. Go? [y/N] ').lower() != 'y':
        return 1
    started = datetime.datetime.now()
    directory = reports.run_dir('scan_feeders', started)
    report = {'script': 'scan_feeders', 'started': started.isoformat(timespec='seconds'),
              'args': vars(args), 'config': config_dir, 'status': 'running'}

    def done(status, message=None, texts=()):
        report['status'] = status
        if message:
            report['message'] = message
        if status != 'done':
            report['nozzlesAfterFailure'] = call(config_dir, '/nozzles', None).get('nozzles')
        path = reports.finish(directory, report, texts)
        reports.print_hints(report)
        print('Report: ' + path)
        return 0 if status == 'done' else 1

    result = call(config_dir, '/feeder/scan-qr', dict(body, confirm=True))
    report['scan'] = result
    if result.get('status') != 'ok':
        print(json.dumps(result, ensure_ascii=False, indent=1))
        return done('failed', 'Scan failed: %s' % result.get('error'))
    print('%d label(s) found, %s' % (result['labelsFound'],
                                     'applied' if result['applied'] else 'dry run'))
    for label in result['labels']:
        pick = label.get('firstPick')
        print('  %-16s at %7.2f %7.2f  %-12s %s' % (
            label['text'], label['x'], label['y'], label.get('status', ''),
            label.get('error') or label.get('warning') or (
                'first pick %.2f %.2f, %s parts seen, feeder %s' % (
                    pick['x'], pick['y'], label.get('partsFound'),
                    label.get('feederName', 'new')) if pick else '')))
    label_errors = [label.get('error') for label in result['labels'] if label.get('error')]
    if not (args.verify or args.verify_all):
        return done('failed' if label_errors else 'done',
                    '%d label(s) failed' % len(label_errors) if label_errors else None,
                    label_errors)

    failed = 0
    report['verify'] = []
    for label in result['labels']:
        wanted = label.get('status') == 'added' or (args.verify_all
                                                    and label.get('status') == 'updated')
        if not wanted or not label.get('feederId'):
            continue
        print('\nVerifying %s ...' % label['feederName'])
        passed, steps = verify(config_dir, label, args)
        for step in steps:
            print('  ' + step)
        if passed and label.get('status') == 'added':
            enable = call(config_dir, '/feeder/update', {
                'feederId': label['feederId'], 'enabled': True,
                'reason': 'scan_feeders.py --verify: all checks passed'})
            passed = enable.get('status') == 'ok'
            print('  enabled' if passed else '  enabling failed: %s' % enable.get('error'))
        print('  PASSED' if passed else '  FAILED: the feeder keeps its enabled state; '
              'give the report to the AI')
        report['verify'].append({'feederId': label['feederId'], 'feederName': label['feederName'],
                                 'passed': passed, 'steps': steps})
        failed += 0 if passed else 1
    steps = [step for v in report['verify'] for step in v['steps']]
    if failed or label_errors:
        return done('failed', '%d feeder(s) failed verification, %d label(s) failed'
                    % (failed, len(label_errors)), label_errors + steps)
    return done('done')


if __name__ == '__main__':
    sys.exit(main())
