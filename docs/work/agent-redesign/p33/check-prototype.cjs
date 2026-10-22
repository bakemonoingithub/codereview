// Browser checks for the standalone prototype; not production Vue or backend tests.
const fs=require('node:fs');
const path=require('node:path');
const assert=require('node:assert/strict');
const {pathToFileURL}=require('node:url');
const {createRequire}=require('node:module');
const req=createRequire(path.join(process.env.P33_NODE_MODULES||'C:/Users/ysx/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules','package.json'));
const {chromium}=req('playwright');
const root=path.resolve(__dirname,'../../../..');
const file=path.join(root,'docs/work/agent-redesign/p29/prototype/index-agent.html');
const output=path.join(__dirname,'evidence');fs.mkdirSync(output,{recursive:true});
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.P33_BROWSER||'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
 const errors=[],checks=[];
 const page=await browser.newPage({viewport:{width:1366,height:900},deviceScaleFactor:1});
 page.on('pageerror',e=>errors.push(e.message));page.on('dialog',d=>d.accept());
 const check=async(name,fn)=>{await fn();checks.push(name);};
 try{
  await page.goto(pathToFileURL(file).href+'?agent=a1');await page.waitForTimeout(100);
  await check('existing Agent result layout opens without runtime errors',async()=>{assert.equal(errors.length,0);assert.ok(await page.getByRole('heading',{name:'支付超时后的重试链路',exact:true}).count());});
  await page.screenshot({path:path.join(output,'result-1366.png'),fullPage:true});
  await check('explicit result submission is a native tool request',async()=>{assert.equal(await page.evaluate(()=>agentTasks.a1.calls.at(-1).response.tool_calls[0].function.name),'submit_result');assert.equal(await page.evaluate(()=>agentTasks.a1.calls.at(-1).request.tools.some(t=>t.function.name==='submit_result')),true);});
  await page.evaluate(()=>openAgentCode(0,'b7a312e',16,17));await page.waitForTimeout(100);
  await check('same-result references group by file and fixed version',async()=>{assert.equal(await page.locator('#p33-ref-count').textContent(),'2 / 2');assert.equal(await page.locator('#p33-next').isDisabled(),true);assert.ok(await page.locator('#p33-line-16.current').count());});
  await check('reference starts at top and all current-result ranges highlight',async()=>{assert.equal(await page.locator('.p33-code-line.marked').count(),6);const dy=await page.evaluate(()=>{const a=document.getElementById('p33-line-16').getBoundingClientRect();const b=document.getElementById('p33-code-scroll').getBoundingClientRect();return Math.abs(a.top-b.top);});assert.ok(dy<3);});
  await page.screenshot({path:path.join(output,'code-references-1366.png')});
  await page.locator('#p33-prev').click();await check('previous reference changes current position and stops at first',async()=>{assert.equal(await page.locator('#p33-ref-count').textContent(),'1 / 2');assert.equal(await page.locator('#p33-prev').isDisabled(),true);});
  await page.keyboard.press('Alt+ArrowRight');await check('keyboard navigation preserves reference group',async()=>assert.equal(await page.locator('#p33-ref-count').textContent(),'2 / 2'));
  await page.getByRole('button',{name:'保存片段',exact:true}).click();await check('saved material retains original line numbers',async()=>assert.ok(await page.locator('#p33-line-16').count()));
  await page.evaluate(()=>{p33Code.error=true;p33CodeRender();});await check('full-version failure retains references and supports retry',async()=>{assert.ok(await page.getByText('固定版本全文读取失败，当前引用与位置已保留。').count());await page.getByRole('button',{name:'重试',exact:true}).click();assert.ok(await page.locator('#p33-line-16').count());});
  await page.keyboard.press('Escape');await check('Escape closes evidence dialog',async()=>assert.equal(await page.locator('#p33-code-dialog').evaluate(e=>e.open),false));
  await page.evaluate(()=>openAgentTask('a5'));await check('submitted insufficient-evidence result remains readable and selectable',async()=>{assert.ok(await page.getByText('材料不足说明已固定保存。尚不能回答的部分将随来源冻结；可另选结果生成报告。').count());assert.equal(await page.evaluate(()=>candidates.find(c=>c.id==='result-a5').ok),true);assert.equal(await page.evaluate(()=>reportSourceData['result-a5'].answerStatus),'INSUFFICIENT_EVIDENCE');assert.equal(await page.evaluate(()=>agentTasks.a5.calls.at(-1).response.tool_calls[0].function.name),'submit_result');});
  await page.screenshot({path:path.join(output,'insufficient-1366.png'),fullPage:true});
  await check('mixed report freeze preserves insufficiency and old snapshots',async()=>{const frozen=await page.evaluate(()=>{const a=freezeReportSources(['r1','result-a5'],state.saved);const before=a[1].unresolved[0];reportSourceData['result-a5'].unresolved[0]='后续变化不应影响冻结';const unchanged=a[1].unresolved[0]===before;publishAgentCandidate(agentTasks.a5);return {count:a.length,status:a[1].answerStatus,unchanged};});assert.deepEqual(frozen,{count:2,status:'INSUFFICIENT_EVIDENCE',unchanged:true});});
  await page.evaluate(()=>{openAgentTask('a2');openAgentCode(0,'d82e610',10,13);});
  await check('different versions never share a reference group',async()=>{assert.equal(await page.evaluate(()=>p33Code.refs.every(e=>e.sha==='d82e610')),true);assert.equal(await page.evaluate(()=>p33Code.refs.some(e=>e.sha==='b7a312e')),false);});
  await page.keyboard.press('Escape');
  await page.evaluate(()=>openAgentTask('a4'));await check('limit termination has no complete report candidate',async()=>{assert.equal(await page.evaluate(()=>candidates.find(c=>c.id==='result-a4').ok),false);assert.equal(await page.getByRole('button',{name:'选结果生成报告',exact:true}).count(),0);});
  await page.screenshot({path:path.join(output,'limit-1366.png'),fullPage:true});
  await page.evaluate(()=>openAgentTask('a2'));await check('non-contiguous commits keep individual comparison inputs',async()=>{const pairs=await page.evaluate(()=>agentTasks.a2.calls.filter(c=>c.tool?.name==='read_diff').map(c=>[c.tool.args.parent,c.tool.args.commit]));assert.deepEqual(pairs,[['12ac80f','b7a312e'],['a65d901','d82e610']]);});
  await page.evaluate(()=>{state.agentTab='calls';render();});await page.screenshot({path:path.join(output,'calls-1366.png'),fullPage:true});
  await check('call reader uses preserved request and native final response',async()=>{await page.evaluate(()=>{state.agentCall=activeAgent().calls.length-1;state.agentCallTab='response';render();});assert.ok(await page.locator('.agent-call-json').textContent().then(s=>s.includes('submit_result')));});
  await page.evaluate(()=>openAgentForm());await check('blank required instructions block creation',async()=>{await page.locator('#submit-review').click();assert.equal(await page.locator('#form-error').textContent(),'请填写审查要求。');});
  await page.evaluate(()=>fillAgentExample('COMMITS'));await check('multi-commit form excludes selected file targets',async()=>{assert.equal(await page.evaluate(()=>agentForm.kind),'COMMITS');assert.equal(await page.evaluate(()=>agentForm.chosen.size),0);assert.equal(await page.evaluate(()=>agentForm.commits.size),2);});
  await page.evaluate(()=>fillAgentExample('FILES'));await page.screenshot({path:path.join(output,'launch-1366.png')});
  await page.locator('#submit-review').click();await check('new task uses current model and prompt snapshots',async()=>{assert.equal(await page.evaluate(()=>activeAgent().status),'running');assert.equal(await page.evaluate(()=>activeAgent().visible),0);assert.ok(await page.evaluate(()=>!!activeAgent().input.promptSnapshot?.content));});
  await page.evaluate(()=>cancelAgentTask());await check('cancel rejects subsequent demo advancement',async()=>{const before=await page.evaluate(()=>activeAgent().visible);await page.evaluate(()=>advanceAgentDemo());assert.equal(await page.evaluate(()=>activeAgent().status),'cancelled');assert.equal(await page.evaluate(()=>activeAgent().visible),before);});
  await page.evaluate(()=>{openAgentTask('a1');state.agentTab='input';render();});await check('historical input preserves guidance snapshot',async()=>{assert.ok(await page.getByText('支付链路调查 · v1',{exact:false}).count());});
  for(const width of [1366,1920]){await page.setViewportSize({width,height:900});await page.evaluate(()=>openAgentTask('a1'));await check('no horizontal page overflow at '+width,async()=>assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false));if(width===1920)await page.screenshot({path:path.join(output,'result-1920.png'),fullPage:true});}
  await check('no browser JavaScript errors',async()=>assert.deepEqual(errors,[]));
  fs.writeFileSync(path.join(output,'prototype-checks.json'),JSON.stringify({scope:'Standalone HTML only; not production or owner approval',browser:await browser.version(),checks:checks.map(name=>({name,passed:true})),errors},null,2)+'\n');
  console.log('PASS '+checks.length+' standalone prototype checks; screenshots saved.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e.stack);process.exit(1);});
