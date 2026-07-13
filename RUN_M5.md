# RUN_M5   (chạy ở commit fork-join seeding)

```bash
# 1
cd "$HOME/My Drive/Researching/07_RegIncHUSPM_Parallel_July2026_HJS/RegIncHUSPM_Parallel_Code"
git log -1 --oneline    # PHẢI thấy "Fork-join work-stealing..." (nếu chưa, đợi Drive sync xong)

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
S1 speedup LEVIATHAN nay ~4–5× @ T=10 (E cao ở T=2/4 nhờ fork) · S5 `P-RIncHUSP` và
`P-RIncHUSP-invidx` giống hệt HS/recall · S2/S5/S6 `P-RIncHUSP` nhanh hơn `RIncHusp-Fix0.4` mọi dataset.

Kết quả `results/run_*/`: `results.csv` (đủ S1–S8, có cột build_ms/incr_ms), `dataset_stats.csv`,
`meta.properties` (signature `v3;algo=trie-fork`; `env.gitCommit` = HEAD lúc chạy).

⚠️ Fork-join seeding CHƯA test được FIFA/KOSARAK (máy 8GB không ôm nổi). Nếu FIFA/KOSARAK **OOM**:
đó là do fork cấp phát VUL độc lập — mở `AlgoPRIncHUSP.java`, tăng `seedGrain` (128→512) HOẶC đặt
`forkSeed=false` trong `ExpConfig.newProposed`, build lại, `--resume`. Ba dataset nhẹ không có rủi ro này.
