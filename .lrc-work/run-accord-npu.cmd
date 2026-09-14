@echo off
if not exist C:\Users\glenm\.cache\accord-npu-worker mkdir C:\Users\glenm\.cache\accord-npu-worker
for /f "tokens=1" %%i in ('wsl.exe -d Ubuntu -- hostname -I') do set ACCORD_WSL_IP=%%i
C:\Users\glenm\.cache\accord-npu-test\Scripts\python.exe -u C:\Users\glenm\.cache\accord-npu-test\enhanced_lrc_npu_worker.py --server http://%ACCORD_WSL_IP%:18097 --model C:\Users\glenm\.cache\accord-npu-test\model-small --work-root C:\Users\glenm\.cache\accord-npu-worker >> C:\Users\glenm\.cache\accord-npu-worker\worker.log 2>&1
