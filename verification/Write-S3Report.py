"""Materialize S3 dispositions and task-only evidence after a successful validation."""
from pathlib import Path
from collections import Counter
from zipfile import ZipFile
import difflib
import hashlib
import json
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
EVIDENCE = ROOT / '.verification/s3-20261106'

# Each line is one primary S3 tracker item, including inherited fixes.
DATA = '''
CLI-15|原有修复已回归|RoleActionClientSessionTest：断线发送失败结束 pending，不再挂到超时。
MIXIN-SRV-14|本次修复已验证|通配方法选择器覆盖全部 CIVILIAN 回退；拆分 vigilanteCount 必需注入；外部样例检查实际导出的四个轮换类。
ROLE-API-01|原有修复已回归|RoleInventoryProfile 两端复制；隔离服两名构造玩家连续领取同一模板，数量互不污染。
ROLE-API-02|部分修复，待契约确认|普通 ShopEntry 已复制；自定义子类保留对象以保留行为，无法保证深复制；live Supplier 的相等性仍依赖函数对象。
ROLE-API-03|原有修复已回归|动作 Context/Result 在构造及访问时复制 byte[]，见 RoleActionApiTest。
ROLE-API-05|原有修复已回归|有效角色去重；别名/替换解析回归与外部附属模组样例。
ROLE-API-06|原有修复已回归|现有 canSeeCoin/canBeRandomed 默认 true 并支持显式 boolean；本次未再次改动默认值。
ROLE-API-07|原有修复已回归|现有队友可见性默认 true 与显式覆盖保留；本次未更改玩法默认值；真实客户端视野仍需验收。
ROLE-API-08|部分修复，待契约确认|空白阵营已拒绝；首个 REMOVE_WINNER 仍会被 merge 丢弃。需要可表达顺序操作的新兼容模型，并与 S4 胜者消费联验。
ROLE-API-09|部分修复，待契约确认|纯内存同步槽与 Builder resetOn 复制已有修复；dataVersion>1 且无迁移链是否可表示高版本新槽，仍需明确。
ROLE-API-11|原有修复已回归|匹配替换按 ID 保留位置并校验重复；shopPatch/shopTransform 互斥；修改/替换模型测试。
ROLE-API-12|原有修复已回归|InstinctDecision 构造约束与客户端扩展规则测试。
ROLE-API-13|原有修复已回归|DiagnosticReport 复制；有效 profile 的 opposingTwoWay/skills 从实际托管角色与技能后端读取；RoleProfileParityTest。
ROLE-API-14|本次修复已验证|将 registrar 文档改为禁用的二进制兼容门面，明确无查询方法并指向 Catalog/Diagnostics。
ROLE-API-15|待 Mike 确认|现有生成概率 100、人数窗口 0/0 未改；上游默认概率及人数窗口为 -1。需确认默认玩法和兼容策略后修改。
ROLE-API-16|部分修复，待契约确认|Decision 已区分 ALLOW/PASS，默认 DENY 优先；null 仍按 PASS。是否允许 ALLOW 短路需与权限/钩子策略一起确认。
ROLE-API-17|原有修复已回归|目标头开销与字节上限、long 冷却换算已在 S1 修复；动作模型/网络边界回归。
ROLE-API-18|本次修复已验证|支持空 REPLACE_ALL、匹配替换保留位置、翻译键匹配、Component 防御性复制；RoleBookPatchTest。
ROLE-API-19|原有修复已回归|无命名空间解析策略和不带运行句柄的 profile 查询已有回归；RoleCatalogQueryTest/RoleProfileParityTest。
ROLE-API-20|部分修复，待契约确认|hide/color 互斥已校验；DYNAMIC 仍缺 resolver/运行实现，不能声称动态皮肤可用。
ROLE-API-21|原有修复已回归|WORLD 维度语义文档、faction 派生、非有限/负距离评估拒绝已有修复；未知距离仍按既有契约放行。
ROLE-API-22|原有修复已回归|阵营互斥组合在构建时校验；RoleProfileParityTest。
ROLE-API-24|本次修复已验证|保留全部位置构造器兼容性；为 44 字段 profile 和 41 字段 overlay 新增具名 builder/toBuilder，迁移内部装配，测试独立布尔字段、显式空技能替换、回调与往返一致。
ROLE-API-25|部分修复，待契约确认|RoleQuery 的过滤条件已有实现；废弃客户端门面的抛异常入口仍保留。删除或替代公开签名需确定兼容方案。
ROLE-CMP-01|原有修复已回归|完整别名/替换图、解析顺序与循环拒绝已有测试；外部附属模组实际验证 ALIAS→REPLACE。
ROLE-CMP-02|原有修复已回归|链式替换有效/隐藏互斥及禁用 ADD 不被复活已有回归。
ROLE-CMP-03|原有修复已回归|旧目标链补丁先于新角色补丁；外部样例以旧 LATE、新 EARLY 检查新角色值优先。
ROLE-CMP-04|原有修复已回归|非标量写入参与冲突提取；中立阵营副作用恢复已有修复；本次另使运行期冲突筛选不初始化上游注册表。
ROLE-CMP-05|原有修复已回归|列表匹配替换及技能重复/槽位顺序已有回归。
ROLE-CMP-08|本次修复已验证|沿用本局 frozen manifest，并扩展至结算；重连发送不重写全局本局哈希。真实网络重连仍待验收。
ROLE-CMP-09|本次修复已验证|结算期更新保留最新 pending；激活先清 settlement，立即再开局取消旧的晋升任务；RoleSnapshotTest。
ROLE-CMP-10|部分修复，待运行契约|本次 v4 指纹增加 ADD 工厂定义、物品/商店内容、书页正文、技能/关系列表顺序；动态注册表组件回退与可执行回调仍依赖 provider 版本。
ROLE-CMP-13|本次修复已验证|真实 role.spawnInfo 单独深复制/恢复；不以默认字段覆盖管理员值；外部管理员在首次基线之后再次重载仍需场景验收。
ROLE-CMP-14|原有修复已回归|保留原 ID 替换写回上游映射、商店与技能注册已有隔离引导测试。
ROLE-CMP-16|部分修复，待边界确认|新增在线、模式、名额、断线清理；精确/阵营队列已有互斥清理。局中排下一局角色仍按当前快照解析，pending 资格需明确。
ROLE-CMP-06|原有修复已回归|入口 ModID 排序与书页 PatchPriority 顺序已有实现；本次远端书页也按链及优先级排序。
ROLE-CMP-07|原有修复已回归|事务 commit 在任何写入前检查 NEW_ID_WITH_ALIAS 与上游 ID 碰撞，失败只回滚本提供者。
ROLE-CMP-15|部分修复，待工厂约定|本次补自定义 roleFactory 书页定义的冻结解析；自定义工厂的库存/商店、运行时改阵营及上游缓存一致性未全闭环。
ROLE-CMP-17|本次修复已验证|目录读取冻结 profile；书页随快照冻结；客户端按服务端 active entry IDs 折叠，忽略本地开关。
ROLE-CMP-18|本次修复已验证|沿用客户端默认 key 格式对齐；本次同步 ADD/MODIFY/REPLACE/ALIAS 生效 entry IDs，排除冲突声明，防止被服务端拒绝的补丁在客户端继续生效；真实 HUD/皮肤仍待验收。
ROLE-CMP-19|原有修复已回归|FieldTrace 使用原始基线，providers 包含行为/状态声明；诊断回归。
ROLE-CMP-20|原有修复已回归|诊断按实际 current/settlement 快照打印，结算期不再硬编码 lobby。
ROLE-CMP-21|本次修复已验证|TMMRoles.<clinit> 作为内置来源保留；生产启动日志检查来源归属。
ROLE-RT-01|原有修复已回归|每玩家转职重入守卫已有实现；RoleChangeTransactionTest/RoleChangeApiTest。
ROLE-RT-02|部分修复，待回滚约定|新增玩家状态检查点及暂缓同步，失败恢复，移除提交后才发事件/同步，冷却成功后清理；同角色重初始化与外部不可逆钩子/物品副作用仍不保证完整撤销。
ROLE-RT-06|S4 联合验收|本次未修改权威胜者消费；需与 S4 finalizeGame/stopGame、实际下游契约联合处理，不关闭。
ROLE-RT-07|待 Mike 确认|现有熔断已不再统一 DENY，但统一 PASS 仍不满足计划；须确定死亡/结束与敏感行为各自失败策略。
ROLE-RT-09|本次修复已验证|沿用断线不清冷却、局边界清理；角色特有冷却延迟至转职成功，失败保留；仍需真实重连验收。
ROLE-RT-08|原有修复已回归|全局 Hook 优先级、状态切换 gate 失效、同角色多持有人均派发已有回归；补齐参与者测试游戏类型初始化。
ROLE-RT-11|本次修复已验证|提供者上限统一为 4096、拒绝截断、65 项真实 codec 往返；沿用 S1 长度/限流/失败回调与本地定义指纹校验。
ROLE-RT-14|本次修复已验证|状态与语音/聊天 gate 随局冻结；客户端单调 revision、删除墓碑、重叠快照/增量交错清理回归。
API-TV-05|部分修复，需 SPI 边界方案|投票保留通道拒绝已有修复；CoreSpi 原始 bridge 公开入口与 SceneInstanceBridge 体积/底层类型暴露仍在，未冒险破坏下游二进制兼容。
API-TV-06|原有修复已回归|supports(role.v2/match.state) 查询真实 RoleSpi/SRE 安装探针，不再常量 true；API 能力测试。
'''


def main():
    data = {}
    for line in DATA.strip().splitlines():
        key, status, details = line.split('|', 2)
        assert key not in data
        data[key] = {'status': status, 'details': details}
    tracker_path = ROOT / 'docs/S0-2026-11-06/324项修复追踪表.md'
    tracker = tracker_path.read_text(encoding='utf-8-sig')
    start = tracker.index('## S3：')
    end = tracker.index('## S4：', start)
    section = tracker[start:end]
    ids = re.findall(r'^\| ([A-Z][A-Z0-9-]+) \|', section, re.M)
    assert len(ids) == 53 and set(ids) == set(data), (len(ids), set(ids) ^ set(data))

    # Do not turn an unsuccessful or unclean validation into a delivery report.
    run_name = 's3-runtime-13'
    processes = json.loads((EVIDENCE / (run_name + '-processes.json')).read_text(encoding='utf-8-sig'))
    assert processes['ExitCode'] == 0 and processes['CleanupOkay'] and not processes['RemainingJava']
    probe = json.loads((EVIDENCE / 'runtime/s3-probe-result.json').read_text())
    assert probe['passed']
    tests = {}
    for task in ['test', 'apiRuntimeRegressionTest', 'greedPouchTest', 'replacementRoleBootstrapTest']:
        tally = Counter()
        skips = []
        for file in sorted((ROOT / 'build/test-results' / task).glob('TEST-*.xml')):
            xml = ET.parse(file).getroot()
            for key in ['tests', 'failures', 'errors', 'skipped']:
                tally[key] += int(xml.attrib.get(key, 0))
            skips.extend(xml.attrib['name'] + '#' + t.attrib['name'] for t in xml.findall('testcase') if t.find('skipped') is not None)
        assert tally['tests'] > 0 and tally['failures'] == tally['errors'] == 0, (task, tally)
        tests[task] = dict(tally, skipped_cases=skips)
    artifact = ROOT / 'build/libs/habitrain_core-2.0.27.jar'
    delivery = ROOT.parent / '临时/habitrain_core-2.0.27.jar'
    sha = lambda file: hashlib.sha256(file.read_bytes()).hexdigest()
    assert sha(artifact) == sha(delivery)
    with ZipFile(artifact) as jar:
        assert not any('S3Probe' in p or p.startswith('com/habitrain/verification/') for p in jar.namelist())

    patch = []
    changes = []
    with ZipFile(EVIDENCE / 'source-before.zip') as baseline:
        original = {i.filename: baseline.read(i) for i in baseline.infolist() if not i.is_dir()}
        source_paths = set(p for p in original if p.startswith('src/'))
        source_paths.update(p.relative_to(ROOT).as_posix() for p in (ROOT / 'src').rglob('*') if p.is_file())
        for name in sorted(source_paths | {'build.gradle', 'gradle.properties'}):
            target = ROOT / name
            before = original.get(name, b'')
            after = target.read_bytes() if target.is_file() else b''
            if before.replace(b'\r\n', b'\n') == after.replace(b'\r\n', b'\n'):
                continue
            changes.append({'path': name, 'before_sha256': hashlib.sha256(before).hexdigest(), 'after_sha256': hashlib.sha256(after).hexdigest()})
            patch.extend(difflib.unified_diff(before.decode('utf-8-sig').splitlines(True), after.decode('utf-8-sig').splitlines(True), fromfile='a/' + name, tofile='b/' + name))
    (EVIDENCE / 's3-only.patch').write_text(''.join(patch), encoding='utf-8')
    (EVIDENCE / 's3-changed-files.json').write_text(json.dumps(changes, ensure_ascii=False, indent=2), encoding='utf-8')
    result = {'stage_complete': False, 'run': run_name, 'tests': tests, 'probe': probe, 'jar_sha256': sha(artifact), 'delivery_sha256': sha(delivery), 'java_cleanup_okay': True, 'source_changes': len(changes), 'statuses': dict(Counter(d['status'] for d in data.values()))}
    (EVIDENCE / 'final-verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    (EVIDENCE / 'dispositions.json').write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')

    for key, disposition in data.items():
        section = re.sub(r'(^\| ' + re.escape(key) + r' \|.*?\| )[^|\n]+( \|$)', lambda m: m[1] + disposition['status'] + m[2], section, count=1, flags=re.M)
    note = '\n2026-11-06 S3 本轮处置已核查，阶段尚未关闭。逐项证据、未完成项及需要确认的契约见 [S3 修复与验证报告](../S3-2026-11-06/S3修复与验证报告.md)。\n\n'
    section = section.rstrip() + '\n' + note
    tracker_path.write_text(tracker[:start] + section + tracker[end:], encoding='utf-8')

    report = '''# habitrain_core S3 修复与验证报告（2026-11-06）

Mike，本轮已完成可明确修复部分和构建交付，**S3 尚未全阶段关闭**。公开胜利合并模型、生成默认值、钩子故障策略等仍需确认；同角色重初始化和自定义工厂等还有实质未完成部分。不能把“构建通过”解释为 53 项全部完成。

## 范围和基线

项目：`D:/Backup/mc mod/哈比列车api`，Fabric 1.21.1 / Java 21，版本 2.0.27。保留进入任务前的大量未提交修改，没有提交或推送，没有修改上游 DLC 或下游抽奖模组。

本次源码改动以 `.verification/s3-20261106/source-before.zip` 为基线生成 `s3-only.patch` 和逐文件哈希，不能以全部 HEAD 差异作为本任务工作量。起始 HEAD 记录在同目录 `head.txt`，原始未提交补丁在 `baseline-tracked.patch`。旧交付包在 `delivery-before.jar`。

## 本次修复

- 职业书：支持清空、保持原槽位的匹配替换、语言无关标题匹配和文本复制；服务端按快照冻结，客户端根据服务端有效 entry IDs 折叠；补齐自定义 ADD 工厂书页读取。
- 快照：补齐状态、语音/聊天和角色声明开关冻结；结算窗口保留本局 manifest；结算期间连续修改与立即再开局不会恢复旧 pending。
- 客户端规则：有效 entry IDs 采用与编译器相同的冲突分析，冲突声明不会被误报为生效规则或恢复职业书补丁。
- 同步：客户端拒绝旧 revision、保留删除墓碑，处理完整快照和新增量交错；manifest 提供者编码/解码上限一致，65 项可实际往返，超 4096 明确拒绝。
- 转职：玩家状态事务暂缓通知，失败恢复内存/持久状态和迁移标记，提交后清角色冷却；remove 延迟事件/发包。此项不承诺撤销任意外部钩子或任意可变对象内部修改。
- 发牌与基线：真实 spawnInfo 独立复制/恢复；强制发牌拒绝离线、其他模式和超在线名额玩家，断线清队列。
- 定义指纹：v4 覆盖初始物品、标准商店条目、书页正文、技能和关系列表顺序；动态组件无法完整编码时采用确定性回退，回调实现和动态工厂仍依赖 provider 版本。
- Mixin：轮换 CIVILIAN 替代不再依赖固定 lambda 编号；必需注入拆分；保留 TMMRoles 静态初始化来源；纯编译/指纹/运行补丁筛选避免为了诊断而初始化上游注册表。
- registrar 文档明确为禁用兼容门面，并给出实际查询 API。
- 大参数构造：为 EffectiveRoleProfile/CompiledModifyOverlay 新增 builder/toBuilder，内部装配改为字段名赋值；原构造器保留。

## 构建和运行证据

执行项目 wrapper，所有命令都带 `--no-daemon`。最终命令由 `verification/Invoke-S3Gradle.ps1` 运行：

```powershell
.\\gradlew.bat build s3ProductionServer verifyProductionMixins --offline -I .verification/s0-20261106/offline-cache.init.gradle -I .verification/s3-20261106/production.init.gradle -Ps0RuntimeDir=.verification/s3-20261106/runtime --no-daemon
```

其中 S3 init 脚本仅为验证注册独立任务，不修改正常发行内容。失败轮次和原始错误均保留在证据目录，未删日志、未跳过失败用例。参与者测试原先缺少游戏类型初始化，本次补上 Bootstrap；最终只保留原有两项合法跳过。

| 测试任务 | 总数 | 失败/错误 | 跳过 |
| --- | ---: | ---: | ---: |
'''
    for task, tally in tests.items():
        report += f"| {task} | {tally['tests']} | {tally['failures']}/{tally['errors']} | {tally['skipped']} |\n"
    total = sum(t['tests'] for t in tests.values())
    skipped = sum(t['skipped'] for t in tests.values())
    report += f'\n合计 {total} 项，{total - skipped} 通过、{skipped} 跳过。跳过用例：\n\n'
    for tally in tests.values():
        report += ''.join(f'- `{case}`\n' for case in tally['skipped_cases'])
    report += f'''
隔离专用服只监听 `127.0.0.1:25589`，使用独立 `s3-isolated-world`。外部 Fabric 附属模组通过公开 `habitrain:role_extensions` 注册 ADD/MODIFY/REPLACE/ALIAS、状态和职业书；启动后共 {probe['checks']} 项断言通过。实际检查两名构造 ServerPlayer 的独立物品、在线名额与跨模式拒绝、生产 CCA 转职故障后的角色/状态回滚、回合/结算冻结以及四个轮换类导出字节码。生产 remap 注入检查通过。

**验证边界：** 构造玩家没有真实客户端网络连接；此结果不能代替真实双人联机、HUD/职业书页面显示、网络重连、长时间连续对局验收。测试附属模组单独 remap，已检查发行包不包含 `S3Probe`。

隔离环境日志仍包含上游数据标签缺引用、上游可选 Mixin 目标缺失以及 Mojang 公钥请求 TLS 告警；本次启动和上述断言继续通过，未将这些上游/网络告警解释为真实客户端登录已验证。

最终运行日志：`.verification/s3-20261106/{run_name}.log`；外部模组结果：`runtime/s3-probe-result.json`；进程记录：`{run_name}-processes.json`。每个失败或成功轮次均在 finally 等待并核对本次 Java 后代，最终及既有运行均无本次遗留 Java；未终止其他 Minecraft、IDE 或开发进程。

发行包：`D:/Backup/mc mod/临时/habitrain_core-2.0.27.jar`

SHA-256：`{sha(artifact)}`。与 `build/libs/habitrain_core-2.0.27.jar` 一致。

## 53 项逐项处置

“原有修复已回归”表示保留并验证任务开始前已有实现，不归为本轮新增修复。运行场景仍可能有明确列出的验收边界。

| 编号 | 处置 | 证据或剩余问题 |
| --- | --- | --- |
'''
    for key in ids:
        report += f"| {key} | {data[key]['status']} | {data[key]['details']} |\n"
    report += '''
## 需要 Mike 确认的方案

1. **默认生成规则（ROLE-API-15）**：建议未显式配置时对齐上游：概率/最小人数/最大人数为 -1、最大持有人数仍为 1。现有显式配置保留；需确认是否接受未配置新角色的行为变化。
2. **胜利与决定合并（ROLE-API-08/16、ROLE-RT-07、S4 RT-06）**：建议新增可保留顺序的 WinPatch 操作列表，保留旧签名作兼容适配；默认 DENY 优先、不让 ALLOW 绕过后续拒绝；死亡/结束钩子故障 PASS，购买/动作等敏感检查故障 DENY。实现前需确定这张策略表，不能沿用统一 PASS。
3. **回滚与工厂边界（ROLE-RT-02、CMP-15/16、API-02/09/20/25）**：需确定同角色重初始化失败是否接受只能回滚托管角色/状态，或要求新增可撤销生命周期；自定义工厂的 Profile 是强制覆盖还是工厂优先；自定义 ShopEntry 采用显式复制器；新高版本状态槽是否允许没有迁移链；DYNAMIC 应补 resolver 还是明确拒绝；废弃门面仅保留兼容还是提供迁移接口；局中下一局强制发牌的资格采用当前还是 pending 快照。每项都影响下游调用或玩法，当前没有擅自决定。
4. **SPI 边界尚未完成（API-TV-05）**：逐步收拢 SPI 原始门面，避免一次删除下游仍可能调用的二进制入口。哪些入口仅属内部、哪些下游可用需确定兼容方案，未经确认不视为延期关闭。API-24 的具名构建入口与内部迁移已在本轮完成。

真实联机和 S4 权威结算联合验收完成前，不给 S3 全绿结论。
'''
    report_dir = ROOT / 'docs/S3-2026-11-06'
    report_dir.mkdir(parents=True, exist_ok=True)
    (report_dir / 'S3修复与验证报告.md').write_text(report, encoding='utf-8')
    (ROOT.parent / '临时/habitrain_core_S3修复与验证报告_2026-11-06.md').write_text(report, encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
