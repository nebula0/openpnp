"""Place a batch of boards from a job file, without the AI.

Parts go on lowest first: the placements are grouped by part height and each group is placed
with OpenPnP's own job processor (POST /job/place), then checked right away with the top camera
(POST /job/inspect). OpenPnP must be running with the AI bridge (ai-bridge/start-openpnp.bat).

    python ai-bridge/run_job.py --job C:/path/board.job.xml                  # all enabled
    python ai-bridge/run_job.py --job ... --boards Brd1,Brd3 --placements C6   # some of them
    python ai-bridge/run_job.py --job ... --survey                            # new bare boards
    python ai-bridge/run_job.py --job ... --dry-run                           # only show the plan

The plan is printed and nothing moves before you confirm it (--yes skips the questions). It
stops at the first group that is not placed completely. The report goes to
ai-bridge/reports/<time>-run_job/report.json (format: report.py), with the photos of placements
that did not pass and the troubleshooting entries that match the errors; when a run fails, give
that report to the AI. Only the Python standard library is used.
"""
import argparse
import base64
import datetime
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

import report as reports

PLACE_TIMEOUT_S = 3600
HEIGHT_DIGITS = 2


class Bridge:
    def __init__(self, config_dir):
        info = json.load(open(os.path.join(config_dir, 'ai-bridge', 'bridge.json')))
        self.base = 'http://127.0.0.1:%d' % info['port']
        self.token = info['token']

    def call(self, method, path, query=None, body=None, timeout=120):
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


def confirm(question, yes):
    if yes:
        print(question + ' yes (--yes)')
        return True
    return input(question + ' [y/N] ').strip().lower() == 'y'


def gated(bridge, path, body, yes, timeout=PLACE_TIMEOUT_S):
    """POSTs without confirm to get the plan, shows it and asks, then POSTs with confirm.

    Returns the result, or None if the user said no."""
    plan = bridge.call('POST', path, body=body)
    if plan.get('status') != 'needs_confirmation':
        return plan
    print(plan['plan'])
    for warning in plan.get('warnings', []):
        print('  warning: ' + warning)
    if not confirm('Go?', yes):
        return None
    return bridge.call('POST', path, body=dict(body, confirm=True), timeout=timeout)


def short_id(board_id):
    return board_id.split('\u21d2')[-1]


def select(job, boards, placements):
    """The enabled placements of enabled boards, optionally only some boards and ids."""
    selected = []
    for board in job['boards']:
        if not board['enabled'] or (boards and short_id(board['boardId']) not in boards
                                    and board['boardId'] not in boards):
            continue
        for p in board['placements']:
            if p['enabled'] and (not placements or p['placementId'] in placements):
                selected.append(dict(p, boardId=board['boardId']))
    return selected


def groups_by_height(selected):
    heights = sorted({round(p.get('partHeight') or 0, HEIGHT_DIGITS) for p in selected})
    return [(h, [p for p in selected if round(p.get('partHeight') or 0, HEIGHT_DIGITS) == h])
            for h in heights]


def refs(group):
    return [{'boardId': p['boardId'], 'placementId': p['placementId']} for p in group]


def save_images(result, directory):
    """Writes the images of an inspection to files, returns {name: path}."""
    paths = {}
    for image in result.pop('images', []):
        path = os.path.join(directory, image['name'] + '.png')
        with open(path, 'wb') as f:
            f.write(base64.b64decode(image['data']))
        paths[image['name']] = path
    return paths


def main():
    # Board ids contain '⇒', which a Windows console code page (e.g. cp950) cannot print.
    sys.stdout.reconfigure(errors='replace')
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--job', required=True, help='the .job.xml file')
    parser.add_argument('--boards', help='comma separated board ids, e.g. Brd1,Brd3 '
                                         '(default: all enabled boards)')
    parser.add_argument('--placements', help='comma separated placement ids, e.g. C6,R1 '
                                             '(default: all enabled placements)')
    parser.add_argument('--survey', action='store_true',
                        help='first photograph the bare boards (new boards only)')
    parser.add_argument('--tolerance', type=float, default=0.25,
                        help='inspection tolerance in mm (default 0.25)')
    parser.add_argument('--dry-run', action='store_true', help='only show what would be done')
    parser.add_argument('--yes', action='store_true', help='do not ask before moving')
    parser.add_argument('--sim', action='store_true', help='use the simulation config')
    args = parser.parse_args()
    config_dir = os.path.expanduser('~/openpnp-sim' if args.sim else '~/.openpnp2')
    bridge = Bridge(config_dir)
    job_file = os.path.abspath(args.job)
    boards = set(args.boards.split(',')) if args.boards else None
    wanted = set(args.placements.split(',')) if args.placements else None

    job = bridge.call('GET', '/job/placements', query={'file': job_file})
    if job.get('status') != 'ok':
        sys.exit('Cannot read the job: %s' % job.get('error'))
    selected = select(job, boards, wanted)
    if not selected:
        sys.exit('No enabled placements selected.')
    groups = groups_by_height(selected)

    print('Job %s: %d placement(s) in %d height group(s), lowest first:'
          % (job_file, len(selected), len(groups)))
    for height, group in groups:
        print('  %.2f mm: %s' % (height, ', '.join(
            '%s %s (%s)' % (short_id(p['boardId']), p['placementId'], p['partId'])
            for p in group)))
    unknown = sorted({p['partId'] for p in selected if not p.get('partHeight')})
    if unknown:
        print('  warning: height unknown (0) for %s: placed first; set it with update_part.'
              % ', '.join(unknown))
    placed_before = [p for p in selected if p.get('placed')]
    if placed_before:
        print('  warning: %d placement(s) are marked as placed in the job file; a bare board '
              'photo keeps them from being placed twice.' % len(placed_before))
    if args.dry_run:
        return 0

    started = datetime.datetime.now()
    run_dir = reports.run_dir('run_job', started)
    report = {'script': 'run_job', 'job': job_file,
              'started': started.isoformat(timespec='seconds'), 'args': vars(args),
              'config': config_dir, 'status': 'running', 'groups': [], 'placements': {}}
    for p in selected:
        report['placements']['%s %s' % (short_id(p['boardId']), p['placementId'])] = {
            'boardId': p['boardId'], 'placementId': p['placementId'], 'partId': p['partId'],
            'height': p.get('partHeight'), 'verdict': 'not run'}

    def finish(status, message=None, result=None):
        report['status'] = status
        report['finished'] = datetime.datetime.now().isoformat(timespec='seconds')
        if message:
            report['message'] = message
        if result is not None and 'nozzlesAfterFailure' in result:
            report['nozzlesAfterFailure'] = result['nozzlesAfterFailure']
        elif status != 'done':
            report['nozzlesAfterFailure'] = bridge.call('GET', '/nozzles').get('nozzles')
        texts = [r for p in report['placements'].values() for r in p.get('reasons', [])]
        texts += [str(r) for r in report.get('partsRejected', [])]
        path = reports.finish(run_dir, report, texts)
        counts = {}
        for p in report['placements'].values():
            counts[p['verdict']] = counts.get(p['verdict'], 0) + 1
        print('\n%s: %s' % (status.upper(), ', '.join('%d %s' % (n, v)
                                                     for v, n in sorted(counts.items()))))
        for key, p in report['placements'].items():
            if p['verdict'] != 'ok':
                print('  %-12s %-14s %-9s %s' % (key, p['partId'], p['verdict'],
                                                 '; '.join(p.get('reasons', []))))
        if message:
            print(message)
        if report.get('nozzlesAfterFailure'):
            held = [n['name'] + ': ' + n['partOnNozzle']
                    for n in report['nozzlesAfterFailure'] if n.get('partOnNozzle')]
            if held:
                print('Parts may still be on nozzles: ' + ', '.join(held))
        reports.print_hints(report)
        print('Report: ' + path)
        if status != 'done':
            print('Give this report to the AI to find out what went wrong.')
        return 0 if status == 'done' else 1

    status = bridge.call('GET', '/status')
    if status.get('status') != 'ok':
        return finish('failed', 'Status: %s' % status.get('error'))
    if not status.get('homed'):
        if not args.yes:
            input('The machine is not homed. Homing moves the whole head: make sure nothing is '
                  'in its way, then press Enter (Ctrl+C to stop). ')
        result = bridge.call('POST', '/machine/home',
                             body={'confirm': True, 'reason': 'run_job.py: not homed'},
                             timeout=300)
        if result.get('status') != 'ok':
            return finish('failed', 'Homing failed: %s' % result.get('error'), result)

    if args.survey:
        result = gated(bridge, '/job/survey', {
            'file': job_file, 'placements': refs(selected),
            'reason': 'run_job.py: bare board photos before placing'}, args.yes)
        if result is None:
            return finish('stopped', 'Stopped before the bare board survey.')
        report['survey'] = {k: v for k, v in result.items() if k != 'images'}
        if result.get('status') != 'ok':
            return finish('failed', 'Survey failed: %s' % result.get('error'), result)

    for height, group in groups:
        names = ', '.join('%s %s' % (short_id(p['boardId']), p['placementId']) for p in group)
        print('\n== %.2f mm: %s' % (height, names))
        entry = {'height': height, 'placements': refs(group)}
        report['groups'].append(entry)
        place = gated(bridge, '/job/place', {
            'file': job_file, 'placements': refs(group), 'captureBefore': True,
            'reason': 'run_job.py: place the %.2f mm parts (%s)' % (height, names)}, args.yes)
        if place is None:
            return finish('stopped', 'Stopped before placing the %.2f mm group.' % height)
        entry['place'] = place
        for p in place.get('placements', []):
            r = report['placements']['%s %s' % (short_id(p['boardId']), p['placementId'])]
            r['placed'] = p.get('placed')
            if p.get('skipped'):
                r['verdict'] = 'skipped'
                r['reasons'] = [p['skipped']]
            elif not p.get('placed'):
                r['verdict'] = 'not placed'
        for key in ('partsRejected', 'feedersDisabled', 'skippedOccupied', 'notCheckedForParts'):
            if place.get(key):
                report.setdefault(key, []).extend(place[key])

        # Check whatever was placed, also after a failure, before deciding to stop.
        placed = [p for p in place.get('placements', []) if p.get('placed')]
        if placed:
            inspect = bridge.call('POST', '/job/inspect', body={
                'file': job_file, 'placements': refs(placed), 'images': 'failures',
                'toleranceMm': args.tolerance, 'confirm': True,
                'reason': 'run_job.py: check the %.2f mm parts just placed' % height},
                timeout=PLACE_TIMEOUT_S)
            images = save_images(inspect, run_dir)
            entry['inspect'] = inspect
            for r in inspect.get('placements', []):
                p = report['placements']['%s %s' % (short_id(r['boardId']), r['placementId'])]
                p['verdict'] = r.get('verdict', 'unknown')
                p['reasons'] = r.get('reasons', [])
                p['measured'] = r.get('measured')
                p['frameFile'] = r.get('frameFile')
                if r.get('image') in images:
                    p['image'] = images[r['image']]
            if inspect.get('status') != 'ok':
                return finish('failed', 'Inspection failed: %s' % inspect.get('error'), inspect)
        if place.get('status') != 'ok':
            return finish('failed', 'Placing the %.2f mm group failed: %s'
                          % (height, place.get('error')), place)
    return finish('done')


if __name__ == '__main__':
    sys.exit(main())
