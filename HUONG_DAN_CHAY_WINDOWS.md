# Hướng dẫn chạy toàn bộ thực nghiệm trên máy Windows 11

Tài liệu này hướng dẫn **copy dự án sang một máy Windows 11 khác (đã cài Maven)** và chạy
toàn bộ bộ thực nghiệm chính (official suite S1–S4) chỉ bằng **một thao tác**.

> ℹ️ **Máy chạy KẾT QUẢ CUỐI là MacBook M5 (macOS)** → xem [`HUONG_DAN_CHAY_MAC.md`](HUONG_DAN_CHAY_MAC.md).
> Tài liệu Windows này dành cho phương án phụ (máy Dell XPS 64 GB). Lưu ý heap: máy 64 GB dùng 32g, còn máy Mac 32 GB chỉ để **16–18g**.

---

## 0. Cấu hình máy chạy thực nghiệm (thực tế)

| Thành phần | Thông số |
|---|---|
| Máy | Dell XPS — Windows 11, 64-bit (tên thiết bị `Admin-PC`) |
| CPU | Intel Core **i7-10750H** @ 2.60 GHz — **6 nhân vật lý / 12 luồng** (Hyper-Threading) |
| RAM | **64 GB** (63.8 GB usable) |
| GPU | NVIDIA GTX 1650 Ti 4 GB + Intel UHD — *không dùng* (thực nghiệm thuần CPU) |
| Ổ cứng | còn ~617 GB trống |
| Heap JVM khi chạy | **`-Xmx32g`** (stack `-Xss4m`) |

**Ảnh hưởng tới S1 (scalability):** Java thấy **12 luồng** nên dải thread được đo là
**T ∈ {1, 2, 4, 8, 12}** (`ExperimentOfficial.threadCounts`). Speedup S(p)=T₁/Tₚ,
efficiency E(p)=S(p)/p — kỳ vọng gần tuyến tính tới ~**6 luồng (số nhân vật lý)**, sau đó
E giảm dần ở T=8/T=12 do 2 luồng chia nhau 1 nhân (SMT). Đây là hiện tượng **bình thường**,
nên nêu rõ ranh giới 6 nhân/SMT trong bài báo.

**Vì là laptop — tránh bó nhiệt (thermal throttling) khi chạy nhiều giờ:**
- Cắm **sạc AC** + đặt Windows Power plan **High performance**.
- Kê cao máy / dùng đế tản nhiệt, phòng mát; đóng các app nền nặng.
- `warmup + median 3 lần` lọc được nhiễu tức thời, **không** chống được nóng tích lũy.

> Nguồn cấu hình: `Researching/cauhinhXPS.txt`.

---

## 1. Trên máy NGUỒN (máy hiện tại) — chuẩn bị copy

Vì dự án nằm trong Google Drive, hãy đảm bảo các file **đã tải về thật** (không phải
biểu tượng "chỉ trên đám mây") trước khi copy — đặc biệt là thư mục `datasets/`
(riêng `KOSARAK_seq.txt` ~80 MB).

- Trong Google Drive: chuột phải thư mục `RegIncHUSPM_Parallel_Code` → **"Luôn giữ trên thiết bị này" / "Available offline"**, đợi tải xong.
- Sau đó **nén cả thư mục** `RegIncHUSPM_Parallel_Code` thành `.zip` để mang sang máy khác.

**Cần copy tối thiểu những gì** (hoặc đơn giản là copy cả thư mục):

| Bắt buộc | Không cần copy (sẽ tự tạo lại) |
|---|---|
| `src/` — mã nguồn | `target/`, `out/` — thư mục build |
| `pom.xml` — cấu hình Maven | `results/` — kết quả cũ |
| `datasets/` — **toàn bộ file dữ liệu** | `.git/`, `.idea/` |
| `RUN_ALL_WINDOWS.bat` — lệnh chạy | |

---

## 2. Trên máy ĐÍCH — yêu cầu

- **JDK 11 trở lên** (kiểm tra: mở `cmd`, gõ `java -version`).
- **Maven** trên PATH (kiểm tra: `mvn -v`).
- **Kết nối Internet ở LẦN CHẠY ĐẦU** — Maven cần tải vài plugin build về `~/.m2`.
  (Các lần sau chạy được offline.)
- **RAM**: khuyến nghị cấp heap **32 GB** (lý tưởng cho máy 64 GB — đây là mức đang dùng).
  Máy 8 GB vẫn chạy được BIBLE/SIGN/LEVIATHAN/FIFA, nhưng **KOSARAK** cần heap ≈ 16 GB trở lên.

---

## 3. Chạy — CÁCH ĐƠN GIẢN NHẤT

Giải nén, mở thư mục dự án, rồi **double-click** vào:

```
RUN_ALL_WINDOWS.bat
```

Hoặc mở `cmd` tại thư mục dự án và gõ:

```bat
RUN_ALL_WINDOWS.bat
```

File `.bat` này sẽ tự động:
1. Kiểm tra `mvn` và `java` có trên máy không.
2. Tự dò RAM để chọn mức heap an toàn (tối đa 32 GB → máy 64 GB sẽ dùng đúng 32g).
3. Kiểm tra đủ file dataset chưa (thiếu cái nào sẽ báo và **bỏ qua**, phần còn lại vẫn chạy).
4. `mvn -q compile` → biên dịch.
5. `mvn exec:exec` chạy `test.ExperimentOfficial` (toàn bộ S1–S4).
6. Vừa in ra màn hình vừa **ghi log** vào `results\run_log_all_<thời-gian>.txt`.

> ⚠️ Bộ thực nghiệm đầy đủ có thể chạy **nhiều giờ** (FIFA ~7 phút/lần chạy, KOSARAK rất
> nặng, mỗi điểm đo lặp 1 warmup + 3 lần đo). Cứ để cửa sổ mở đến khi thấy dòng **HOÀN TẤT**.

### Chỉ định heap thủ công (tùy chọn)

Nếu muốn ép mức heap (GB), truyền vào tham số đầu tiên:

```bat
RUN_ALL_WINDOWS.bat 32     :: ép -Xmx32g (khuyến nghị cho máy 64 GB RAM)
RUN_ALL_WINDOWS.bat 8      :: ép -Xmx8g  (khi máy RAM nhỏ)
```

---

## 4. Kết quả ở đâu?

Trong thư mục `results\`:

- `official_suite_<thời-gian>.csv` — **file kết quả chính** (mỗi dòng: dataset, kịch bản,
  thuật toán, số thread, runtime_ms, peak_mb, hs_count, recall, …).
- `run_log_all_<thời-gian>.txt` — toàn bộ log màn hình (tốc độ, speedup, S3 invariance…).

---

## 5. Phương án dự phòng

**a) Đã có sẵn script PowerShell** (nhiều tùy chọn hơn — chọn từng thí nghiệm, heap, stack):

```powershell
.\run_experiments.ps1 -HeapGb 32          # full suite với -Xmx32g (khuyến nghị)
.\run_experiments.ps1 -Experiment recall  # chỉ chạy recall
.\run_experiments.ps1                     # full suite, mặc định -Xmx16g
```

> Nếu PowerShell báo *"running scripts is disabled"*, chạy bằng:
> `powershell -ExecutionPolicy Bypass -File .\run_experiments.ps1`
> — hoặc đơn giản là dùng `RUN_ALL_WINDOWS.bat` (không dính lỗi này).

**b) Gõ lệnh Maven trực tiếp** (không cần script):

```bat
mvn -q compile
mvn -q exec:exec -Dexec.mainClass=test.ExperimentOfficial -DheapSize=32g -DstackSize=4m
```

**c) Không có Maven vẫn chạy được** (chỉ cần JDK 11+):

```bat
mkdir out
dir /s /b src\*.java > sources.txt
javac -d out @sources.txt
java -Xmx32g -Xss4m -cp out test.ExperimentOfficial
```

---

## 6. Xử lý sự cố nhanh

| Triệu chứng | Cách xử lý |
|---|---|
| `'mvn' is not recognized` | Maven chưa vào PATH. Cài Maven, mở lại `cmd`. |
| `'java' is not recognized` | Cài JDK 11+, đặt `JAVA_HOME`, mở lại `cmd`. |
| Treo/`OutOfMemoryError` | Máy 64 GB dùng 32g hầu như không gặp. Nếu máy nhỏ hơn: hạ heap bằng `RUN_ALL_WINDOWS.bat 16` (hoặc 8); dataset lỗi tự bị bỏ qua, phần còn lại vẫn xong. |
| `StackOverflowError` | Stack đã đặt 4m; nếu vẫn lỗi, tăng: sửa `-DstackSize=8m` trong lệnh (mục 5b). |
| `Unknown lifecycle phase ".mainClass=..."` | PowerShell cắt sai tham số `-D` có dấu chấm. `RUN_ALL_WINDOWS.bat` đã sửa (chạy `mvn` thẳng trong **cmd**). Nếu tự gõ trong **PowerShell**, phải bọc nháy: `mvn -q exec:exec "-Dexec.mainClass=test.ExperimentOfficial" "-DheapSize=32g" "-DstackSize=4m"`. Gõ trong **cmd** thì không cần nháy. |
| Lần chạy đầu tải plugin lâu | Bình thường — Maven đang tải về `~/.m2`. Cần Internet lần đầu. |
