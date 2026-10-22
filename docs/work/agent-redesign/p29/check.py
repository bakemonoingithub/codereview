"""P29 lightweight local checks. No business services or external resources."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[4]
HERE = Path(__file__).resolve().parent
errors = []
paths = [ROOT / 'AGENTS.md', ROOT / 'docs/STATE.md', ROOT / 'docs/ROADMAP.md']
paths.append(HERE.parent / 'business-contract.md')
paths += sorted(p for p in HERE.rglob('*') if p.is_file() and p.suffix in {'.md', '.html', '.py', '.cjs'})
for path in paths:
    raw = path.read_bytes()
    if raw.startswith(b'\xef\xbb\xbf'):
        errors.append(f'BOM: {path.relative_to(ROOT)}')
    if b'\r' in raw:
        errors.append(f'CRLF: {path.relative_to(ROOT)}')
    try:
        content = raw.decode('utf-8')
    except UnicodeDecodeError:
        errors.append(f'UTF-8: {path.relative_to(ROOT)}')
        continue
    if path.suffix == '.md':
        for target in re.findall(r'\]\(([^)]+)\)', content):
            if re.match(r'^[a-z]+:', target) or target.startswith('#'):
                continue
            local = target.split('#', 1)[0]
            if local and not (path.parent / local).exists():
                errors.append(f'Link: {path.relative_to(ROOT)} -> {target}')
state = (ROOT / 'docs/STATE.md').read_text(encoding='utf-8')
roadmap = (ROOT / 'docs/ROADMAP.md').read_text(encoding='utf-8')
current = {}
for name, content in [('STATE', state), ('ROADMAP', roadmap)]:
    match = re.search(r'^当前:[ \t]*(\S+)', content, re.M)
    current[name] = match[1] if match else None
if current['STATE'] != current['ROADMAP'] or current['STATE'] is None:
    errors.append('STATE/ROADMAP current task mismatch')
items = {}
for match in re.finditer(r'^\| (P\d+(?:\.\d+)?) \| ([^|]+) \| (todo|active|done) \| ([^|]+) \|', roadmap, re.M):
    item_id, title, status, depends = match.groups()
    if item_id in items:
        errors.append(f'Duplicate roadmap ID: {item_id}')
    dependencies = [] if depends.strip() == '-' else [d.strip() for d in depends.split(',')]
    items[item_id] = {'status': status, 'dependencies': dependencies}
for phase in range(29, 34):
    if f'P{phase}' not in items:
        errors.append(f'Missing phase P{phase}')
for item_id, item in items.items():
    for dep in item['dependencies']:
        if dep not in items:
            errors.append(f'Unknown dependency: {item_id} -> {dep}')
        elif item['status'] in {'done', 'active'} and items[dep]['status'] != 'done':
            errors.append(f'Unfinished dependency: {item_id} -> {dep}')
    children = [value for key, value in items.items() if key.startswith(item_id + '.')]
    if item['status'] == 'done' and any(child['status'] != 'done' for child in children):
        errors.append(f'Done parent has unfinished children: {item_id}')
active = [key for key, value in items.items() if value['status'] == 'active']
if current['STATE'] == '-':
    if active:
        errors.append('Current task is empty but active items exist')
elif current['STATE'] not in active:
    errors.append('Current task is not active in ROADMAP')
elif any(key.split('.')[0] != current['STATE'].split('.')[0] for key in active):
    errors.append('Active items span multiple phases')
visiting, visited = set(), set()
def visit(item_id):
    if item_id in visiting:
        errors.append(f'Dependency cycle at {item_id}')
        return
    if item_id in visited or item_id not in items:
        return
    visiting.add(item_id)
    for dep in items[item_id]['dependencies']:
        visit(dep)
    visiting.remove(item_id)
    visited.add(item_id)
for item_id in items:
    visit(item_id)
html = (HERE / 'prototype/index.html').read_text(encoding='utf-8')
if re.search(r'<(?:script|link|img)[^>]+(?:src|href)=["\']https?://', html):
    errors.append('Prototype contains external resource')
if re.search(r'\b(?:fetch|XMLHttpRequest|WebSocket)\s*\(', html):
    errors.append('Prototype contains network call')
if html.count('<dialog ') != 5 or 'smartSplit:false' not in html:
    errors.append('Prototype structural boundary mismatch')
for page in ['models', 'prompts', 'strategies']:
    if f'data-nav="{page}"' not in html:
        errors.append(f'Prototype missing management navigation: {page}')
print(f'Checked {len(paths)} text files and {len(items)} roadmap items; status, dependencies and prototype boundaries checked.')
for error in errors:
    print('ERROR:', error)
sys.exit(bool(errors))
