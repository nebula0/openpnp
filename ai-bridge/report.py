"""Reports of the ai-bridge scripts, in one format, for the AI to read when a run fails.

Each run writes ai-bridge/reports/<time>-<script>/report.json:

    {"script": "run_job", "status": "done" | "failed" | "stopped", "started": ..., "finished": ...,
     "message": what went wrong, "args": the command line options,
     "nozzlesAfterFailure": what is on the nozzles (failed runs),
     "hints": troubleshooting entries whose symptom matches the errors, ...the script's own data}

Photos a script saves go into the same folder. Only the Python standard library is used.
"""
import datetime
import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
TROUBLESHOOTING = os.path.join(HERE, '..', 'knowledge', 'troubleshooting.md')


def run_dir(script, started):
    path = os.path.join(HERE, 'reports', '%s-%s' % (started.strftime('%Y%m%d-%H%M%S'), script))
    os.makedirs(path, exist_ok=True)
    return path


def knowledge_hints(texts):
    """Troubleshooting entries whose heading quotes an error message found in texts.

    A heading such as ### `Found 0 hole candidates but not two ...` matches when the quoted
    message, with ... standing for anything, appears in one of the texts."""
    try:
        with open(TROUBLESHOOTING, encoding='utf-8') as f:
            lines = f.read().split('\n')
    except OSError:
        return []
    text = '\n'.join(t for t in texts if t)
    hints = []
    for i, line in enumerate(lines):
        if not line.startswith('### '):
            continue
        for quoted in re.findall(r'`([^`]+)`', line):
            pattern = '.*?'.join(re.escape(part.strip()) for part in quoted.split('...'))
            if len(quoted) >= 8 and re.search(pattern, text, re.IGNORECASE):
                body = []
                for following in lines[i + 1:]:
                    if following.startswith('#'):
                        break
                    body.append(following)
                hints.append({'title': line[4:], 'line': i + 1,
                              'file': 'knowledge/troubleshooting.md',
                              'text': '\n'.join(body).strip()})
                break
    return hints


def finish(directory, report, texts=()):
    """Adds the finish time and hints, writes report.json, returns its path."""
    report.setdefault('finished', datetime.datetime.now().isoformat(timespec='seconds'))
    if report.get('status') not in ('done', 'ok'):
        report['hints'] = knowledge_hints(list(texts) + [report.get('message', '')])
    path = os.path.join(directory, 'report.json')
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(report, f, ensure_ascii=False, indent=1)
    return path


def print_hints(report):
    for hint in report.get('hints', []):
        print('See %s:%d: %s' % (hint['file'], hint['line'], hint['title']))
