# RUN_M5 — suite v5 (P-RIncHUSP **EXACT**)

> **Đây là thuật toán KHÁC với lần chạy trước.** Mọi thư mục `results/run_*` cũ là `v4` (bản *xấp xỉ*,
> recall < 1). Signature `v5;algo=eng05seed+trie+exact` sẽ **tự chặn** `--resume` nhầm vào chúng — nhưng
> đừng lấy số cũ ghép với số mới.

```bash
# 1 — Đồng bộ + kiểm commit
cd "$HOME/My Drive/Researching/07_RegIncHUSPM_Parallel_July2026_HJS/RegIncHUSPM_Parallel_Code"
git log -1 --oneline          # phải thấy commit "exact incremental" (chưa thấy = đợi Drive sync xong)

# 2 — Build + smoke (bắt buộc, ~3 phút; nay CÓ quét cả S6–S10 trên dataset ví dụ)
find src -name '*.java' -print0 | xargs -0 javac --release 11 -d out
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

## ⚠️ BẮT BUỘC: dọn ổ đĩa trước khi chạy

```bash
df -h /      # cần >= 20 GB trống
```
Ngày 2026-07-14 máy chỉ còn **3.8 GB** và một JVM `-Xmx12g` đã thrash vào swap, **lấp đầy 100% ổ boot**
đến mức không chạy nổi lệnh nào. Run 4–6h này có heap lớn — thiếu đĩa là chết giữa chừng.

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
| **S9 (θ₀)** | recall ≡ 1.0000 ∀μ; thời gian hình chữ **U**, **cực tiểu tại μ=1.0**; μ=0.4 chậm hơn nhiều (SIGN ~5.6×) |
| **S8 (crossover)** | P-RIncHUSP **phẳng** theo #batch; ParRemine **tuyến tính** → cắt nhau ở **k≈3–4** |
| **S7 (ρ)** | `P-RIncHUSP` = 1.0000 ∀ρ · `P-RIncHUSP-approx` **sụp khi ρ giảm** (đây là lý do phải làm exact) |
| **S2** | P-RIncHUSP nhanh hơn ParRemine-RDLB **và recall bằng nhau (1.0)** |
| **S5** | `P-RIncHUSP` ≡ `P-RIncHUSP-invidx` về HS/recall (chỉ khác thời gian) |

Kết quả: `results/run_*/results.csv` · `dataset_stats.csv` · `meta.properties`
(signature `v5;algo=eng05seed+trie+exact`, có `env.gitCommit`).

**Ước lượng: ~4–6h.** (Dài hơn v4 vì thêm S9/S10, SIGN nay chạy full S2+S4, và δ hạ thấp hơn →
nhiều mẫu hơn. Bù lại seeding ở μ=1 rẻ hơn ~10× so với μ=0.4.)

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

## Những gì đã đổi so với v4 (để khỏi quên khi viết bài)

1. **μ = 1, không còn là hyperparameter.** θ₀ = δ·U(D_old) và θ_disc = δ·U(ΔD) — mỗi phần được đào ở
   *ngưỡng tự nhiên của chính nó*. Suy ra từ **bổ đề phân hoạch**, không phải do tune.
2. **Toàn bộ tuyến "adaptive-μ / semi-high buffer" bị loại** — nó là *nguyên nhân*, không phải giải pháp:
   kéo θ₀ xuống dưới ngưỡng tự nhiên ⇒ seed nổ tung. Chính nó gây ra:
   - OOM của SIGN ở phân phối B (nay peak **92 MB**, chạy ngon);
   - việc phải **nâng δ** của LEVIATHAN/BIBLE để né OOM (nay hạ lại được → BIBLE **5 → 1349 mẫu**).
3. **Seed prune regularity dùng ρ·N_final** (sound) thay vì ρ·N_current (unsound).
4. **P-RIncHUSP giờ EXACT** — claim của bài mạnh hẳn lên:
   *"incremental **chính xác tuyệt đối**, mà nhanh hơn parallel re-mine tới 14–15×"*.
