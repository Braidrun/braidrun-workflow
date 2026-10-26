"""Export a TEST-ONLY Java launcher against a separately frozen engine distribution.
Run after RunnerFixtureClasspathTest and installDist from the same source commit.
Does not launch JVMs, edit the production distribution, or enable network bypasses.
"""
import argparse, hashlib, json, os, shutil, subprocess, zipfile
from pathlib import Path

p=argparse.ArgumentParser()
for name in ['distribution','out']: p.add_argument('--'+name,required=True)
a=p.parse_args();root=Path(__file__).resolve().parents[3];dist=Path(a.distribution).resolve();out=Path(a.out).resolve()
head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
assert not subprocess.check_output(['git','status','--porcelain'],cwd=root,text=True).strip(), 'Commit source before export'
assert (dist/'runner-host.jar').is_file()
out.mkdir(parents=True,exist_ok=False)
subprocess.run(['cp','-cR',str(dist),str(out/'engine')],check=True)
sha=lambda f:hashlib.sha256(f.read_bytes()).hexdigest()
# Match by bytes, not basename: incompatible test dependencies cannot silently shadow runtime jars.
known={sha(f):f for f in (out/'engine/lib').glob('*.jar')}
cp=['engine/runner-host.jar'];extras=out/'test-lib';extras.mkdir()
for raw in (root/'build/runner-http-fixture.classpath').read_text().split(os.pathsep):
 f=Path(raw)
 if not f.is_file() or f.suffix!='.jar':continue
 digest=sha(f)
 if digest in known:target=known[digest]
 else:
  target=extras/f.name
  assert not target.exists() or sha(target)==digest, 'Conflicting test dependency basename'
  if not target.exists():shutil.copy2(f,target)
 if str(target.relative_to(out)) not in cp:cp.append(str(target.relative_to(out)))
# runner-host.jar references its own engine/lib dependencies; explicit entries also serve test classes.
manifest='Manifest-Version: 1.0\r\nMain-Class: com.fartech.agents.runner.RunnerHttpAgentFixtureMainKt\r\nFixture-Only: true\r\nEngine-Source-Commit: '+head+'\r\n'
value='Class-Path: '+' '.join(cp)
while len(value)>70:manifest+=value[:70]+'\r\n';value=' '+value[70:]
manifest+=value+'\r\n\r\n'
classes=root/'build/classes/kotlin/test';jar=out/'http-agent-fixture.jar';count=0
with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as z:
 z.writestr('META-INF/MANIFEST.MF',manifest)
 for f in classes.rglob('*.class'):
  if f.name.startswith(('RunnerHttpAgentFixtureMainKt','FixtureHttpSession')):
   z.write(f,str(f.relative_to(classes)));count+=1
assert count>0
(out/'classpath.txt').write_text('\n'.join(cp)+'\n')
shutil.copy2(root/'src/test/kotlin/com/fartech/agents/runner/RunnerHttpAgentFixtureMain.kt',out/'RunnerHttpAgentFixtureMain.kt')
for name in ['runner_http_agent_smoke.py','export_runner_http_agent_fixture.py']:shutil.copy2(root/'src/test/fixtures'/name,out/name)
shutil.copytree(root/'src/test/resources/runner-workflow-v1/fixtures/valid',out/'base-fixtures')
shutil.copy2(root/'docs/MAC_RUNNER_ARTIFACT_READY_HANDOFF.md',out/'README.md')
inventory=[{'path':str(f.relative_to(out)),'sha256':sha(f),'size':f.stat().st_size} for f in sorted(out.rglob('*')) if f.is_file()]
receipt={'sourceCommit':head,'testOnly':True,'productionDistributionUnmodified':True,'runnerHostSha256':sha(dist/'runner-host.jar'),'fixtureJarSha256':sha(jar),'compiledFixtureClasses':count,'files':inventory,'actualSPCombinedAcceptance':False}
(out/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps({'path':str(out),'fixtureJarSha256':receipt['fixtureJarSha256'],'sourceCommit':head,'extraTestJars':len(list(extras.glob('*.jar')))}))
