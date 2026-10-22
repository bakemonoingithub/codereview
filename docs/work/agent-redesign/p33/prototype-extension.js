/* P33.1 protocol/state additions to the existing Agent prototype. No backend requests. */
'use strict';
const p33OriginalBuildCalls = buildAgentCalls;
const p33SubmitSchema = {
  type:'function', function:{name:'submit_result',description:'提交带证据与边界的固定审查结果并结束任务。不得与其他工具同时调用。',
    parameters:{type:'object',properties:{title:{type:'string'},summary:{type:'string'},answerStatus:{type:'string',enum:['ANSWERED','INSUFFICIENT_EVIDENCE']},markdown:{type:'string'},citations:{type:'array',items:{type:'object',properties:{id:{type:'string'},materialId:{type:'string'},startLine:{type:'integer'},endLine:{type:'integer'}},required:['id','materialId','startLine','endLine'],additionalProperties:false}},unresolved:{type:'array',items:{type:'string'}}},required:['title','summary','answerStatus','markdown','citations','unresolved'],additionalProperties:false}}
};
agentTools.push(p33SubmitSchema);
function p33Submission(taskResult, insufficient=false){
  return {title:taskResult.title,summary:taskResult.summary,answerStatus:insufficient?'INSUFFICIENT_EVIDENCE':'ANSWERED',markdown:taskResult.markdown,
    citations:(taskResult.evidence||[]).map(e=>({id:e.id,materialId:'demo-material-'+e.id,startLine:e.start,endLine:e.end})),unresolved:taskResult.limitations||[]};
}
function p33ConvertCalls(calls, input){
  for(const c of calls){
    c.request.tools=agentClone(agentTools); c.request.tool_choice='auto';
    if(c.tool?.result?.path){c.tool.result.materials=(calls.at(-1)?.result?.evidence||[]).filter(e=>paths[e.index]===c.tool.result.path&&e.sha===(c.tool.result.version||input.version)).map(e=>({id:'demo-material-'+e.id,sha:e.sha,path:paths[e.index],startLine:e.start,endLine:e.end}));}
    if(c.result){const insufficient=!agentSupportsInput(input);if(insufficient&&!c.result.markdown)c.result.markdown='## 材料不足\n\n当前范围尚无完整演示材料，不能据其他范围的代码回答本次问题。\n\n## 未解决事项\n\n需要取得本次范围的实际实现。';c.response={role:'assistant',content:null,tool_calls:[{id:'submit-'+c.number,type:'function',function:{name:'submit_result',arguments:JSON.stringify(p33Submission(c.result,insufficient))}}]};c.label=insufficient?'提交材料不足说明':'显式提交审查结果';}
  }
  return calls;
}
buildAgentCalls=function(input){return p33ConvertCalls(p33OriginalBuildCalls(input),input);};
Object.values(agentTasks).forEach(t=>{p33ConvertCalls(t.calls,t.input);t.published=t.status==='completed';});
// A normally submitted insufficiency result differs from a run interrupted by a hard limit.
const insufficient=agentTasks.a5;
insufficient.result={title:'第三方支付幂等能力尚无法确认',summary:'已核对调用方与接口，未取得支付实现，不能确认实际去重能力。',markdown:'## 现有依据\n\n调用方的重试入口再次发起支付请求。[E1] [E2]\n\n## 尚不能回答\n\n当前任务未取得第三方支付实现，不能确认重复请求是否会重复扣款。调用方和接口不足以证明实际幂等行为。\n\n## 后续所需材料\n\n需要支付实现及其请求去重契约。',evidence:agentEvidenceFor(insufficient.input).slice(0,2),limitations:['未取得第三方支付实现，无法确认实际扣款是否去重。']};
insufficient.evidence=insufficient.evidence.slice(0,2);
const finalRequest=agentClone(insufficient.calls.at(-1).request),prior=insufficient.calls.at(-1);
finalRequest.messages.push(agentClone(prior.response),{role:'tool',tool_call_id:prior.response.tool_calls[0].id,content:JSON.stringify(prior.tool.result)});
insufficient.calls.push({number:insufficient.calls.length+1,label:'显式提交材料不足说明',request:finalRequest,response:{role:'assistant',content:null,tool_calls:[{id:'submit-insufficient',type:'function',function:{name:'submit_result',arguments:JSON.stringify(p33Submission(insufficient.result,true))}}]},elapsed:4,usage:640,result:insufficient.result});
insufficient.visible=insufficient.calls.length; insufficient.published=true;
function p33HasResult(task){return task.status==='completed'||task.status==='insufficient'&&task.published===true;}
const p33OriginalResult=renderAgentResult, p33OriginalDetail=renderAgentDetail, p33OriginalPublish=publishAgentCandidate;
renderAgentResult=function(task){
  if(!p33HasResult(task))return p33OriginalResult(task);
  if(task.status==='completed')return p33OriginalResult(task);
  return '<div class="notice amber"><strong>材料不足说明已完整提交</strong><br>这是已结束的调查记录；尚不能回答的部分会随结果冻结到报告，不表示所有问题已得到回答。</div>'+p33OriginalResult({...task,status:'completed'});
};
renderAgentDetail=function(){
  const task=activeAgent();task.limits={calls:96,time:1200,quota:82103424,capacity:80,...task.limits};let html;
  if(task.status==='insufficient'&&task.published){task.status='completed';try{html=p33OriginalDetail();}finally{task.status='insufficient';}html=html.replace(badge('已完成','green'),badge('材料不足','amber')).replace('审查结果已固定保存。进一步分析可引用此结果新建任务；生成报告需另行选材。','材料不足说明已固定保存。尚不能回答的部分将随来源冻结；可另选结果生成报告。');}
  else html=p33OriginalDetail();
  html=html.replaceAll('累计额度','累计用量').replace('预算与 80% 阈值为讨论用演示参数，具体计量与数值待 P33 设计确认。','本页计数为固定演示；首版默认 48 轮、96 次实际请求、192 次工具、20 分钟，上下文阈值 80%。累计用量是资源计量，不代表费用。');
  return html;
};
publishAgentCandidate=function(task){
  const previous=task.status;if(p33HasResult(task))task.status='completed';try{p33OriginalPublish(task);}finally{task.status=previous;}
  const source=reportSourceData['result-'+task.id],candidate=candidates.find(c=>c.id==='result-'+task.id);
  if(p33HasResult(task)&&source){source.answerStatus=previous==='insufficient'?'INSUFFICIENT_EVIDENCE':'ANSWERED';source.unresolved=agentClone(task.result.limitations);if(previous==='insufficient'){source.summary='材料不足 · '+task.result.summary;if(candidate)candidate.desc='已完整提交材料不足说明 · 未解决事项随来源冻结';}}
};
const p33OriginalAdvance=advanceAgentDemo;
advanceAgentDemo=function(){const task=activeAgent();p33OriginalAdvance();if(task.status==='completed'||task.status==='insufficient'){task.published=task.visible===task.calls.length;publishAgentCandidate(task);render();}};
// Existing prototype starts with a plain-text completion; future tasks use the normalized builder above.
const p33Style=document.createElement('style');
p33Style.textContent=`
#p33-code-dialog{width:min(1160px,calc(100vw - 40px));max-height:92vh;border:1px solid var(--line);border-radius:24px;padding:0;color:var(--ink);box-shadow:0 24px 70px #0f172a33}
#p33-code-dialog::backdrop{background:#0f172a66}#p33-code-dialog header{padding:20px 24px;border-bottom:1px solid var(--line)}
.p33-toolbar{display:flex;gap:10px;align-items:center;flex-wrap:wrap;padding:14px 24px;border-bottom:1px solid var(--line)}
#p33-code-scroll{max-height:56vh;min-height:210px;overflow:auto;background:#fcfdfe}#p33-code-scroll::after{content:'';display:block;height:calc(56vh - 28px)}.p33-code-line{display:flex;min-height:28px;line-height:28px;font:12px/28px Consolas,monospace;white-space:pre;border-left:3px solid transparent}
.p33-code-line .ln{width:58px;flex:none;text-align:right;padding-right:14px;color:var(--muted);user-select:none}.p33-code-line code{background:none;border:0;padding:0;white-space:pre;min-width:max-content}
.p33-code-line.marked{background:#eff4ff;border-left-color:#93b4f5}.p33-code-line.current{background:#dceaff;border-left-color:var(--blue)}.p33-code-line.current .ln{color:var(--blue);font-weight:bold}
.p33-code-note{padding:12px 24px;color:var(--muted);font-size:12px;border-top:1px solid var(--line)}
`;
document.head.append(p33Style);
const p33Dialog=document.createElement('dialog');p33Dialog.id='p33-code-dialog';p33Dialog.setAttribute('aria-label','Agent 固定版本代码证据');document.body.append(p33Dialog);
let p33Code={};
function p33CodeRender(){
  const s=p33Code,e=s.refs[s.current],all=s.lines;
  p33Dialog.innerHTML=`<header><div class="row wrap"><h2>${esc(files[s.index])}</h2><span class="badge">${s.withContext?'代码证据':'材料阅读'}</span><span class="spacer"></span><button aria-label="关闭代码证据" onclick="document.getElementById('p33-code-dialog').close()">关闭</button></div><p class="small muted">${esc(paths[s.index])} · ${esc(s.sha)} · ${esc(s.side)}${e?' · 定位 第 '+e.start+'–'+e.end+' 行':''}</p></header><div class="p33-toolbar"><button class="${s.mode==='full'?'primary':''}" onclick="p33Code.mode='full';p33CodeRender()">固定版本全文</button><button class="${s.mode==='saved'?'primary':''}" onclick="p33Code.mode='saved';p33CodeRender()">保存片段</button><span class="muted small">${s.mode==='full'?'扩展阅读，不改变实际发送内容':'本次返回给模型的保存范围'}</span><span class="spacer"></span>${s.withContext?`<button id="p33-prev" ${s.current===0?'disabled':''} onclick="p33CodeMove(-1)">上一处</button><span id="p33-ref-count">${s.current+1} / ${s.refs.length}</span><button id="p33-next" ${s.current===s.refs.length-1?'disabled':''} onclick="p33CodeMove(1)">下一处</button>`:''}</div><div id="p33-code-scroll">${s.error?'<div class="pad"><p>固定版本全文读取失败，当前引用与位置已保留。</p><button onclick="p33Code.error=false;p33CodeRender()">重试</button><button onclick="p33Code.mode=\'saved\';p33Code.error=false;p33CodeRender()">返回保存片段</button></div>':!all?'<div class="pad">对应版本原文缺失，不能使用其他版本替代。</div>':all.map((line,i)=>{const n=i+1;if(s.mode==='saved'&&!s.savedRanges.some(r=>n>=r.start&&n<=r.end))return '';const marked=s.withContext&&s.refs.some(r=>n>=r.start&&n<=r.end),current=marked&&n>=e.start&&n<=e.end;return `<div id="p33-line-${n}" data-line="${n}" class="p33-code-line ${marked?'marked':''} ${current?'current':''}"><span class="ln">${n}</span><code>${esc(line)}</code></div>`;}).join('')}</div><div class="p33-code-note">${s.withContext?'仅高亮当前审查结果中此路径、版本与前后侧的引用；上一处／下一处不跨文件。':'材料阅读不带结论引用，不高亮。'} Alt + ← / → 切换；Esc 关闭。<span class="small"> 本页是固定演示数据。</span></div>`;
  requestAnimationFrame(()=>{const scroll=document.getElementById('p33-code-scroll'),line=document.getElementById('p33-line-'+e?.start);if(scroll&&line)scroll.scrollTop=line.offsetTop-scroll.firstElementChild.offsetTop;});
}
function p33CodeMove(delta){p33Code.current=Math.max(0,Math.min(p33Code.refs.length-1,p33Code.current+delta));const e=p33Code.refs[p33Code.current];if(p33Code.mode==='saved'&&!p33Code.savedRanges.some(r=>e.start>=r.start&&e.end<=r.end))p33Code.mode='full';p33CodeRender();}
openAgentCode=function(index,sha,start=1,end=null){
  const task=agentTasks[state.detailTaskId]||activeAgent();
  // The prototype has FILE and AFTER examples; side remains part of the grouping key.
  const side=task.input.kind==='COMMITS'?'AFTER':'FILE';
  let refs=task.evidence.filter(e=>e.index===index&&e.sha===sha&&(e.side||side)===side).map(e=>({...e,side}));
  refs=refs.filter((e,i,a)=>a.findIndex(x=>x.start===e.start&&x.end===e.end)===i).sort((a,b)=>a.start-b.start);
  const withContext=refs.length>0;if(!refs.length)refs=[{start,end:end||start,side}];
  const found=refs.findIndex(e=>e.start===start&&e.end===(end||start));
  const read=task.calls.slice(0,task.visible).find(c=>c.tool?.name==='read_code'&&c.tool.result.path===paths[index]&&c.tool.result.version===sha)?.tool.result;
  const lines=task.evidence.find(e=>e.index===index&&e.sha===sha)?.code||agentCodeAt(sha,index);
  const savedRanges=read?[{start:read.start,end:read.end}]:refs.map(e=>({start:e.start,end:e.end}));
  p33Code={index,sha,side,refs,current:found<0?0:found,withContext,mode:'full',lines,savedRanges,error:false};
  p33CodeRender();if(!p33Dialog.open)p33Dialog.showModal();
};
p33Dialog.addEventListener('click',e=>{if(e.target===p33Dialog){const r=p33Dialog.getBoundingClientRect();if(e.clientX<r.left||e.clientX>r.right||e.clientY<r.top||e.clientY>r.bottom)p33Dialog.close();}});
document.addEventListener('keydown',e=>{if(p33Dialog.open&&e.altKey&&['ArrowLeft','ArrowRight'].includes(e.key)&&p33Code.withContext){e.preventDefault();e.stopPropagation();p33CodeMove(e.key==='ArrowLeft'?-1:1);}},true);
Object.values(agentTasks).forEach(publishAgentCandidate);
// The shared report example was frozen before this extension; rebuild only its demo snapshot.
if(agentMixedReport){const prior=captureReport();Object.assign(state,agentClone(agentMixedReport));state.frozenSourceSnapshots=freezeReportSources(state.frozenSources,state.frozenFeedback);state.reportBody=buildReport();agentMixedReport=captureReport();Object.assign(state,prior);}
const initial=new URLSearchParams(location.search).get('agent');
if(initial==='form')openAgentForm();else if(initial&&agentTasks[initial])openAgentTask(initial);else render();
