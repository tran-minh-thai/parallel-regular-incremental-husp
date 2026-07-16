# RUN_M5 — suite v5 (P-RIncHUSP **EXACT**)

> **Đây là thuật toán KHÁC với lần chạy trước.** Mọi thư mục `results/run_*` cũ là `v4` (bản *xấp xỉ*,
> recall < 1). Signature `v5;algo=eng05seed+trie+exact` sẽ **tự chặn** `--resume` nhầm vào chúng — nhưng
> đừng lấy số cũ ghép với số mới.

```bash
# 1 — Vào thư mục gốc của repo
cd /đường/dẫn/tới/RegIncHUSPM_Parallel_Code

# 2 — Build + smoke (bắt buộc, ~3 phút; quét cả S6–S10 trên dataset ví dụ)
javac --release 11 -d out $(find src/main/java -name '*.java')
java -Xmx2g -cp out test.ExperimentOfficial --test

# 3 — Chạy thật
nohup bash ./run_experiments.sh --no-maven --skip-build > nohup_run.log 2>&1 &

# 4 — Theo dõi
tail -f results/run_log_all_*.txt
```

Crash giữa chừng → `bash ./run_experiments.sh --no-maven --skip-build --resume`

---

## ⚠️ BẮT BUỘC: cắm sạc + TẮT Low Power Mode

Dòng đầu log phải là:
```
### thread sweep (pinned) = [1, 2, 4, 8, 10] | best T = 10 ###
```
Thấy `!! WARNING ... TRUNCATED to [1, 2, 4, 8]` → máy chỉ nhận 8 lõi → **Ctrl-C, chỉnh nguồn, chạy lại**.

## ⚠️ BẮT BUỘC: kiểm ổ đĩa và heap trước khi chạy

```bash
df -h /      # cần >= 20 GB trống
```
Đặt `-Xmx` **thấp hơn hẳn RAM vật lý**. Nếu heap chạm mức RAM, JVM sẽ swap chứ không báo lỗi: chậm
hơn nhiều lần và có thể lấp đầy ổ đĩa, giết cả lần chạy dài.

## ✅ Cổng chặn ở bước 2 (smoke) — sai là DỪNG, đừng chạy tiếp 4h

| Kiểm tra trong output `--test` | Bắt buộc |
|---|---|
| `S10-exactness  P-RIncHUSP[reg,disc]` | **recall = 1.0000** |
| `S10-exactness  P-RIncHUSP[reg,-]` và `[-,disc]` | recall **< 1.0** (từng cờ một mình *không* đủ) |
| `S9-musweep` (6 giá trị μ) | recall = **1.0000 ở CẢ 6** |
| `S3 HS invariance across T` | `OK` |

---

## Đối chiếu khi chạy xong

| Kiểm tra | Kỳ vọng |
|---|---|
| **Recall** | **1.0000 ở MỌI dataset, MỌI kịch bản** (không còn SIGN 0.9667) |
| **S10 (bảng cơ chế)** | `[-,-]` và `[-,disc]` sót mẫu · `[reg,-]` sót mẫu · **`[reg,disc]` = 1.0000** |
| **S9 (θ₀)** | recall ≡ 1.0000 ∀μ; thời gian hình chữ **U**, **cực tiểu tại μ=1.0**; μ=0.4 chậm hơn nhiều |
| **S8 (crossover)** | P-RIncHUSP **phẳng** theo #batch; ParRemine **tuyến tính** → cắt nhau ở **k≈3–8** tùy tập |
| **S7 (ρ)** | `P-RIncHUSP` = 1.0000 ∀ρ · `P-RIncHUSP-approx` **sụp khi ρ giảm** (đây là lý do phải làm exact) |
| **S2** | P-RIncHUSP nhanh hơn ParRemine-RDLB **và recall bằng nhau (1.0)** |
| **S5** | `P-RIncHUSP` ≡ `P-RIncHUSP-invidx` về HS/recall (chỉ khác thời gian) |

Kết quả: `results/run_*/results.csv` · `dataset_stats.csv` · `meta.properties`
(signature `v5;algo=eng05seed+trie+exact`, có `env.gitCommit`).

**Ước lượng: ~2–4h** trên máy 10 nhân, tùy δ của từng tập.

---

## Probe chạy RIÊNG, SAU khi suite xong (đừng nhét vào suite)

`partitionMine` (đào từng batch → exact sau *mỗi* batch) **bị OS OOM-killer giết** ở SIGN k=8.
OOM cấp hệ điều hành sẽ giết luôn cả run 4–6h, nên nó nằm ngoài suite:

```bash
java -Xmx16g -cp out test.KBench datasets/SIGN_seq.txt datasets/SIGN_eui.txt 0.03 0.30 SIGN 10 0.25
```
Kỳ vọng: **cả hai đều exact**, nhưng bản k-phần đắt hơn ~3× ở k=4 và có thể chết ở k≥8 —
đúng *nguyên lý granularity* (phân hoạch càng mịn, candidate superset càng nở). Đây là **negative
result có chủ đích**, dùng để trả lời sẵn câu hỏi reviewer *"sao không exact sau mỗi batch?"*.

---

## Vì sao suite này khác các lần chạy cũ

1. **μ = 1 không phải hyperparameter.** θ₀ = δ·U(D_old) và θ_disc = δ·U(ΔD) — mỗi phần được đào ở
   ngưỡng tự nhiên của chính nó. Đây là điều kiện của **bổ đề phân hoạch**, không phải kết quả tinh chỉnh.
2. **Cắt tỉa đều đặn khi gieo hạt dùng ρ·N_final** (sound) thay cho ρ·N_current.
3. Hai điểm trên cộng lại khiến P-RIncHUSP **chính xác**: tập trả về bằng đúng khai thác lại, trong
   khi vẫn nhanh hơn khai thác lại song song khi số lô vượt điểm giao.

Chi tiết lý thuyết: `../paper/results_v3/seed_exactness.md`.
