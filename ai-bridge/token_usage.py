"""Token usage per tool, from a Claude Code session transcript.

    python ai-bridge/token_usage.py                 # the newest session of this project
    python ai-bridge/token_usage.py <session.jsonl> [--since 2026-10-14T15:00]

Claude Code keeps every session as a JSONL file under ~/.claude/projects/<project>/, with the
token usage of each model request. A tool call costs in two places:

- output: what the model writes to make the call (and its thinking). Counted on the request
  that issued the call; split evenly when one request makes several calls.
- result: the tool's answer (text and images) is new input for the next request, so that
  request's new input (input + cache creation) is attributed to the calls before it, split by
  the size of their results. Images are counted at about 1600 tokens each for the split.

After that a result stays in the context and is read from the cache on every later request
until the context is compacted. Those cache reads are cheap (about a tenth of new input) and
are shown only in the total.
"""
import argparse
import glob
import json
import os
import sys
from collections import defaultdict

IMAGE_WEIGHT = 1600 * 4  # characters an image counts as when splitting


def newest_session():
    project = os.path.join(os.path.expanduser('~/.claude/projects'),
                           os.getcwd().replace(':', '-').replace('\\', '-').replace('/', '-'))
    files = glob.glob(os.path.join(project, '*.jsonl'))
    if not files:
        # The folder name differs in the drive letter's case now and then.
        files = [f for d in glob.glob(os.path.expanduser('~/.claude/projects/*'))
                 if d.lower() == project.lower() for f in glob.glob(os.path.join(d, '*.jsonl'))]
    return max(files, key=os.path.getmtime)


def result_size(content):
    if isinstance(content, str):
        return len(content), 0
    chars, images = 0, 0
    for c in content or []:
        if c.get('type') == 'image':
            images += 1
        else:
            chars += len(c.get('text', ''))
    return chars, images


def short(name):
    return name.split('__')[-1] if name.startswith('mcp__') else name


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('session', nargs='?')
    parser.add_argument('--since', help='only requests from this time on (ISO, local time)')
    args = parser.parse_args()
    path = args.session or newest_session()

    requests = []  # in order: {id, usage, calls: [tool_use_id]}
    by_request = {}
    calls = {}  # tool_use_id -> {name, chars, images}
    for line in open(path, encoding='utf-8'):
        try:
            e = json.loads(line)
        except ValueError:
            continue
        if args.since and e.get('timestamp', '') < args.since:
            continue
        m = e.get('message') or {}
        if e.get('type') == 'assistant' and m.get('usage'):
            rid = e.get('requestId') or m.get('id')
            r = by_request.get(rid)
            if r is None:
                r = {'usage': m['usage'], 'calls': []}
                by_request[rid] = r
                requests.append(r)
            for c in m.get('content') or []:
                if c.get('type') == 'tool_use' and c['id'] not in calls:
                    calls[c['id']] = {'name': short(c['name']), 'chars': 0, 'images': 0}
                    r['calls'].append(c['id'])
        elif e.get('type') == 'user' and isinstance(m.get('content'), list):
            for c in m['content']:
                if c.get('type') == 'tool_result' and c.get('tool_use_id') in calls:
                    chars, images = result_size(c.get('content'))
                    calls[c['tool_use_id']]['chars'] += chars
                    calls[c['tool_use_id']]['images'] += images

    tools = defaultdict(lambda: {'calls': 0, 'images': 0, 'result': 0, 'output': 0})
    total = defaultdict(int)
    previous = []
    for r in requests:
        u = r['usage']
        new_input = u.get('input_tokens', 0) + u.get('cache_creation_input_tokens', 0)
        total['new input'] += new_input
        total['cache read'] += u.get('cache_read_input_tokens', 0)
        total['output'] += u.get('output_tokens', 0)
        # New input: the results of the calls just before, else a prompt or other text.
        weights = [max(1, calls[c]['chars'] + IMAGE_WEIGHT * calls[c]['images'])
                   for c in previous]
        if previous:
            for c, w in zip(previous, weights):
                tools[calls[c]['name']]['result'] += new_input * w / sum(weights)
        else:
            tools['(prompt, text, compaction)']['result'] += new_input
        if r['calls']:
            for c in r['calls']:
                t = tools[calls[c]['name']]
                t['calls'] += 1
                t['images'] += calls[c]['images']
                t['output'] += u.get('output_tokens', 0) / len(r['calls'])
        else:
            tools['(reply to the user)']['output'] += u.get('output_tokens', 0)
        previous = r['calls']

    print(os.path.basename(path), '-', len(requests), 'requests')
    print('%-32s %6s %6s %12s %10s %10s' % ('tool', 'calls', 'images', 'result tok', 'output',
                                            'per call'))
    rows = sorted(tools.items(), key=lambda kv: -(kv[1]['result'] + kv[1]['output']))
    for name, t in rows:
        per = (t['result'] + t['output']) / t['calls'] if t['calls'] else 0
        print('%-32s %6d %6d %12.0f %10.0f %10.0f' % (name[:32], t['calls'], t['images'],
                                                       t['result'], t['output'], per))
    print('total: new input %d, cache read %d, output %d' % (
        total['new input'], total['cache read'], total['output']))
    return 0


if __name__ == '__main__':
    sys.exit(main())
