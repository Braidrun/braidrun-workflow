"""Fixed production E/JVM + actual native Bash; S process/root/P-index services are controlled.
No business-file polling: snapshot metadata is synthesized only after separately ACKed ready.
"""
import argparse, hashlib, json, os, queue, signal, subprocess, threading, time, uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

p=argparse.ArgumentParser()
for name in ['runner-jar','java','out','base-fixtures']:p.add_argument('--'+name,required=True)
a=p.parse_args();root=Path(a.out).resolve();root.mkdir(parents=True,exist_ok=False)
sha=lambda raw:hashlib.sha256(raw).hexdigest()
canonical=lambda v:json.dumps(v,ensure_ascii=False,separators=(',',':'),sort_keys=True)
now=lambda:datetime.now(timezone.utc).isoformat().replace('+00:00','Z')
def definition(id):
 s=f'''name: set-{id}
agents: {{}}
workflow:
  - step: write
    code:
      language: bash
      timeout: 20
      working_dir: {root}
      script: |
        mkdir -p '{root}/run'
        printf 'once\\n' >> '{root}/run/{id}.txt'
        exit {4 if id=='child' else 0}
'''
 if id=='root':s+='  - step: nested\n    sub_workflow:\n      workflow_id: child\n'
 return s
sources={id:definition(id) for id in ['root','child']}
declarations=[]
for id in ['root','child']:
 for n in range(70 if id=='root' else 1):
  declarations.append({'declarationId':f'{id}-{n}','producer':{'workflowId':id,'stepName':'write','childSteps':['nested'] if id=='child' else []},'selector':{'kind':'FILE','path':f'run/{id}.txt' if n==0 else f'run/optional-{id}-{n}.txt'},'presence':'REQUIRED' if n==0 else 'OPTIONAL','maximumFiles':1,'maximumFileBytes':1024,'maximumTotalBytes':1024,'purpose':'DIAGNOSTIC' if id=='child' else 'GATE_MATERIAL'})
policy={'schemaVersion':1,'rootWorkflowId':'root','declarations':declarations}
files={id+'.yaml':('definition',s) for id,s in sources.items()};files['runtime/runner-artifact-sets.v1.json']=('runtime',canonical(policy))
for name,(_,data) in files.items():q=root/name;q.parent.mkdir(parents=True,exist_ok=True);q.write_text(data)
claim=json.loads((Path(a.base_fixtures)/'claim.json').read_text());binding=claim['binding'];binding['definitionHash']=sha(sources['root'].encode());binding['executionId']='set-smoke-'+uuid.uuid4().hex
meta={'rootWorkflowId':'root','files':[{'path':name,'role':v[0],'size':len(v[1].encode()),'sha256':sha(v[1].encode())} for name,v in sorted(files.items())],'definitions':[{'workflowId':id,'path':id+'.yaml','definitionHash':sha(s.encode())} for id,s in sorted(sources.items())]}
bundle={**meta,'bundleHash':sha(canonical(meta).encode())};(root/'binding.json').write_text(canonical(binding))
checked=datetime.now(timezone.utc);env=json.loads((Path(a.base_fixtures)/'environment-zh-allow.json').read_text());env.update(binding=binding,checkedAt=checked.isoformat().replace('+00:00','Z'),validUntil=(checked+timedelta(seconds=60)).isoformat().replace('+00:00','Z'))
for e in env['egress']:e.update(pathId='subprocess',observedAt=env['checkedAt'])
lease={'leaseId':'fixture-lease','serverTime':env['checkedAt'],'leaseExpiresAt':env['validUntil'],'renewAfterSeconds':15,'leaseDurationSeconds':60}
auth=claim['authorization'];auth['requiredCapabilities']=['workflow.full.v1','step.script.v1','step.subWorkflow.v1','artifact.collection.v1','artifact.set.v1']
q=queue.Queue();trace=[];controls=[];children=[];native=[];codeStarts={};ready={};commits={};completes={};closed=[];result=None
with (root/'engine.stderr.log').open('w') as stderr:
 proc=subprocess.Popen([a.java,'-jar',str(Path(a.runner_jar).resolve()),'--binding',str(root/'binding.json')],cwd=root,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=stderr,text=True)
 def read():
  for line in proc.stdout:q.put(line)
  q.put(None)
 threading.Thread(target=read,daemon=True).start()
 def send(kind,body,reply=None):
  f={'protocolVersion':1,'frameId':str(uuid.uuid4()),'type':kind,'binding':binding,'payload':body}
  if reply:f['replyTo']=reply
  controls.append(f);proc.stdin.write(canonical(f)+'\n');proc.stdin.flush()
 def ack(f):send('ack',{'ackSeq':f['payload']['eventSeq'],'revision':1,'serverTime':now()},f['frameId'])
 deadline=time.monotonic()+50
 try:
  while time.monotonic()<deadline:
   line=q.get(timeout=max(.1,deadline-time.monotonic()))
   if line is None:break
   f=json.loads(line);trace.append(f);kind=f['type'];b=f['payload']
   if kind=='ready':
    assert {'artifact.collection.v1','artifact.set.v1'}<=set(b['capabilities'])
    assert not {'control.gateSets.v1','source.attestation.v1'}&set(b['capabilities'])
    send('start',{'executionDirectory':str(root),'bundle':bundle,'authorization':auth,'lease':lease,'environment':env,'inputs':{}})
   elif kind=='processLaunch':
    child=subprocess.Popen(b['argv'],cwd=root/b['relativeCwd'],env=b['environment'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,start_new_session=True)
    children.append(child);registration='fixture-'+str(child.pid);send('processStarted',{'requestId':b['requestId'],'registrationId':registration,'pid':child.pid,'processGroupId':child.pid,'startIdentity':'test-reaped-'+str(child.pid)},f['frameId'])
    stdout,err=child.communicate(b['stdin'],timeout=20);assert child.poll() is not None
    native.append({'registrationId':registration,'pid':child.pid,'exitCode':child.returncode,'reapedBeforeReady':True})
    send('processExited',{'registrationId':registration,'exitCode':child.returncode,'stdout':stdout,'stderr':err,'stdoutArtifactId':None,'stderrArtifactId':None,'timedOut':False,'captureTruncated':False})
   elif kind=='event':
    d=b['payload'];ek=d.get('kind')
    if d.get('event',{}).get('type')=='code_step_started':codeStarts[d['executionId']]=b['eventId']
    if ek=='artifact_set_ready':
     producer=d['producer'];id=producer['workflowId'];execution=binding['executionId']+('__'+'__'.join(producer['childExecutionPath']) if producer['childExecutionPath'] else '')
     assert producer['producerStartEventId']==codeStarts[execution]
     assert len(native)==(1 if id=='root' else 2)
     assert d['outcome']==('FAILED' if id=='child' else 'SUCCESS')
     assert f==json.loads((root/f'.runner-state/events/{b["eventSeq"]}.json').read_text())
     assert (root/f'run/{id}.txt').read_text()=='once\n'
     assert 1<=len(d['declarationIds'])<=64 and len(canonical(f).encode())<=65536
     ack(f);ready[b['eventId']]=f
     # Controlled S/P prepared snapshot/ticket metadata: no actual P member/index store claimed.
     snap=sha(canonical({'ids':d['declarationIds'],'fixture':True}).encode());setid='set-'+b['eventId'];ticket='ticket-'+b['eventId']
     send('artifactSetCollectRequest',{'setId':setid,'ticketId':ticket,'snapshotSha256':snap,'policySha256':d['policySha256'],'producer':producer,'boundaryEventId':b['eventId'],'expiresAt':now()})
    elif ek=='artifact_set_committed':
     assert d['boundaryEventId'] in ready;commits[d['setId']]=f
     ack(f)
     send('artifactSetIndexAck',{'setId':d['setId'],'snapshotSha256':d['snapshotSha256'],'eventId':b['eventId'],'eventSeq':b['eventSeq'],'setIndexHash':sha(('fixture-index:'+d['setId']).encode()),'revision':1},f['frameId'])
    else:
     if d.get('event',{}).get('type')=='code_step_completed':
      id='child' if '__nested__' in d['executionId'] else 'root'
      expected=[x['payload']['payload'] for x in ready.values() if x['payload']['payload']['producer']['workflowId']==id]
      assert expected and all('set-'+x['payload']['eventId'] in completes for x in ready.values() if x['payload']['payload']['producer']['workflowId']==id)
      closed.append(id)
     ack(f)
   elif kind=='artifactSetCollectComplete':
    commit=commits[b['setId']];cb=commit['payload'];assert b['eventId']==cb['eventId'] and b['eventSeq']==cb['eventSeq']
    assert b['snapshotSha256']==cb['payload']['snapshotSha256'];assert b['setIndexHash']==sha(('fixture-index:'+b['setId']).encode())
    assert f['replyTo']==commit['replyTo'];completes[b['setId']]=f
   elif kind=='checkpoint':pass
   elif kind=='result':result=b;break
   else:raise AssertionError('unexpected '+kind)
  assert result and result['status']=='FAILED',result
  finalization=json.loads((root/'.runner-state/finalization.json').read_text())
  assert finalization['binding']==binding and finalization['status']==result['status']
  assert finalization['finalEventSeq']==result['finalEventSeq'] and finalization['businessOperationsClosed'] is True
  assert finalization['phase']=='E_BUSINESS_CLOSED_JVM_EXIT_PENDING' and finalization['stopConfirmed'] is False
  assert result['stopReceipt']['confirmed'] is False and result['stopReceipt']['reason']=='JVM_EXIT_PENDING'
  proc.stdin.close();proc.wait(timeout=5);assert proc.returncode==1
  assert len(ready)==len(commits)==len(completes)==3
  assert sum(len(x['payload']['payload']['declarationIds']) for x in ready.values())==71
  assert closed==['root','child'] and [x['exitCode'] for x in native]==[0,4]
  (root/'smoke-verdict.json').write_text(json.dumps({'passed':True,'actual':'fixed production E JAR, normal interpreter/RunnerProcesses NDJSON, real native Bash root and child exits, Code boundary/partitions/dual ACK/Complete and original FAILED outcome','controlled':'S process transport and snapshot/ticket/P index proof simulated; no real S kernel peer/P HTTP or full workflow acceptance','readyPartitions':len(ready),'declarations':71,'complete':len(completes),'native':native,'status':result['status'],'engineExitCode':proc.returncode,'businessFenceBeforeResult':True,'stopConfirmedByE':False,'postStopScannerStartedByE':False,'runnerJarSha256':sha(Path(a.runner_jar).read_bytes())},indent=2)+'\n')
 finally:
  (root/'ipc-trace.json').write_text(json.dumps(trace,indent=2)+'\n')
  (root/'control-trace.json').write_text(json.dumps(controls,indent=2)+'\n')
  for owned in children:
   if owned.poll() is None:
    os.killpg(owned.pid,signal.SIGTERM)
    try:owned.wait(timeout=2)
    except subprocess.TimeoutExpired:os.killpg(owned.pid,signal.SIGKILL);owned.wait()
  try:proc.stdin.close()
  except BrokenPipeError:pass
  try:proc.wait(timeout=5)
  except subprocess.TimeoutExpired:proc.kill();proc.wait()
print((root/'smoke-verdict.json').read_text())
