$workRoot = 'C:\Users\glenm\.cache\accord-npu-worker'
New-Item -ItemType Directory -Path $workRoot -Force | Out-Null
$ErrorActionPreference = 'Continue'
& 'C:\Users\glenm\.cache\accord-npu-test\Scripts\python.exe' -u `
    'C:\Users\glenm\.cache\accord-npu-test\enhanced_lrc_npu_worker.py' `
    --server 'http://127.0.0.1:18098' `
    --model 'C:\Users\glenm\.cache\accord-npu-test\model-small' `
    --work-root $workRoot *>> "$workRoot\worker.log"
exit $LASTEXITCODE
