"""Collect this task's evidence and copy only verified delivery artifacts."""
import datetime
import difflib
import hashlib
import json
import pathlib
import shutil
import xml.etree.ElementTree as ET
import zipfile

root = pathlib.Path(__file__).resolve().parent.parent
record = root / '.verification/s5-20261107'
run = 'build-07-final'
process = json.loads((record / f'{run}-processes.json').read_text(encoding='utf-8-sig'))
assert process['ExitCode'] == 0 and process['CleanupOkay'] and not process['RemainingJava'], process
log = (record / f'{run}.log').read_text(encoding='utf-8-sig')
assert 'BUILD SUCCESSFUL' in log and 'Verified 31 safety handlers' in log
probe = json.loads((record / 'runtime/s5-probe-result.json').read_text())
assert probe['passed'] and f"S5_PROBE_PASSED checks={probe['checks']}" in log

groups = {}
for name in ('test', 'apiRuntimeRegressionTest', 'greedPouchTest', 'replacementRoleBootstrapTest'):
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for file in (root / 'build/test-results' / name).glob('TEST-*.xml'):
        suite = ET.parse(file).getroot()
        for key in totals:
            totals[key] += int(suite.attrib.get(key, 0))
    assert totals['tests'] > 0 and totals['failures'] == 0 and totals['errors'] == 0, (name, totals)
    totals['passed'] = totals['tests'] - totals['skipped']
    groups[name] = totals
totals = {key: sum(value[key] for value in groups.values()) for key in next(iter(groups.values()))}

with zipfile.ZipFile(record / 'source-before.zip') as archive:
    before = {name.replace('\\', '/'): archive.read(name) for name in archive.namelist() if not name.endswith('/')}
after = {path.relative_to(root).as_posix(): path.read_bytes() for path in (root / 'src').rglob('*') if path.is_file()}
changes, patch = [], []
for name in sorted(set(before) | set(after)):
    if not name.startswith('src/'):
        continue
    old, new = before.get(name, b''), after.get(name, b'')
    if old == new:
        continue
    changes.append({'path': name, 'before': hashlib.sha256(old).hexdigest() if old else None,
                    'after': hashlib.sha256(new).hexdigest() if new else None})
    old_text = old.decode('utf-8-sig').replace('\r\n', '\n')
    new_text = new.decode('utf-8-sig').replace('\r\n', '\n')
    patch.extend(difflib.unified_diff(old_text.splitlines(True), new_text.splitlines(True),
                    fromfile='a/' + name if name in before else '/dev/null',
                    tofile='b/' + name if name in after else '/dev/null'))
(record / 'changes-vs-task-start.patch').write_text(''.join(patch), encoding='utf-8')
(record / 'changed-source-files.json').write_text(json.dumps(changes, ensure_ascii=False, indent=2), encoding='utf-8')

history = []
for file in sorted(record.glob('*-processes.json')):
    entry = json.loads(file.read_text(encoding='utf-8-sig'))
    assert not entry['Launched'] or entry['CleanupOkay'] and not entry['RemainingJava'], entry
    history.append({'name': entry['Name'], 'exitCode': entry['ExitCode'],
                    'launched': entry['Launched'], 'ownedJava': len(entry['OwnedJava']),
                    'cleanupOkay': entry['CleanupOkay'], 'remainingJava': len(entry['RemainingJava'])})

jar = root / 'build/libs/habitrain_core-2.0.27.jar'
with zipfile.ZipFile(jar) as archive:
    fixture_entries = [name for name in archive.namelist() if 'S5Probe' in name or 's5-probe' in name or 'S4Probe' in name]
    assert not fixture_entries, fixture_entries
destination = root.parent / '临时/habitrain_core-2.0.27.jar'
shutil.copy2(jar, destination)
digest = hashlib.sha256(jar.read_bytes()).hexdigest()
assert digest == hashlib.sha256(destination.read_bytes()).hexdigest()

result = {'date': datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).isoformat(),
          'run': run, 'buildSucceeded': True, 'testGroups': groups, 'testTotals': totals,
          'productionProbe': probe, 'productionSafetyHandlers': 31,
          'changedSourceFiles': len(changes), 'javaProcesses': process,
          'runHistory': history, 'jar': str(destination), 'sha256': digest,
          'fixtureEntriesInRelease': fixture_entries,
          'userDecision': '保留每次更新实例重置倒计时',
          'acceptance': '代码与自动验证交付；真人客户端、多人数及极限地图仍待验收'}
(record / 'final-verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')

report = root / 'docs/S5-2026-11-07/S5修复与验证报告.md'
text = report.read_text(encoding='utf-8')
table = f'''## 最终验证结果

| 检查 | 结果 |
| --- | --- |
| 最终wrapper完整build / 生产服务端 / Mixin验证 | 通过，`{run}.log` |
| 自动测试 | **{totals['passed']}通过、{totals['skipped']}跳过、0失败，共{totals['tests']}** |
| 独立生产S5夹具 | **{probe['checks']}项检查通过** |
| 生产安全Mixin导出检查 | 31个处理器通过；类装载与注入证据不代替玩法验收 |
| 捕获Tick采样 | {probe['captureSamples']}次，P95 {probe['captureP95Nanos']/1e6:.3f}ms，最大{probe['captureMaxNanos']/1e6:.3f}ms |
| 最终构建Java子进程 | 跟踪{len(process['OwnedJava'])}个，剩余0个，CleanupOkay=true |
| 本次相对起点的源码/测试文件 | {len(changes)}个 |
| 交付SHA-256 | `{digest}`，源包与临时目录一致 |

常规test：{groups['test']['passed']}通过、{groups['test']['skipped']}跳过；真实Minecraft引导专项：{groups['apiRuntimeRegressionTest']['passed']} + {groups['greedPouchTest']['passed']} + {groups['replacementRoleBootstrapTest']['passed']}通过。历史失败和修正均保留日志；包括测试兼容预期、空profile预览兼容、验证任务顺序及目标维修类装载。未降低最终检查要求。

'''
assert '## 最终验证结果' not in text
text = text.replace('## 仍需验收的边界', table + '## 仍需验收的边界')
report.write_text(text, encoding='utf-8')
shutil.copy2(report, root.parent / '临时/habitrain_core_S5修复与验证_2026-11-07.md')

tracker = root / 'docs/S0-2026-11-06/324项修复追踪表.md'
shutil.copy2(tracker, record / 'tracker-before.md')
old_tracker = tracker.read_text(encoding='utf-8-sig')
start, end = old_tracker.index('## S5：'), old_tracker.index('## S6：')
lines = old_tracker[start:end].splitlines(True)
count = 0
for index, line in enumerate(lines):
    if line.startswith('| SCN-') or line.startswith('| CORE-12 '):
        columns = line.rstrip('\n').split('|')
        columns[-2] = ' 实现及自动验证已交付；真人/规模待验收，见S5逐项记录 '
        lines[index] = '|'.join(columns) + '\n'
        count += 1
assert count == 16, count
new_tracker = old_tracker[:start] + ''.join(lines) + old_tracker[end:]
tracker.write_text(new_tracker, encoding='utf-8')
(record / 'tracker-update.patch').write_text(''.join(difflib.unified_diff(
    old_tracker.splitlines(True), new_tracker.splitlines(True),
    fromfile='a/docs/S0-2026-11-06/324项修复追踪表.md',
    tofile='b/docs/S0-2026-11-06/324项修复追踪表.md')), encoding='utf-8')
print(json.dumps({'tests': totals, 'production': probe, 'sha256': digest, 'changedSourceFiles': len(changes),
                  'finalJavaCount': len(process['OwnedJava']), 'remainingJava': len(process['RemainingJava']),
                  'jar': str(destination), 'report': str(report)}, ensure_ascii=False))
