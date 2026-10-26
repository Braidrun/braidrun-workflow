"""TEST ONLY: normal E HTTP Agent; S/P services simulated, no accounts.
Collection is driven only by ACKed formal ready events. Marker polling solely
controls the explicit pre-write test pause and is never collection authority.
"""
import argparse, hashlib, json, queue, subprocess, threading, time, uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

parser=argparse.ArgumentParser()
for name in ['fixture-jar','java','out','base-fixtures']:parser.add_argument('--'+name,required=True)
a=parser.parse_args();root=Path(a.out).resolve();root.mkdir(parents=True,exist_ok=False)
sha=lambda b:hashlib.sha256(b).hexdigest()
canonical=lambda v:json.dumps(v,ensure_ascii=False,separators=(',',':'),sort_keys=True)
now=lambda:datetime.now(timezone.utc).isoformat().replace('+00:00','Z')
def definition(name,logical,child=False):
 text=f'''name: {name}
agents:
  model:
    strategy: single_run
    llm: {{model: gpt-4o-mini, provider: openai}}
workflow:
  - step: write
    agent: model
    input: Return fixture commentary.
    structured_output:
      schema: ai_commentary_parts
      write_to: {root/logical}
'''
 if child:text+='  - step: nested\n    sub_workflow:\n      workflow_id: child\n'
 return text
sources={'root':definition('fixture-root','run/root.json',True),'child':definition('fixture-child','run/child.json')}
policy={'schemaVersion':1,'rootWorkflowId':'root','declarations':[{'declarationId':'decl-'+i,'producerWorkflowId':i,'producerStep':'write','childSteps':[] if i=='root' else ['nested'],'logicalPath':'run/'+i+'.json','kind':'MATERIAL','maximumBytes':100000,'sourcePolicy':'UNAVAILABLE','codeSourceSha256':None} for i in ['root','child']]}
files={i+'.yaml':('definition',v) for i,v in sources.items()};files['runtime/runner-artifact-policy.v1.json']=('runtime',canonical(policy))
for path,(_,value) in files.items():p=root/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(value)
claim=json.loads((Path(a.base_fixtures)/'claim.json').read_text());binding=claim['binding'];binding['definitionHash']=sha(sources['root'].encode());binding['executionId']='http-ready-fixture-'+uuid.uuid4().hex
meta={'rootWorkflowId':'root','files':[{'path':p,'role':v[0],'size':len(v[1].encode()),'sha256':sha(v[1].encode())} for p,v in sorted(files.items())],'definitions':[{'workflowId':i,'path':i+'.yaml','definitionHash':sha(v.encode())} for i,v in sorted(sources.items())]}
bundle={**meta,'bundleHash':sha(canonical(meta).encode())};(root/'binding.json').write_text(canonical(binding));pause=root/'.runner-state/fixture-control';pause.mkdir(parents=True)
checked=datetime.now(timezone.utc)
env=json.loads((Path(a.base_fixtures)/'environment-zh-allow.json').read_text());env.update(binding=binding,checkedAt=checked.isoformat().replace('+00:00','Z'),validUntil=(checked+timedelta(seconds=60)).isoformat().replace('+00:00','Z'))
for item in env['egress']:item.update(pathId='jvm-http',observedAt=env['checkedAt'])
authorization=claim['authorization'];authorization['requiredCapabilities']=['workflow.full.v1','step.agent.v1','model.http.v1','step.subWorkflow.v1','artifact.collection.v1']
lease={'leaseId':'fixture-lease','serverTime':now(),'leaseExpiresAt':env['validUntil'],'renewAfterSeconds':15,'leaseDurationSeconds':60}
messages=queue.Queue();trace=[];starts={};ready={};indexed={};complete={};paused=set();result=None
with (root/'engine.stderr.log').open('w') as stderr:
 proc=subprocess.Popen([a.java,'-jar',str(Path(a.fixture_jar).resolve()),'--binding',str(root/'binding.json'),'--pause-dir',str(pause)],cwd=root,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=stderr,text=True)
 def read():
  for line in proc.stdout:messages.put(line)
  messages.put(None)
 threading.Thread(target=read,daemon=True).start()
 def send(kind,payload,reply=None):
  f={'protocolVersion':1,'frameId':str(uuid.uuid4()),'type':kind,'binding':binding,'payload':payload}
  if reply:f['replyTo']=reply
  proc.stdin.write(canonical(f)+'\n');proc.stdin.flush()
 deadline=time.monotonic()+40
 try:
  while time.monotonic()<deadline:
   for marker in pause.glob('*.waiting'):
    if marker.name in paused:continue
    logical=marker.read_text();assert logical in ['run/root.json','run/child.json']
    assert not (root/logical).exists(), 'file existed before normal write pause'
    producer=Path(logical).stem;assert producer not in ready, 'ready before actual write'
    paused.add(marker.name);marker.with_suffix('.release').write_text('test-driver-release')
   try:line=messages.get(timeout=.05)
   except queue.Empty:continue
   if line is None:break
   frame=json.loads(line);trace.append(frame);kind=frame['type'];body=frame['payload']
   if kind=='ready':
    assert 'artifact.collection.v1' in body['capabilities']
    send('start',{'executionDirectory':str(root),'bundle':bundle,'authorization':authorization,'lease':lease,'environment':env,'inputs':{}})
   elif kind=='event':
    detail=body['payload']
    if detail.get('kind')=='step_started' and detail.get('stepName')=='write':starts[detail['executionId']]=body['eventId']
    if detail.get('kind')=='artifact_collection_ready':
     producer=detail['producerWorkflowId'];assert producer in ['root','child']
     assert detail['producerStartEventId']==starts[detail['executionId']]
     assert detail['declarationIds']==['decl-'+producer]
     assert detail['policySha256']==sha(canonical(policy).encode())
     assert frame==json.loads((root/f'.runner-state/events/{body["eventSeq"]}.json').read_text())
     send('ack',{'ackSeq':body['eventSeq'],'revision':1,'serverTime':now()},frame['frameId']);ready[producer]=frame
     # Controlled S reads only after the ACKed formal notification.
     raw=(root/f'run/{producer}.json').read_bytes();assert json.loads(raw);(root/f'fixture-spool-{producer}').write_bytes(raw)
     send('artifactCollectRequest',{'requestId':'request-'+producer,'policySha256':detail['policySha256'],'declarationId':'decl-'+producer,'publicationId':'publication-'+producer,'artifactId':'artifact-'+producer,'logicalPath':'run/'+producer+'.json','size':len(raw),'sha256':sha(raw),'childExecutionPath':detail['childExecutionPath'],'producerWorkflowId':producer,'producerStep':'write','producerStartEventId':detail['producerStartEventId'],'codeSourceSha256':None})
    elif body['type']=='artifact':
     producer=detail['producerWorkflowId'];assert producer in ready
     raw=(root/f'fixture-spool-{producer}').read_bytes();assert detail['sha256']==sha(raw) and detail['size']==len(raw);indexed[producer]=body
     send('ack',{'ackSeq':body['eventSeq'],'revision':2,'serverTime':now()},frame['frameId'])
    else:send('ack',{'ackSeq':body['eventSeq'],'revision':1,'serverTime':now()},frame['frameId'])
   elif kind=='artifactCollectComplete':
    producer=body['artifactId'].removeprefix('artifact-');assert producer in indexed
    assert body['eventId']==indexed[producer]['eventId'];complete[producer]=frame
   elif kind=='checkpoint':pass
   elif kind=='result':result=body;break
   else:raise AssertionError('unexpected frame '+kind)
  assert result and result['status']=='COMPLETED',result
  assert set(ready)==set(indexed)==set(complete)=={'root','child'} and len(paused)==2
  proc.wait(timeout=5);assert proc.returncode==0
 finally:
  if proc.poll() is None:proc.kill();proc.wait()
  (root/'trace.json').write_text(json.dumps(trace,ensure_ascii=False,indent=2))
(root/'trace.json').write_text(json.dumps(trace,ensure_ascii=False,indent=2))
receipt={'passed':True,'actual':'standalone test-only JAR/JVM, normal interpreter/host, controlled HTTP model, real root+child JSON writes with pre-write pause and formal IPC','simulated':'S snapshot/chunk ACK/P index/control transport; not S kernel/P HTTP/Mongo acceptance','pausedBeforeWrite':len(paused),'ready':len(ready),'indexed':len(indexed),'complete':len(complete),'fixtureJarSha256':sha(Path(a.fixture_jar).read_bytes())}
(root/'receipt.json').write_text(json.dumps(receipt,indent=2));print(json.dumps(receipt))
