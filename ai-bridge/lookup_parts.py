"""Look up parts by their LCSC number and fill in their description (and height, if known).

For each part, the LCSC product page gives the manufacturer, manufacturer part number,
description, package and a datasheet link. The part's name becomes
"BRAND MPN (LCSC Cxxxx): description", and the LCSC package is compared with the part's
OpenPnP package, which catches e.g. an 0805 reel taken for an 0603 part. LCSC pages rarely give
the thickness: the height is only written when the page has it, otherwise the datasheet link is
listed for the AI or a person to read. Does not move the machine.

    python ai-bridge/lookup_parts.py --part C0603-100nF=C14663 --part C0603-10nF=C5137477
    python ai-bridge/lookup_parts.py                  # parts whose name has "LCSC Cxxxx" in it
    python ai-bridge/lookup_parts.py ... --apply      # write the names (and heights found)

The report goes to ai-bridge/reports/<time>-lookup_parts/report.json (format: report.py). Only
the Python standard library is used; it needs the internet for lcsc.com.
"""
import argparse
import datetime
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

import report as reports

LCSC_URL = 'https://www.lcsc.com/product-detail/%s.html'
HEIGHT_NAMES = ('height', 'thickness', 'height - seated (max)', 'thickness (max)')


def bridge_call(config_dir, method, path, query=None, body=None):
    info = json.load(open(os.path.join(config_dir, 'ai-bridge', 'bridge.json')))
    url = 'http://127.0.0.1:%d%s' % (info['port'], path)
    if query:
        url += '?' + urllib.parse.urlencode(query)
    request = urllib.request.Request(
        url, method=method, data=None if body is None else json.dumps(body).encode('utf-8'),
        headers={'X-AI-Bridge-Token': info['token'], 'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.loads(response.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode('utf-8'))
    except urllib.error.URLError as e:
        sys.exit('Cannot reach the OpenPnP AI bridge: %s. Is OpenPnP running?' % e.reason)


def lcsc(code):
    """The product data of an LCSC part number, from the structured data on its page."""
    request = urllib.request.Request(LCSC_URL % code, headers={'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(request, timeout=30) as response:
        html = response.read().decode('utf-8', 'replace')
    product = None
    for m in re.finditer(r'<script type="application/ld\+json">(.*?)</script>', html, re.S):
        try:
            data = json.loads(m.group(1))
        except ValueError:
            continue
        if isinstance(data, dict) and data.get('@type') == 'Product':
            product = data
            break
    if product is None:
        raise ValueError('no product data on the LCSC page of %s' % code)
    properties = {p.get('name'): p.get('value') for p in product.get('additionalProperty', [])}
    pdf = re.search(r'"pdfUrl":"([^"]+)"', html)
    brand = product.get('brand')
    return {'code': code, 'brand': brand.get('name') if isinstance(brand, dict) else brand,
            'mpn': product.get('mpn'), 'description': product.get('description'),
            'properties': properties, 'datasheet': pdf.group(1) if pdf else None}


def height_of(properties):
    """A height in mm from the product properties, or None."""
    for name, value in properties.items():
        if name and name.lower() in HEIGHT_NAMES and value:
            m = re.match(r'\s*([\d.]+)\s*mm', str(value))
            if m:
                return float(m.group(1))
    return None


def package_matches(lcsc_package, openpnp_package):
    """Whether the LCSC package (e.g. 0603) appears in the OpenPnP package id (e.g. C0603)."""
    if not lcsc_package or not openpnp_package:
        return None
    size = re.search(r'\d{4}', lcsc_package)
    if size:
        return size.group(0) in openpnp_package
    return lcsc_package.lower().replace('-', '') in openpnp_package.lower().replace('-', '')


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--part', action='append', default=[],
                        help='PARTID=LCSC number, e.g. C0603-100nF=C14663 (repeatable)')
    parser.add_argument('--apply', action='store_true', help='write names and heights found')
    parser.add_argument('--sim', action='store_true', help='use the simulation config')
    args = parser.parse_args()
    config_dir = os.path.expanduser('~/openpnp-sim' if args.sim else '~/.openpnp2')

    parts = {p['partId']: p for p in bridge_call(config_dir, 'GET', '/parts').get('parts', [])}
    wanted = {}
    for item in args.part:
        part_id, _, code = item.partition('=')
        if not code:
            parser.error('--part needs PARTID=Cxxxx, not %s' % item)
        wanted[part_id] = code.strip().upper()
    if not args.part:
        for part_id, p in parts.items():
            m = re.search(r'LCSC\s+(C\d+)', p.get('name') or '')
            if m:
                wanted[part_id] = m.group(1)
    if not wanted:
        sys.exit('No parts to look up: give --part PARTID=Cxxxx, or put "LCSC Cxxxx" in the '
                 "part's name.")

    started = datetime.datetime.now()
    directory = reports.run_dir('lookup_parts', started)
    report = {'script': 'lookup_parts', 'started': started.isoformat(timespec='seconds'),
              'args': vars(args), 'parts': []}
    problems = 0
    for part_id, code in sorted(wanted.items()):
        part = parts.get(part_id)
        entry = {'partId': part_id, 'lcsc': code}
        report['parts'].append(entry)
        if part is None:
            entry['error'] = 'no such part in OpenPnP'
            print('%-16s %s: no such part' % (part_id, code))
            problems += 1
            continue
        try:
            info = lcsc(code)
        except (OSError, ValueError) as e:
            entry['error'] = 'LCSC lookup failed: %s' % e
            print('%-16s %s: lookup failed: %s' % (part_id, code, e))
            problems += 1
            continue
        entry.update(info)
        name = '%s %s (LCSC %s): %s' % (info['brand'], info['mpn'], code, info['description'])
        entry['name'] = name
        matches = package_matches(info['properties'].get('Package'), part.get('packageId'))
        entry['packageMatches'] = matches
        height = height_of(info['properties'])
        entry['height'] = height
        notes = []
        if matches is False:
            notes.append('PACKAGE MISMATCH: LCSC %s, OpenPnP %s'
                         % (info['properties'].get('Package'), part.get('packageId')))
            problems += 1
        if height is None:
            notes.append('height not on LCSC (now %.2f mm), see %s'
                         % (part.get('height') or 0, info['datasheet']))
            if not part.get('height'):
                problems += 1
        print('%-16s %s\n    %s' % (part_id, name, '; '.join(notes) or 'height %.2f mm' % height))
        if args.apply and matches is not False:
            body = {'partId': part_id, 'name': name,
                    'reason': 'lookup_parts.py: LCSC %s' % code}
            if height is not None:
                body['height'] = height
            result = bridge_call(config_dir, 'POST', '/part/update', body=body)
            entry['written'] = result.get('status') == 'ok'
            if not entry['written']:
                print('    writing failed: %s' % result.get('error'))
                problems += 1
    report['status'] = 'failed' if problems else 'done'
    if problems:
        report['message'] = ('%d problem(s): parts not found, lookups failed, package mismatches '
                             'or unknown heights (read the datasheets)' % problems)
    path = reports.finish(directory, report)
    print(('Not written (no --apply). ' if not args.apply else '') + 'Report: ' + path)
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
