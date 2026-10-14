"""Set up strip feeders from their QR labels, without the AI.

The top camera moves along the row of QR labels, one at the start of each cut tape strip,
reads which part each strip holds, finds the strip's sprocket holes and parts next to it, and
updates or adds a strip feeder per label. OpenPnP must be running with the AI bridge
(ai-bridge/start-openpnp.bat) and the machine homed.

    python ai-bridge/scan_feeders.py --y 199.5 --from 120 --to 160            # dry run
    python ai-bridge/scan_feeders.py --y 199.5 --from 120 --to 160 --apply    # change feeders

Only the Python standard library is used. The camera moves only after you confirm the plan.
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request


def call(config_dir, body):
    info = json.load(open(os.path.join(config_dir, 'ai-bridge', 'bridge.json')))
    request = urllib.request.Request(
        'http://127.0.0.1:%d/feeder/scan-qr' % info['port'],
        data=json.dumps(body).encode('utf-8'), method='POST',
        headers={'X-AI-Bridge-Token': info['token'], 'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(request, timeout=900) as response:
            return json.loads(response.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode('utf-8'))


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
    parser.add_argument('--sim', action='store_true', help='use the simulation config')
    parser.add_argument('--yes', action='store_true', help='do not ask before moving')
    args = parser.parse_args()
    config_dir = os.path.expanduser('~/openpnp-sim' if args.sim else '~/.openpnp2')
    body = {'y': args.y, 'xFrom': args.x_from, 'xTo': args.x_to, 'step': args.step,
            'yTolerance': args.y_tolerance,
            'tapeOffset': args.tape_offset, 'searchLength': args.search_length, 'z': args.z,
            'apply': args.apply,
            'reason': 'scan_feeders.py: set up strip feeders from their QR labels'}

    plan = call(config_dir, body)
    if plan.get('status') != 'needs_confirmation':
        print(json.dumps(plan, ensure_ascii=False, indent=1))
        return 1
    print(plan['plan'])
    if not args.yes and input('Make sure nothing is in the way. Go? [y/N] ').lower() != 'y':
        return 1
    result = call(config_dir, dict(body, confirm=True))
    if result.get('status') != 'ok':
        print(json.dumps(result, ensure_ascii=False, indent=1))
        return 1
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
    return 0


if __name__ == '__main__':
    sys.exit(main())
