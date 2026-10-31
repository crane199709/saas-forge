import json, os, pathlib, subprocess, tempfile
script = str(pathlib.Path(__file__).with_name('check-package.sh'))
with tempfile.TemporaryDirectory(prefix='ghcr-package-regression-') as tmp:
    curl=pathlib.Path(tmp)/'curl'
    curl.write_text('''#!/usr/bin/env python3
import os, sys
with open(sys.argv[sys.argv.index('--output')+1], 'w') as output:
    output.write(os.environ['PACKAGE_FIXTURE'])
print('200', end='')
''')
    curl.chmod(0o755)
    base={'name':'saas-forge-v2/forge-gateway','visibility':'private','owner':{'login':'crane199709'},'repository':None}
    cases=[('private-null-repository',{},0),('public',{'visibility':'public'},1),('other-repository',{'repository':{'full_name':'crane199709/other'}},1),('other-package',{'name':'other/forge-gateway'},1),('other-owner',{'owner':{'login':'someone-else'}},1),('matching-repository',{'repository':{'full_name':'crane199709/saas-forge-v2'}},0),('owner-case',{'owner':{'login':'Crane199709'}},0)]
    failed=0
    for name, changes, expected in cases:
        fixture=base|changes
        env=os.environ|{'PATH':tmp+':'+os.environ['PATH'],'PACKAGE_FIXTURE':json.dumps(fixture),'IMAGE_PREFIX':'ghcr.io/crane199709/saas-forge-v2','GITHUB_REPOSITORY_OWNER':'crane199709','GITHUB_REPOSITORY':'crane199709/saas-forge-v2','GITHUB_API_URL':'https://api.github.com','GH_TOKEN':'fixture-only','OWNER_TYPE':'User'}
        p=subprocess.run(['bash',script,'forge-gateway'],env=env,text=True,capture_output=True)
        ok=p.returncode==expected
        print(f'{"PASS" if ok else "FAIL"} {name}: expected={expected} actual={p.returncode}')
        print(p.stdout+p.stderr,end='')
        failed+=not ok
    raise SystemExit(bool(failed))
