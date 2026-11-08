"""Prepare frozen local-only runtime fixtures. Does not launch Minecraft or accept its EULA."""
from pathlib import Path
from zipfile import ZipFile
import hashlib
import json
import shutil

ROOT = Path(__file__).resolve().parent.parent
RECORD = ROOT / '.verification/s0-20261106'
DEST = RECORD / 'runtime'

def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def copy(source, target):
    target = target.resolve()
    if not target.is_relative_to(DEST.resolve()):
        raise RuntimeError(f'Runtime path outside fixture directory: {target}')
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        raise RuntimeError(f'Refusing to overwrite existing runtime evidence: {target}')
    shutil.copy2(source, target)

if DEST.exists():
    raise SystemExit('Runtime fixture directory already exists; preserve it and use a new record directory for another campaign.')
artifact = ROOT / 'build/libs/habitrain_core-2.0.27.jar'
if not artifact.is_file():
    raise SystemExit('Build the remapped production JAR first.')

local = [p for p in (ROOT / 'libs').glob('*.jar') if not p.name.endswith('-dev.jar')]
external = []
coordinates = [
    ('net.fabricmc.fabric-api', 'fabric-api', '0.116.13+1.21.1'),
    ('org.ladysnake.cardinal-components-api', 'cardinal-components-base', '6.1.3'),
    ('org.ladysnake.cardinal-components-api', 'cardinal-components-entity', '6.1.3'),
    ('org.ladysnake.cardinal-components-api', 'cardinal-components-world', '6.1.3'),
    ('org.ladysnake.cardinal-components-api', 'cardinal-components-scoreboard', '6.1.3'),
    ('me.shedaniel.cloth', 'cloth-config-fabric', '15.0.140'),
    ('com.terraformersmc', 'modmenu', '11.0.3'),
    ('dev.upcraft.datasync', 'datasync-minecraft-1.21.1-fabric', '0.10.2'),
]
cache = ROOT / '.gradle-home/caches/modules-2/files-2.1'
for group, module, version in coordinates:
    matches = [p for p in (cache / group / module / version).glob('*/*.jar')
               if not p.name.endswith(('-sources.jar', '-javadoc.jar'))]
    if len(matches) != 1:
        raise SystemExit(f'Need exactly one original runtime artifact for {group}:{module}:{version}, found {matches}')
    external.append(matches[0])

# SRE itself requires voicechat and modmenu. The no-voicechat case is a negative dependency-resolution case.
sets = {
    'production-full': local + external,
    'production-minimal': [p for p in local if not p.name.startswith('TACZ-')] + external,
    'production-no-voicechat': [p for p in local if not p.name.startswith(('TACZ-', 'voicechat-'))] + external,
    'client-minimal': [p for p in local if not p.name.startswith('TACZ-')] + external,
}
source_config = ROOT / 'run/config'
config_files = [p for p in source_config.glob('habitrain*.json') if p.is_file()]
manifest = {'release': {'file': artifact.name, 'sha256': sha(artifact)}, 'sets': {}, 'configs': []}
for name, dependencies in sets.items():
    run = DEST / name
    mods = [artifact] + dependencies
    for source in mods:
        copy(source, run / 'mods' / source.name)
    (run / 'world').mkdir(parents=True, exist_ok=True)
    (run / 'config').mkdir(parents=True, exist_ok=True)
    for source in config_files:
        copy(source, run / 'config' / source.name)
    (run / 'eula.txt').write_text('eula=false\n', encoding='utf-8')
    (run / 'server.properties').write_text(
        'server-ip=127.0.0.1\nserver-port=25585\nonline-mode=true\nlevel-name=world\n'
        'enable-rcon=false\nenable-query=false\nview-distance=3\nsimulation-distance=3\n', encoding='utf-8')
    (run / 'mixin-jvm-args.txt').write_text(
        '-Dmixin.debug.export=true\n-Dmixin.debug.export.decompile=false\n'
        '-Dmixin.debug.verbose=true\n', encoding='utf-8')
    manifest['sets'][name] = [{'file': p.name, 'sha256': sha(p)} for p in mods]
for source in config_files:
    manifest['configs'].append({'file': source.name, 'sha256': sha(source)})
    copy(source, DEST / 'fixtures/original-config' / source.name)
for name in ['development-server', 'development-client']:
    run = DEST / name
    (run / 'world').mkdir(parents=True, exist_ok=True)
    (run / 'config').mkdir(parents=True, exist_ok=True)
    for source in config_files:
        copy(source, run / 'config' / source.name)
    (run / 'eula.txt').write_text('eula=false\n', encoding='utf-8')
    (run / 'server.properties').write_text(
        'server-ip=127.0.0.1\nserver-port=25585\nonline-mode=true\nlevel-name=world\n'
        'enable-rcon=false\nenable-query=false\nview-distance=3\nsimulation-distance=3\n', encoding='utf-8')
for host in ['client-minimal', 'development-client']:
    for name in ['S0-A', 'S0-B']:
        (DEST / host / 'saves' / name).mkdir(parents=True, exist_ok=True)
bad = DEST / 'fixtures/bad-files'
bad.mkdir(parents=True, exist_ok=True)
(bad / 'invalid-utf8.json').write_bytes(b'{"value":"\xff"}')
(bad / 'invalid-utf8.json.bak').write_text('{"value":7}\n', encoding='utf-8')
(bad / 'truncated.json').write_text('{"value":', encoding='utf-8')
(bad / 'truncated.json.bak').write_text('{"value":7}\n', encoding='utf-8')
(DEST / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({'prepared': list(sets), 'config_snapshots': len(config_files),
                  'minecraft_started': False, 'eula_accepted': False}, ensure_ascii=False))
