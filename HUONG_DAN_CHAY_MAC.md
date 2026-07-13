# Hướng dẫn chạy toàn bộ thực nghiệm trên macOS (máy M5, 32 GB)

Máy chạy **kết quả cuối** của bài báo: **MacBook M5, 32 GB RAM (macOS)**.
Trên macOS dùng script bash **`run_experiments.sh`** — **KHÔNG** dùng `RUN_ALL_WINDOWS.bat`
(file `.bat` chỉ dành cho Windows).

---

## 1. Yêu cầu trên máy M5
- **JDK 11+** và **Maven** (kiểm tra: `java -version`, `mvn -v`).
- **Internet ở lần chạy đầu** (Maven tải plugin build về `~/.m2`; các lần sau offline được).

## 2. Chuẩn bị dữ liệu (giống bản Windows)
Dữ liệu `datasets/*.txt` **không** nằm trong git → phải copy tay:
- Trên Google Drive: chuột phải thư mục `RegIncHUSPM_Parallel_Code` → **"Available offline"**, đợi tải xong (nhất là `KOSARAK_seq.txt` ~80 MB).
- Copy **cả thư mục** sang máy M5.

## 3. Chạy — một lệnh
```bash
cd RegIncHUSPM_Parallel_Code
chmod +x run_experiments.sh          # chỉ cần lần đầu
./run_experiments.sh                 # TOÀN BỘ suite S1–S4, heap 16g (hợp máy 32GB)
```

Tùy chọn:
```bash
./run_experiments.sh --heap 18g      # tăng heap chút (trên máy 32GB nên tối đa ~18–20g)
./run_experiments.sh -e recall       # test nhanh vài giây (RecallProbe)
./run_experiments.sh --resume        # chạy TIẾP sau khi lỗi/tắt máy (bỏ qua phần đã xong)
./run_experiments.sh --dry-run       # chỉ in lệnh, không chạy
./run_experiments.sh -h              # trợ giúp đầy đủ
```

> Script tự bọc `caffeinate -i` để Mac **không ngủ** giữa lúc chạy nhiều giờ; tự `tee`
> ra console **và** file log. Dừng máy khỏi ngủ suốt run dài — cứ để máy cắm sạc.

## 4. ⚠️ HEAP trên máy 32 GB — để 16–18g, TUYỆT ĐỐI KHÔNG để 32g
Máy chỉ có 32 GB RAM. Đặt `-Xmx32g` sẽ **không còn chỗ cho macOS** → hệ điều hành nén/nuốt
RAM (swap) → **làm sai số đo thời gian**, hoặc lỗi hết bộ nhớ. Mặc định script là **16g**,
đã quá đủ (KOSARAK thực tế chỉ đỉnh ~4.2 GB).

## 5. Kết quả ở đâu — mỗi lần chạy = một thư mục tự mô tả
Mỗi lần chạy tạo `results/run_<thời-gian>_<hash-cấu-hình>/`:

| File | Ý nghĩa |
|---|---|
| `results.csv` | **Kết quả chính** — mỗi dòng: dataset, kịch bản, phân phối, thuật toán, μ, δ, ρ, threads, iteration, runtime_ms, peak_mb, hs_count, shs_count, recall, status. Chỉ chứa các cell **đã hoàn tất** (dòng dở của cell lỗi tự bị loại khi resume). |
| `meta.properties` | **Thông số môi trường + cấu hình**: OS, JVM, số nhân, max heap, host, git commit; δ/ρ từng dataset, μ_min/μ_max, warmup/measured, timeout, switch S1/S2/S4; `config.signature` + `status`. Dùng luôn cho phần "môi trường thực nghiệm" của paper. |
| `DONE` | **Marker hợp lệ** — chỉ có khi chạy XONG toàn bộ. **Có DONE = kết quả đầy đủ, hợp lệ**; không có = dở dang. |
| `completed.txt`, `datasets_done.txt` | Trạng thái phục vụ resume (danh sách cell/dataset đã xong). |
| `results/run_log_all_<thời-gian>.txt` | Log console đầy đủ (bảng speedup/efficiency, S3 invariance…). |

**Phân biệt kết quả hợp lệ / cũ cần bỏ:**
- Thư mục **có `DONE`** = hoàn tất, dùng được. **Không có `DONE`** = chạy dở → chạy lại `--resume` để hoàn tất (hoặc bỏ).
- `config.signature` trong `meta.properties` cho biết cấu hình sinh ra kết quả đó. Nếu bạn **đổi δ/ρ/μ/suite**, signature đổi → các thư mục run cũ (signature khác) là **kết quả CŨ của cấu hình khác** → bỏ khi phân tích cấu hình mới.

## 5b. Lỗi / tắt máy giữa chừng? — chạy tiếp bằng `--resume`
```bash
./run_experiments.sh --resume
```
Script tìm thư mục run **chưa có DONE** (cùng cấu hình) mới nhất, **bỏ qua** dataset/cell đã xong, chỉ chạy phần còn thiếu rồi tạo `DONE`. Không chạy lại phần đã hoàn tất; gọi `--resume` bao nhiêu lần cũng an toàn.

## 6. Lưu ý cho phần "môi trường thực nghiệm" của bài báo (Apple Silicon)
M5 có **nhân P (hiệu năng) + nhân E (tiết kiệm điện)** — không đồng nhất. Khi sweep số thread ở
S1, thread rơi vào nhân E chạy chậm hơn → **đường speedup/efficiency kém "sạch"** so với CPU
đồng nhất. Nên ghi rõ cấu hình M5 (số nhân P/E, tổng số luồng, RAM 32 GB, heap 16g) và giải
thích ranh giới nhân P/E khi trình bày kết quả S1.

> Dải thread S1 tự động theo `Runtime.availableProcessors()` (tổng nhân P+E của M5).
> Xác nhận bằng dòng `Cores : …` mà script in ra đầu mỗi lần chạy.

---

## 7. Checklist run chính thức v2 — Lazy-Max + S5 (cập nhật 2026-07-12)

Từ bản này, P-RIncHUSP mặc định dùng **buffer lười exact (Lazy-Max)**: mọi ứng viên dưới minUtil
được GIỮ nhưng đóng băng, chỉ "thức dậy" khi chặn toán học (SWU/IU/giao posting-list) không loại
trừ được khả năng thành HS → **HS mỗi batch bằng đúng Fix(0.4) theo chứng minh**. Suite thêm kịch
bản **S5 fine-batch** (D_old 25% + 15×5%) trên LEVIATHAN, BIBLE **và SIGN**; signature nâng **v2**
(mọi run v1 cũ không bao giờ bị resume nhầm).

### 7a. TRƯỚC khi chạy (bắt buộc)
1. **Chạy từ bản CLONE LOCAL, ngoài Google Drive** — sự cố đã gặp 2026-07-12: file
   `completed.txt`/`datasets_done.txt` mở suốt run dài trong thư mục Drive bị "rỗng hóa"
   (File Provider tráo file dưới file-descriptor đang mở). Kết quả chính (`results.csv`) không sai,
   nhưng cơ chế resume mất tác dụng. Cách làm:
   ```bash
   rsync -a --exclude 'out' --exclude 'results' \
       "$HOME/Google Drive/.../RegIncHUSPM_Parallel_Code/" ~/exp_m5/RegIncHUSPM_Parallel_Code/
   cd ~/exp_m5/RegIncHUSPM_Parallel_Code
   # ... chạy xong:  rsync -a results/ <thư mục Drive>/results/
   ```
2. **Cắm sạc + tắt Low Power Mode**, xác nhận banner in `Cores : 10` (pin/Low Power → chỉ còn 8,
   bảng speedup sẽ thiếu T=10 và chậm ~15%).
3. Đợi Drive sync xong TRƯỚC khi rsync; kiểm tra nhanh code đúng bản v2:
   ```bash
   grep -n 'lazy = true' src/main/java/test/ExpConfig.java        # phải thấy trong newProposed
   grep -n 'v2;algo=lazy1.0' src/main/java/test/RunContext.java   # signature v2
   ```
4. **Sanity 30 giây** trước run dài: `bash ./run_experiments.sh --no-maven -e all --dry-run` rồi
   `java -Xmx2g -cp out test.ExperimentOfficial --test` (sau khi build) — phải thấy các dòng
   `S5-finebatch` và `completed.txt` có nội dung.
5. Nếu `./run_experiments.sh` báo **Permission denied** (Drive làm rơi execute-bit):
   chạy `bash ./run_experiments.sh ...` hoặc `chmod +x run_experiments.sh`.

### 7b. Chạy
```bash
nohup bash ./run_experiments.sh --no-maven > nohup_run.log 2>&1 &
tail -f results/run_log_all_*.txt
```
Ước lượng ~8h (FIFA chiếm phần lớn; S5 thêm ~5–10 phút). Crash giữa chừng → kiểm tra
`completed.txt` có nội dung rồi mới `--resume`; nếu rỗng → chạy fresh lại (an toàn, chỉ tốn thời gian).
**KHÔNG `--resume` vào thư mục run dở sync từ máy khác** (trộn phần cứng 8/10-core trong một bảng).

### 7c. Kết quả kỳ vọng (đối chiếu nhanh sau khi xong)
| Kiểm tra | Giá trị đúng |
|---|---|
| Recall | SIGN 0.9667 · LEVIATHAN 1.0 · BIBLE 1.0 · FIFA 1.0 (trần seed-once; KOSARAK skip) |
| S3 invariance | `OK` ở cả 5 dataset — HS không đổi theo T |
| S5: P-RIncHUSP vs P-RIncHUSP-nolazy | **HS + recall giống hệt nhau** (bất biến exactness); lazy lợi rõ ở SIGN (~−26% match calls), ≈0 ở LEVIATHAN/BIBLE (item quá phổ biến / ứng viên quá ít — gate tự tắt khi không đáng) |
| S2 LEVIATHAN | P-RIncHUSP vẫn chậm hơn RIncHusp-Fix0.4 ~1.5–2× (điểm nghẽn order-miss đã phân tích; khai báo thẳng trong paper) |
| SHS của P-RIncHUSP | THẤP hơn bảng v1 (đếm giá trị đóng băng = chặn dưới chẩn đoán) — footnote, không phải lỗi |
| KOSARAK | lazy tự ngủ (gate, <32 ứng viên): thời gian ≈ bản không-lazy, `checkpoints=0` |
