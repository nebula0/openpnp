"""Find a bottom vision threshold for a part, without the AI.

Picks one part from a feeder, runs its bottom vision once per threshold (overridden for that
run only), and takes the middle of the widest run of thresholds where the part is found and
OpenPnP's part size check passes. That threshold is then checked at the other rotations too.
With --apply it is written to the settings the part uses (its own, or its package's with
--package). The part is discarded at the end. OpenPnP must be running with the AI bridge and
the machine homed.

    python ai-bridge/tune_bottom_vision.py --feeder "C0603-10nF strip"            # only measure
    python ai-bridge/tune_bottom_vision.py --feeder "C0603-10nF strip" --apply    # and write
    python ai-bridge/tune_bottom_vision.py --feeder ... --package --apply         # for the package

The size check is what tells a part from the nozzle tip's reflection, so it must be on: turn it
on with --enable-size-check (BodySize, --size-tolerance percent) or update_part. When no
threshold passes, or the result is unclear, give the output to the AI. Only the Python
standard library is used.
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request


class Bridge:
    def __init__(self, config_dir):
        info = json.load(open(os.path.join(config_dir, 'ai-bridge', 'bridge.json')))
        self.base = 'http://127.0.0.1:%d' % info['port']
        self.token = info['token']

    def call(self, method, path, query=None, body=None, timeout=300):
        url = self.base + path
        if query:
            url += '?' + urllib.parse.urlencode(query)
        request = urllib.request.Request(
            url, method=method, data=None if body is None else json.dumps(body).encode('utf-8'),
            headers={'X-AI-Bridge-Token': self.token, 'Content-Type': 'application/json'})
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return json.loads(response.read().decode('utf-8'))
        except urllib.error.HTTPError as e:
            return json.loads(e.read().decode('utf-8'))
        except urllib.error.URLError as e:
            sys.exit('Cannot reach the OpenPnP AI bridge at %s: %s. Is OpenPnP running?'
                     % (self.base, e.reason))


ANGLE_SPREAD_DEG = 2


def widest_run(passing):
    """The longest run of consecutive passing thresholds, as a list."""
    best, run = [], []
    for threshold, ok in passing:
        run = run + [threshold] if ok else []
        if len(run) > len(best):
            best = run
    return best


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--feeder', required=True, help='feeder id or name to pick from')
    parser.add_argument('--nozzle', help='nozzle id or name (default: one with a fitting tip)')
    parser.add_argument('--from', dest='low', type=int, default=150)
    parser.add_argument('--to', dest='high', type=int, default=250)
    parser.add_argument('--step', type=int, default=10)
    parser.add_argument('--rotations', default='0,-90',
                        help='rotations to check the chosen threshold at, the first one is used '
                             'for the scan (default 0,-90)')
    parser.add_argument('--apply', action='store_true', help='write the threshold')
    parser.add_argument('--package', action='store_true',
                        help="write to the part's package instead of the part")
    parser.add_argument('--enable-size-check', action='store_true',
                        help='turn on the BodySize part size check first (written!)')
    parser.add_argument('--size-tolerance', type=int, default=25,
                        help='size check tolerance in percent (default 25)')
    parser.add_argument('--sim', action='store_true', help='use the simulation config')
    parser.add_argument('--yes', action='store_true', help='do not ask before moving')
    args = parser.parse_args()
    config_dir = os.path.expanduser('~/openpnp-sim' if args.sim else '~/.openpnp2')
    bridge = Bridge(config_dir)
    rotations = [float(r) for r in args.rotations.split(',')]
    thresholds = list(range(args.low, args.high + 1, args.step))
    why = 'tune_bottom_vision.py'

    feeders = bridge.call('GET', '/feeders').get('feeders', [])
    feeder = next((f for f in feeders if args.feeder in (f.get('id'), f.get('name'))), None)
    if feeder is None:
        sys.exit('No feeder %s. Feeders: %s' % (args.feeder,
                                                ', '.join(f.get('name', '?') for f in feeders)))
    part_id = feeder.get('partId')
    parts = bridge.call('GET', '/parts', query={'ids': part_id}).get('parts', [])
    if not parts:
        sys.exit('Feeder %s has no known part.' % args.feeder)
    part = parts[0]
    vision = part['bottomVision']
    print('Part %s (package %s, height %.2f mm), bottom vision "%s"%s, threshold %s, size check '
          '%s %s%%' % (part_id, part['packageId'], part['height'], vision['settingsName'],
                       '' if vision['own'] else ' (inherited)', vision['thresholds'],
                       vision['checkPartSize'], vision['checkSizeTolerancePercent']))
    if not part['height']:
        sys.exit('The part height is 0: set it first (update_part height), bottom vision needs it.')

    if vision['checkPartSize'] == 'Disabled':
        if not args.enable_size_check:
            sys.exit('The part size check is off, so the nozzle tip reflection could pass as a '
                     'part. Run again with --enable-size-check.')
        target = ('/package/update', {'packageId': part['packageId']}) if args.package else (
            '/part/update', {'partId': part_id})
        result = bridge.call('POST', target[0], body=dict(
            target[1], checkPartSize='BodySize', checkSizeTolerancePercent=args.size_tolerance,
            reason=why + ': turn on the part size check'))
        if result.get('status') != 'ok':
            sys.exit('Turning on the size check failed: %s' % result.get('error'))
        print('Turned on the BodySize size check, %d%%.' % args.size_tolerance)

    print('Plan: pick one %s from %s, run bottom vision at thresholds %d..%d (step %d) at %g deg, '
          'check the best at %s deg, discard the part.%s'
          % (part_id, feeder['name'], args.low, args.high, args.step, rotations[0],
             ', '.join('%g' % r for r in rotations), ' Then write it.' if args.apply else ''))
    if not args.yes and input('Go? [y/N] ').strip().lower() != 'y':
        return 1

    pick = bridge.call('POST', '/feeder/pick', body={
        'feederId': feeder['id'], 'nozzleId': args.nozzle, 'returnPart': False, 'confirm': True,
        'reason': why + ': pick a part to tune its bottom vision'})
    if pick.get('status') != 'ok':
        print('Pick failed: %s' % pick.get('error'))
        return 1
    nozzle = pick['nozzleId']

    def run(threshold, rotation):
        result = bridge.call('POST', '/vision/bottom/test', body={
            'nozzleId': nozzle, 'threshold': threshold, 'rotation': rotation, 'confirm': True,
            'reason': why + ': threshold %d at %g deg' % (threshold, rotation)})
        if result.get('status') != 'ok':
            return False, None, 'error: %s' % result.get('error')
        shots = result.get('shots', [])
        ok = bool(shots) and all(s.get('found') and s.get('sizeCheck') == 'ok' for s in shots)
        s = shots[0] if shots else {}
        text = ('%.2f x %.2f mm, %6.1f deg, %s' % (s.get('widthMm', 0), s.get('heightMm', 0),
                                                  s.get('angle', 0), s.get('sizeCheck'))
                if s.get('found') else 'not found')
        return ok, s.get('angle'), text

    chosen = None
    try:
        results = []
        for threshold in thresholds:
            ok, angle, text = run(threshold, rotations[0])
            results.append((threshold, ok, angle))
            print('  %3d  %-4s %s' % (threshold, 'ok' if ok else '-', text))
        # A result framing more than the part, e.g. a bit of the nozzle tip's reflection, may
        # still pass the size check, but its angle is off: keep only angles near the median.
        angles = sorted(a for _, ok, a in results if ok)
        median = angles[len(angles) // 2] if angles else 0
        passing = [(t, ok and abs(a - median) <= ANGLE_SPREAD_DEG) for t, ok, a in results]
        dropped = [t for (t, ok, _), (_, kept) in zip(results, passing) if ok and not kept]
        if dropped:
            print('Angle more than %g deg off the median %.1f: %s' % (
                ANGLE_SPREAD_DEG, median, ', '.join(map(str, dropped))))
        best = widest_run(passing)
        if not best:
            print('No threshold passed. Give this output to the AI.')
            return 1
        chosen = best[len(best) // 2]
        print('Passing %d..%d, chosen %d.' % (best[0], best[-1], chosen))
        if len(best) < 3:
            print('Warning: the passing range is narrow; a small change in light could break it.')
        for rotation in rotations[1:]:
            ok, _, text = run(chosen, rotation)
            print('  %3d at %g deg: %s %s' % (chosen, rotation, 'ok' if ok else 'FAILED', text))
            if not ok:
                print('The chosen threshold fails at %g deg. Give this output to the AI.'
                      % rotation)
                chosen = None
                return 1
    finally:
        discard = bridge.call('POST', '/nozzle/discard', body={
            'nozzleId': nozzle, 'confirm': True, 'reason': why + ': discard the test part'})
        if discard.get('status') != 'ok':
            print('Discarding failed, a part may still be on %s: %s' % (nozzle,
                                                                       discard.get('error')))

    if not args.apply:
        print('Not written (no --apply).')
        return 0
    target = ('/package/update', {'packageId': part['packageId']}) if args.package else (
        '/part/update', {'partId': part_id})
    result = bridge.call('POST', target[0], body=dict(
        target[1], bottomVisionThreshold=chosen,
        reason=why + ': middle of the passing range %d..%d' % (best[0], best[-1])))
    if result.get('status') != 'ok':
        print('Writing failed: %s' % result.get('error'))
        return 1
    print('Written: threshold %d for %s %s.' % (chosen, 'package' if args.package else 'part',
                                                 target[1].popitem()[1]))
    return 0


if __name__ == '__main__':
    sys.exit(main())
