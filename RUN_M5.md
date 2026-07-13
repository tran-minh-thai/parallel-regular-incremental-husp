# RUN_M5

```bash
# 1
cd "$HOME/My Drive/Researching/07_RegIncHUSPM_Parallel_July2026_HJS/RegIncHUSPM_Parallel_Code"

# 2
find src -name '*.java' -print0 | xargs -0 javac --release 11 -d out
java -Xmx2g -cp out test.ExperimentOfficial --test

# 3
nohup bash ./run_experiments.sh --no-maven --skip-build > nohup_run.log 2>&1 &

# 4
tail -f results/run_log_all_*.txt
```

Crash → có nội dung trong `results/run_*/completed.txt` thì:
`bash ./run_experiments.sh --no-maven --skip-build --resume`

Đối chiếu khi xong: recall SIGN 0.9667 · LEVIATHAN/BIBLE/FIFA 1.0 · S3 `OK` ·
S5 hàng `P-RIncHUSP` và `P-RIncHUSP-invidx` giống hệt HS/recall (chỉ khác thời gian) ·
S2/S5 `P-RIncHUSP` (T=10) nhanh hơn `RIncHusp-Fix0.4` (T=1) ở MỌI dataset.

Kết quả trong `results/run_*/`: `results.csv` (đủ S1–S6), `dataset_stats.csv` (bảng đặc trưng),
`meta.properties` (`env.gitCommit` PHẢI khớp commit v3). S6 = δ-sweep chỉ SIGN/LEVIATHAN/BIBLE.
