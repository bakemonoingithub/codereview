// Isolated JavaScript checks with DOM stubs. This is not a browser or visual test.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const html = fs.readFileSync(path.join(__dirname, 'prototype/index.html'), 'utf8');
const source = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const elements = new Map();
const templates = [];
const queue = [];
function element(key) {
  if (!elements.has(key)) {
    const el = { value: '', textContent: '', checked: false, disabled: false,
      open: false, dataset: {}, classList: { add() {}, remove() {}, toggle() {} },
      showModal() { this.open = true; }, close() { this.open = false; },
      addEventListener() {}, click() {} };
    Object.defineProperty(el, 'innerHTML', {
      get() { return this.html || ''; },
      set(v) { this.html = v; templates.push(v); }
    });
    elements.set(key, el);
  }
  return elements.get(key);
}
const context = vm.createContext({
  document: {
    querySelector: element, getElementById: id => element('#' + id),
    querySelectorAll: () => [], createElement: () => element('download')
  },
  window: { scrollTo() {}, addEventListener() {} },
  navigator: { clipboard: { async writeText() {} } },
  confirm: () => true,
  setTimeout: fn => { queue.push(fn); return queue.length; }, clearTimeout() {},
  URL, Blob, console
});
vm.runInContext(source, context);
function run(code) { return vm.runInContext(code, context); }
let passed = 0;
function check(name, fn) { fn(); passed++; console.log('PASS:', name); }
check('Center filters by specialist type and empty search', () => {
  run("state.filterType='PATTERN';render()");
  assert.match(element('#app').innerHTML, /支付路由策略识别/);
  assert.doesNotMatch(element('#app').innerHTML, /订单提交与支付重试/);
  run("state.query='无匹配';render()");
  assert.match(element('#app').innerHTML, /没有符合条件/);
  run("state.filterType='ALL';state.query='';state.scenario='no-denominator';render()");
  assert.doesNotMatch(element('#app').innerHTML, /文件完成情况|问题判定准确率/);
  run("state.page='detail';state.type='ISSUE';render()");
  assert.match(element('#app').innerHTML, /本任务问题判定准确率/);
  assert.match(element('#app').innerHTML, /暂无明确判定/);
});
check('Each file renders its own code and version evidence', () => {
  run("state.page='browser';state.browserFile=1;render()");
  assert.match(element('#app').innerHTML, /interface PaymentGateway/);
  assert.doesNotMatch(element('#app').innerHTML, /public class OrderService/);
  run("openCode('PaymentGateway.java',8,14)");
  assert.match(element('#reader-body').innerHTML, /接口未约定幂等保证/);
  assert.equal(element('#reader-dialog').open, true);
  element('#reader-dialog').close();
});
check('File-name activation and review checkbox have independent actions', () => {
  run("state.page='browser';state.activeProjectId='project-order';state.browserFile=0;state.selected=new Set([0]);render()");
  const row = element('#app').innerHTML.match(/<div class="file-row [\s\S]*?<\/div>/)[0];
  assert.doesNotMatch(row, /<label/);
  assert.match(row, /onchange="toggleBrowserFile/);
  assert.match(row, /onclick="event.stopPropagation\(\);openBrowserFile/);
  element('#path-filter').value = 'Payment';
  run('openBrowserFile(1)');
  assert.equal(run('state.browserFile'), 1);
  assert.equal(run('[...state.selected].join()'), '0');
  assert.match(element('#app').innerHTML, /interface PaymentGateway/);
  assert.equal(element('#path-filter').value, 'Payment');
  run('toggleBrowserFile(2,true)');
  assert.equal(run('state.browserFile'), 1);
  assert.equal(run('[...state.selected].join()'), '0,2');
  assert.equal(element('.heading .primary').textContent, '审查已选文件 (2)');
  run('toggleBrowserFile(0,false)');
  assert.equal(run('state.browserFile'), 1);
  assert.equal(run('[...state.selected].join()'), '2');
});
check('Specialist form defaults and one note per selected file', () => {
  run('openForm()');
  assert.equal(run('form.smartSplit'), false);
  assert.equal(run('form.chosen.size'), 2);
  assert.match(element('#form-body').innerHTML, /OrderService.java 补充说明/);
  assert.match(element('#form-body').innerHTML, /PaymentGateway.java 补充说明/);
  assert.doesNotMatch(element('#form-body').innerHTML, /并发数/);
  run("updateFormType('PATTERN')");
  assert.equal(run('form.scope'), 'FILES');
  assert.doesNotMatch(element('#form-body').innerHTML, /name="scope"/);
});
check('Empty selection and source movement do not create task', () => {
  run('form.chosen.clear();submitReview()');
  assert.match(element('#form-error').textContent, /至少选择一个/);
  run('form.chosen.add(0);form.sourceMoved=true;submitReview()');
  assert.match(element('#form-error').innerHTML, /c8d40ab/);
  assert.equal(run('state.newTask'), false);
  const refresh = element('#form-error').innerHTML.match(/onclick="([^"]+)"/)[1];
  run(refresh);
  assert.equal(run('form.version'), 'c8d40ab');
  assert.equal(run('form.chosen.size'), 0);
  assert.match(element('#form-body').innerHTML, /c8d40ab/);
});
check('Scope dialog stages file changes and commits only on confirmation', () => {
  run("openForm();form.notes={0:'保留此说明',1:'待移除说明'};renderForm()");
  assert.match(element('#form-body').innerHTML, /openScopePicker/);
  assert.doesNotMatch(element('#form-body').innerHTML, /toggleFormFile/);
  run('openScopePicker();toggleScopeFile(1,false);toggleScopeFile(2,true)');
  assert.equal(element('#form-dialog').open, true);
  assert.equal(element('#scope-dialog').open, true);
  assert.equal(run('[...form.chosen].join()'), '0,1');
  run('closeScopePicker()');
  assert.equal(run('[...form.chosen].join()'), '0,1');
  assert.equal(run('form.notes[1]'), '待移除说明');
  run('openScopePicker();toggleScopeFile(1,false);toggleScopeFile(2,true);confirmScopePicker()');
  assert.equal(run('[...form.chosen].join()'), '0,2');
  assert.equal(run('form.notes[0]'), '保留此说明');
  assert.equal(run('form.notes[1]'), undefined);
  assert.equal(element('#scope-dialog').open, false);
  assert.equal(element('#form-dialog').open, true);
});
check('Shared tree previews content without changing checkbox or form draft', () => {
  run('openScopePicker()');
  assert.match(element('#scope-tree').innerHTML, /data-tree-context="scope"/);
  const draft = run('[...scopePicker.checked].join()');
  run('openScopePreview(1)');
  assert.equal(element('#reader-dialog').open, true);
  assert.equal(element('#scope-dialog').open, true);
  assert.match(element('#reader-body').innerHTML, /interface PaymentGateway/);
  assert.equal(run('[...scopePicker.checked].join()'), draft);
  assert.equal(run('[...form.chosen].join()'), '0,2');
  run('closeScopePicker()');
  assert.equal(element('#reader-dialog').open, false);
});
check('Diff scope limits changed files and offers changes plus before-after text', () => {
  run("setFormScope('DIFF');openScopePicker();scopePicker.status='added';renderScopeRows();selectScopeFiltered()");
  assert.equal(run('[...scopePicker.checked].join()'), '1');
  assert.doesNotMatch(element('#scope-tree').innerHTML, /OrderRepository.java/);
  run('toggleScopeFile(2,true);openScopePreview(1)');
  assert.equal(run('[...scopePicker.checked].join()'), '1');
  assert.match(element('#reader-body').innerHTML, /修改前原文/);
  assert.match(element('#reader-body').innerHTML, /修改后原文/);
  run("changeScopePreviewMode('before',scopePicker.session)");
  assert.match(element('#reader-body').innerHTML, /修改前不存在/);
  run("changeScopePreviewMode('after',scopePicker.session)");
  assert.match(element('#reader-body').innerHTML, /interface PaymentGateway/);
  run('openScopePreview(0)');
  assert.match(element('#reader-body').innerHTML, /retryPayment/);
  assert.equal(run("scopeDiffRows(sampleCode.slice(0,15).concat('}'),sampleCode)[0].kind"), '');
  run('closeScopePicker()');
});
check('Scope refresh errors and changed source cannot overwrite form range', () => {
  run("setFormScope('FILES');openScopePicker();toggleScopeFile(0,true);scopePicker.error=true;renderScopePicker();confirmScopePicker()");
  assert.equal(element('#scope-confirm').disabled, true);
  assert.equal(run('form.chosen.size'), 0);
  assert.equal(run('scopePicker.checked.has(0)'), true);
  run('scopePicker.error=false;scopePicker.truncated=true;renderScopePicker()');
  assert.match(element('#scope-body').innerHTML, /列表可能不完整/);
  run("form.version='new-sha';confirmScopePicker()");
  assert.equal(run('form.chosen.size'), 0);
  assert.match(element('#toast').textContent, /来源已变化/);
  run('closeScopePicker()');
});
check('Feedback conflict preserves draft; explicit merge permits save', () => {
  run("state.page='detail';state.type='ISSUE';state.target=0;state.scenario='normal';render();chooseAssessment('issue-2','不准确');editComment('issue-2','需核对事务');state.scenario='conflict';saveFeedback('issue-2')");
  assert.equal(run("state.drafts['issue-2'].comment"), '需核对事务');
  assert.equal(run("state.drafts['issue-2'].dirty"), true);
  assert.equal(run("state.saved['issue-2']"), undefined);
  run("resolveConflict('issue-2');saveFeedback('issue-2')");
  assert.equal(run("state.saved['issue-2'].assessment"), '不准确');
  assert.equal(run("state.drafts['issue-2'].dirty"), false);
});
check('Pattern and coupling feedback attach to complete result', () => {
  run("state.type='PATTERN';render()");
  assert.match(element('#app').innerHTML, /整个设计模式审查结果/);
  run("state.type='COUPLING';render()");
  assert.match(element('#app').innerHTML, /整个耦合度审查结果/);
  assert.match(element('#app').innerHTML, /仓库内已解析出向依赖/);
});
check('Capacity failure and missing source remain explicit', () => {
  run("state.type='ISSUE';state.target=2;state.scenario='capacity';state.tab='result';render()");
  assert.match(element('#app').innerHTML, /不能计为「未发现问题」/);
  run("state.scenario='missing-source';openCode('OrderService.java',16,17)");
  assert.match(element('#reader-body').innerHTML, /不能用最新分支代码替换/);
  assert.equal(element('#copy-reader').disabled, true);
  run('openCall(2)');
  assert.equal(element('#copy-reader').disabled, false);
});
check('Report candidates exclude failed file and require selection', () => {
  run("state.scenario='normal';openReportForm()");
  assert.match(element('#report-form-body').innerHTML, /disabled/);
  run('state.reportSelection.clear()');
  element('#report-title').value = '专项测试报告';
  run('submitReport()');
  assert.match(element('#report-form-error').textContent, /至少选择一个/);
});
check('Report freezes selected result types, versions, feedback and body', () => {
  run("state.reportSelection=new Set(['r4','r5']);submitReport()");
  const body = run('reportText()');
  assert.match(body, /策略接口角色/);
  assert.match(body, /a84cd92/);
  assert.doesNotMatch(body, /重试支付可能造成重复扣款/);
  const frozen = run("state.frozenFeedback['issue-1'].comment");
  run("state.saved['issue-1'].comment='后续更新'");
  assert.equal(run("state.frozenFeedback['issue-1'].comment"), frozen);
  assert.equal(run('reportText()'), body);
  run("openFrozenSource('r5')");
  assert.match(element('#reader-body').innerHTML, /a84cd92/);
  assert.match(element('#app').innerHTML, /正文与下载使用同一份保存内容/);
});
check('Text renders escaped and report failure offers no success download', () => {
  run("state.frozenTitle='<img src=x onerror=alert(1)>';state.reportFailed=false;render()");
  assert.match(element('#app').innerHTML, /&lt;img/);
  assert.doesNotMatch(element('#app').innerHTML, /<img src=x/);
  run("state.reportFailed=true;render()");
  assert.match(element('#app').innerHTML, /报告生成失败/);
  assert.doesNotMatch(element('#app').innerHTML, /下载 Markdown/);
});
check('All page and scenario renderers execute without exception', () => {
  for (const page of ['center','browser','detail','reports','report','settings','models','prompts','strategies']) {
    for (const scenario of ['normal','empty','load-error','running','capacity','conflict','no-denominator','missing-source','report-failed']) {
      run(`state.page='${page}';state.scenario='${scenario}';render()`);
    }
  }
  run("state.page='detail';state.scenario='running';cancelTask()");
  assert.match(element('#app').innerHTML, /任务已取消/);
});
check('New task keeps only selected targets and does not invent results', () => {
  run("openForm();form.chosen.clear();form.chosen.add(1);submitReview()");
  assert.equal(element('#submit-review').disabled, true);
  while (queue.length) queue.shift()();
  assert.equal(run('state.page'), 'detail');
  assert.equal(run('state.detailTargets.join()'), 'PaymentGateway.java');
  assert.match(element('#app').innerHTML, /0 \/ 1/);
  assert.match(element('#app').innerHTML, /尚无完整结果/);
  assert.doesNotMatch(element('#app').innerHTML, /data-feedback=/);
  run('cancelTask()');
  assert.match(element('#app').innerHTML, /当前文件没有完整结果/);
});
check('Management navigation and lists expose existing configuration duties', () => {
  for (const [page, title] of [['models','模型管理'],['prompts','提示词管理'],['strategies','策略管理']]) {
    assert.ok(html.includes(`data-nav="${page}"`));
    run(`state.page='${page}';render()`);
    assert.match(element('#app').innerHTML, new RegExp(title));
  }
  run("management.query.prompts='不存在的规则';state.page='prompts';render()");
  assert.match(element('#app').innerHTML, /暂无匹配的提示词/);
  run("management.query.prompts=''");
});
check('Prompt edit creates version and comparison preserves previous content', () => {
  const previous = run("currentPrompt('p1').content");
  run("openManage('prompts','p1')");
  element('#manage-name').value = '订单服务通用规则';
  element('#manage-content').value = previous + '\n新增审查关注点。';
  element('#manage-description').value = '演示编辑';
  element('#manage-tags').value = '订单';
  element('#manage-newVersion').checked = true;
  run('saveManage()');
  assert.equal(run("currentPrompt('p1').number"), 3);
  assert.equal(run("management.prompts[0].versions[1].content"), previous);
  run("openPromptVersions('p1')");
  element('#version-before').value = '2';
  element('#version-after').value = '3';
  run("comparePromptVersions('p1')");
  assert.match(element('#prompt-version-diff').innerHTML, /新增审查关注点/);
  assert.match(element('#prompt-version-diff').innerHTML, /v2/);
});
check('Strategy validates references and is available in specialist form', () => {
  run("openManage('strategies')");
  element('#manage-name').value = '严格订单规则';
  element('#manage-specialistType').value = 'ISSUE';
  element('#manage-modelId').value = 'invalid';
  element('#manage-promptId').value = 'p1';
  run('saveManage()');
  assert.match(element('#manage-error').textContent, /有效模型/);
  element('#manage-modelId').value = 'm2';
  run('saveManage()');
  assert.equal(run("management.strategies.at(-1).modelId"), 'm2');
  run('openForm()');
  assert.match(element('#form-strategy').innerHTML, /严格订单规则/);
  element('#form-strategy').value = '严格订单规则';
  element('#form-strategy').onchange();
  assert.equal(element('#form-model').value, '备用模型');
  assert.equal(element('#form-model').disabled, true);
  element('#form-strategy').value = '不选策略';
  element('#form-strategy').onchange();
  assert.equal(element('#form-model').disabled, false);
});
check('Model edit invalidates validation and never stores raw credential', () => {
  run("openManage('models','m1')");
  Object.entries({name:'默认模型',modelName:'review-model-v2',baseUrl:'https://ai.example.com/v1',contextWindow:'65536',maxOutput:'4096',token:'sample-only-token'})
    .forEach(([key,value])=>element('#manage-'+key).value=value);
  run('saveManage()');
  assert.equal(run('management.models[0].verifiedRevision'), 0);
  assert.equal(element('#manage-token').value, '');
  assert.ok(!run('JSON.stringify(management.models)').includes('sample-only-token'));
  run("verifyManagedModel('m1')");
  assert.equal(run('management.models[0].verifiedRevision'), run('management.models[0].revision'));
  const count = run('management.models.length');
  run("removeManaged('models','m1')");
  assert.equal(run('management.models.length'), count);
  assert.match(element('#toast').textContent, /正被策略引用/);
});
check('Project management lists projects, supports search and validates creation', () => {
  run("state.page='settings';render()");
  assert.match(element('#app').innerHTML, /<table>/);
  assert.match(element('#app').innerHTML, /订单服务/);
  assert.match(element('#app').innerHTML, /支付服务/);
  assert.match(element('#app').innerHTML, /新建项目/);
  run("management.query.projects='不匹配';render()");
  assert.match(element('#app').innerHTML, /暂无匹配的项目/);
  run("management.query.projects='';openManage('projects')");
  const count = run('management.projects.length');
  element('#manage-name').value = '库存服务';
  element('#manage-giteaUrl').value = 'invalid';
  run('saveManage()');
  assert.match(element('#manage-error').textContent, /有效的 HTTP/);
  element('#manage-giteaUrl').value = 'https://git.example.com/shop/stock';
  element('#manage-token').value = 'project-demo-token';
  run('manageEditor.verifyFailed=true;saveManage()');
  assert.equal(run('management.projects.length'), count);
  assert.equal(element('#manage-token').value, 'project-demo-token');
  run('manageEditor.verifyFailed=false;saveManage()');
  assert.equal(run('management.projects.length'), count + 1);
  assert.equal(run('management.projects.at(-1).hasToken'), true);
  assert.equal(element('#manage-token').value, '');
  assert.ok(!run('JSON.stringify(management.projects)').includes('project-demo-token'));
});
check('Project editing locks repository and keeps credential on blank input', () => {
  run("openManage('projects','project-order')");
  assert.match(element('#manage-body').innerHTML, /readonly/);
  assert.doesNotMatch(element('#manage-body').innerHTML, /project-demo-token/);
  element('#manage-name').value = '订单服务重命名';
  element('#manage-giteaUrl').value = 'https://git.example.com/shop/other';
  element('#manage-token').value = '';
  run('saveManage()');
  assert.match(element('#manage-error').textContent, /不能修改仓库地址/);
  assert.equal(run('management.projects[0].name'), '订单服务');
  element('#manage-giteaUrl').value = 'https://git.example.com/shop/order';
  run('saveManage()');
  assert.equal(run('management.projects[0].name'), '订单服务重命名');
  assert.equal(run('management.projects[0].hasToken'), true);
});
check('Project navigation clears file selection and isolates code and records', () => {
  run("state.selected.add(0);openProjectBrowser('project-payment')");
  assert.equal(run('state.selected.size'), 0);
  assert.match(element('#app').innerHTML, /此项目尚未提供演示代码/);
  assert.doesNotMatch(element('#app').innerHTML, /public class OrderService/);
  run("openProjectReviews('project-payment')");
  assert.match(element('#app').innerHTML, /修复重试逻辑的提交/);
  assert.doesNotMatch(element('#app').innerHTML, /订单提交与支付重试/);
  run("openProjectBrowser('project-order')");
  assert.match(element('#app').innerHTML, /订单服务重命名/);
  assert.match(element('#app').innerHTML, /public class OrderService/);
});
check('Report list opens four-result example with version chapters and citations', () => {
  run("state.page='reports';state.reportFailed=false;render()");
  assert.match(element('#app').innerHTML, /4 个审查结果 · 来自 3 个任务/);
  assert.match(element('#app').innerHTML, /查看多结果报告/);
  run('openReportExample()');
  assert.equal(run('reportSources().length'), 4);
  assert.equal(run('reportGroups().length'), 2);
  assert.ok(run("reportText().split(String.fromCharCode(10)).length") > 20);
  assert.match(element('#app').innerHTML, /主要结论/);
  assert.match(element('#app').innerHTML, /订单服务 · b7a312e/);
  assert.match(element('#app').innerHTML, /订单服务 · a84cd92/);
  assert.match(element('#app').innerHTML, /人工反馈快照/);
  assert.match(run("reportInline('[S4]')"), /openFrozenSource\('r5'\)/);
  assert.equal(run("reportInline('<script>bad</script> [S99]')"), '&lt;script&gt;bad&lt;/script&gt; [S99]');
});
check('Each report citation opens its frozen result, feedback and exact-version code', () => {
  run("openFrozenSource('r4')");
  assert.match(element('#reader-title').textContent, /S3/);
  assert.match(element('#reader-body').innerHTML, /Strategy/);
  const originalSha = run('reportSourceData.r5.sha');
  run("reportSourceData.r5.sha='new-version';openFrozenSource('r5')");
  assert.match(element('#reader-subtitle').textContent, /a84cd92/);
  assert.doesNotMatch(element('#reader-subtitle').textContent, /new-version/);
  run("openReportSourceCode('r5')");
  assert.match(element('#reader-body').innerHTML, /a84cd92/);
  assert.doesNotMatch(element('#reader-body').innerHTML, /retryPayment/);
  assert.ok(run("readerText.split(String.fromCharCode(10)).length") > 5);
  run(`reportSourceData.r5.sha=${JSON.stringify(originalSha)}`);
});
check('Multi-result example and individually generated report keep separate snapshots', () => {
  const generated = run('JSON.stringify(state.generatedReport)');
  run('openGeneratedReport()');
  assert.equal(run('reportSources().length'), 2);
  assert.doesNotMatch(run('reportText()'), /重试支付可能造成重复扣款/);
  const body = run('reportText()');
  run('openReportExample();openGeneratedReport()');
  assert.equal(run('reportText()'), body);
  assert.equal(run('JSON.stringify(state.generatedReport)'), generated);
});
check('Materials show target code and sent declaration slices with original lines', () => {
  run("openTask('t1');state.tab='materials';render()");
  assert.match(element('#app').innerHTML, /实际分析材料/);
  assert.match(element('#app').innerHTML, /class="muted small">b7a312e · 第 1–19 行/);
  assert.doesNotMatch(element('#app').innerHTML, /public class OrderService|retryPayment/);
  run("selectDetailMaterial('t1:OrderService.java:target')");
  assert.equal(element('#reader-dialog').open, true);
  assert.match(element('#reader-body').innerHTML, /public class OrderService/);
  assert.match(element('#reader-body').innerHTML, /retryPayment/);
  run("selectDetailMaterial('t1:OrderService.java:contract')");
  assert.match(element('#reader-body').innerHTML, /已保存的声明片段/);
  assert.match(element('#reader-body').innerHTML, /<span class="ln">8<\/span>/);
  assert.match(element('#reader-body').innerHTML, /void charge/);
  const request = run('materialCallText()');
  assert.ok(request.includes('void charge'));
  assert.ok(!request.includes('void save(Order order);'));
  run('openMaterialReader(true)');
  assert.match(element('#reader-body').innerHTML, /package com.shop.order/);
  assert.match(element('#reader-subtitle').textContent, /不能视为全文已发送/);
  assert.equal(run('materialCallText()'), request);
  run("selectDetailMaterial('t1:OrderService.java:unused')");
  assert.match(element('#app').innerHTML, /已读取，未发送/);
  assert.match(element('#reader-body').innerHTML, /void save/);
});
check('Commit materials default to diff and switch before-after exact versions', () => {
  run("openTask('t4');state.tab='materials';render()");
  assert.doesNotMatch(element('#app').innerHTML, /class="code-line added"|查看差异/);
  assert.match(element('#app').innerHTML, /修改前 12ac80f · 第 1–16 行/);
  run("selectDetailMaterial('t4:OrderService.java:target')");
  assert.equal(run('state.materialMode'), 'changes');
  assert.match(element('#reader-body').innerHTML, /12ac80f → 修改后 e91bd20/);
  assert.match(element('#reader-body').innerHTML, /提交差异/);
  assert.match(element('#reader-body').innerHTML, /class="code-line added"/);
  run("changeMaterialMode('before')");
  assert.match(element('#reader-body').innerHTML, /修改前 12ac80f/);
  assert.doesNotMatch(element('#reader-body').innerHTML, /order.getId/);
  run("changeMaterialMode('after')");
  assert.match(element('#reader-body').innerHTML, /修改后 e91bd20/);
  assert.match(element('#reader-body').innerHTML, /order.getId/);
  run("selectTarget(1);state.tab='materials';render();selectDetailMaterial('t4:PaymentGateway.java:target')");
  assert.match(element('#reader-body').innerHTML, /String requestId/);
  assert.equal(run('activeDetailMaterial().name'), 'PaymentGateway.java');
  assert.equal(run('detailMaterials().length'), 1);
});
check('Missing material body stays explicit and never falls back to current code', () => {
  run("state.scenario='missing-source';render()");
  run('openMaterialReader()');
  assert.match(element('#reader-body').innerHTML, /材料原文不可取得/);
  assert.equal(element('#copy-reader').disabled, true);
  assert.doesNotMatch(element('#reader-body').innerHTML, /interface PaymentGateway/);
  run("openTask('t3');state.tab='materials';render();selectDetailMaterial('t3:OrderService.java:contract')");
  assert.match(element('#reader-body').innerHTML, /该版本的材料原文未保存/);
  assert.doesNotMatch(element('#app').innerHTML, /b7a312e/);
});
check('Every generated inline event handler parses', () => {
  const decode = s => s.replace(/&quot;/g, '"').replace(/&#39;/g, "'")
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
  let count = 0;
  for (const template of [html.replace(/<script>[\s\S]*?<\/script>/, ''), ...templates]) {
    for (const match of template.matchAll(/\bon(?:click|change|input|keydown)="([^"]*)"/g)) {
      new vm.Script('(function(event){' + decode(match[1]) + '})');
      count++;
    }
  }
  assert.ok(count > 100);
  console.log('Inline handler instances parsed:', count);
});
console.log(`${passed} logical checks passed. No browser rendering, visual approval or live resources verified.`);
