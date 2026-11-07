# MHD Sports (APK)

Tên gói: `com.mhd.sports`

Ứng dụng Android phát trực tiếp thể thao từ link **m3u8 / M3U / JSON** (link rút gọn của bên thứ 3 vẫn dùng được).

## Tính năng
- Nhập link cá nhân (nút **Nhập link**), ô nhập có dòng chờ: *nhập link của bạn để tôi có thể nhận biết*
- Gắn sẵn link trực tiếp: sửa `DEFAULT_SOURCE_URL` trong `app/src/main/java/com/mhd/sports/Models.kt`
- Giao diện liquid glass tông đen cam, nền sân vận động lấy từ ảnh thumbnail đã bỏ chữ (`res/drawable-nodpi/bg_stadium.jpg`)
- Trận đang phát hiện đồng hồ đếm phút:giây tính từ giờ bắt đầu
- Nút **Sắp xếp**: ghim, Lên/Xuống, **Ẩn/Hiện** từng nhóm
- Nhóm xếp theo hàng ngang, mỗi kênh/trận là 1 thumbnail
- Đồng hồ ngày giờ đầy đủ; mỗi 30 giây tự lọc theo giờ hiện tại
- Trận đang diễn ra có tem **● TRỰC TIẾP** ở góc phải thumbnail; trận sắp tới hiện giờ bắt đầu; trận đã qua tự ẩn (tắt công tắc "Lọc theo giờ" để xem lại tất cả)
- Tự đọc giờ từ tên trận nếu nguồn không có trường `start` (ví dụ `15:30 06/11 A vs B`); tự bỏ emoji trong tên
- Nút **Sắp xếp nhóm**: ghim nhóm yêu thích lên đầu, chuyển nhóm Lên/Xuống; được lưu lại cho lần sau
- Chạy được trên điện thoại và TV Android (hỗ trợ điều khiển bằng remote), Android 5.0+

## Định dạng JSON hỗ trợ
Xem `sample/matches.json`. Mỗi mục cần `url` (hoặc `link`/`stream`/`m3u8`), tuỳ chọn:
`name`, `logo` (hoặc `thumbnail`/`image`), `group`, `start`, `end`.
Giờ nhận: `"2026-11-06 18:00"`, `"18:00"` (hiểu là hôm nay), hoặc timestamp. Không có `end` thì tính 120 phút.
Không có `start` => kênh chạy 24/7, luôn hiển thị.

M3U: dùng `group-title`, `tvg-logo`, và (tuỳ chọn) `start="..."` trong dòng `#EXTINF`.

## Nút Cập nhật
1. Sửa `GITHUB_REPO` trong `Models.kt` thành `tên-tài-khoản/tên-repo` (repo để **Public**).
2. Mỗi lần bạn push code lên `main`, GitHub Actions tự build và tạo một bản phát hành mới (`build-N`).
3. Khi có bản mới, app tự hiện thông báo **Có bản mới · Cập nhật** ở góc phải dưới (không có nút cố định). Bấm vào để tải và cài đè.
Lần đầu cần bật "Cho phép cài từ nguồn không rõ" cho ứng dụng. Các bản build từ nay đều cùng khoá ký nên cập nhật đè bình thường.

## Build APK bằng GitHub
1. Tạo repo mới trên GitHub, đẩy toàn bộ thư mục này lên nhánh `main`.
2. Vào tab **Actions** → chạy **Build APK** (tự chạy khi push).
3. Tải file APK ở mục **Releases** (hoặc **Artifacts** của lần chạy).

APK ký bằng `app/sportsplayer.jks` (khoá cố định để cập nhật đè được). Dùng cá nhân thì để nguyên; phát hành công khai hãy thay bằng keystore riêng và cất mật khẩu trong GitHub Secrets.

## Nguồn bị lỗi HTTP khi phát
Khi máy chủ trả mã lỗi (ví dụ 403), trình phát tự thử lần lượt: User-Agent trình duyệt → thêm Referer/Origin theo máy chủ phát → theo nguồn danh sách → giả lập VLC → giả lập ffmpeg, và báo rõ mã lỗi.
Nếu nguồn cần header riêng, khai báo trong JSON: `"referer"`, `"origin"`, `"user_agent"`, `"cookie"` hoặc `"headers": {...}`;
trong M3U dùng `#EXTVLCOPT:http-referrer=...` / `http-user-agent=...`; hoặc nối vào link: `https://...m3u8|User-Agent=abc&Referer=https://site`.
