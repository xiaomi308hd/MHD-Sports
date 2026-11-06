# Sports Player (APK)

Ứng dụng Android phát trực tiếp thể thao từ link **m3u8 / M3U / JSON** (link rút gọn của bên thứ 3 vẫn dùng được).

## Tính năng
- Nhập link cá nhân (nút **Nhập link**), ô nhập có dòng chờ: *nhập link của bạn để tôi có thể nhận biết*
- Gắn sẵn link trực tiếp: sửa `DEFAULT_SOURCE_URL` trong `app/src/main/java/com/sportsplayer/app/Models.kt`
- Nhóm xếp theo hàng ngang, mỗi kênh/trận là 1 thumbnail
- Đồng hồ ngày giờ đầy đủ; mỗi 30 giây tự lọc theo giờ hiện tại
- Trận đang diễn ra có tem **● TRỰC TIẾP** ở góc phải thumbnail; trận sắp tới hiện giờ bắt đầu; trận đã qua tự ẩn (tắt công tắc "Lọc theo giờ" để xem lại tất cả)
- Chạy được trên điện thoại và TV Android (hỗ trợ điều khiển bằng remote), Android 5.0+

## Định dạng JSON hỗ trợ
Xem `sample/matches.json`. Mỗi mục cần `url` (hoặc `link`/`stream`/`m3u8`), tuỳ chọn:
`name`, `logo` (hoặc `thumbnail`/`image`), `group`, `start`, `end`.
Giờ nhận: `"2026-11-06 18:00"`, `"18:00"` (hiểu là hôm nay), hoặc timestamp. Không có `end` thì tính 120 phút.
Không có `start` => kênh chạy 24/7, luôn hiển thị.

M3U: dùng `group-title`, `tvg-logo`, và (tuỳ chọn) `start="..."` trong dòng `#EXTINF`.

## Build APK bằng GitHub
1. Tạo repo mới trên GitHub, đẩy toàn bộ thư mục này lên nhánh `main`.
2. Vào tab **Actions** → chạy **Build APK** (tự chạy khi push).
3. Tải file APK ở mục **Artifacts** của lần chạy. Đẩy tag `v1.0` để APK tự lên mục **Releases**.

APK release đang ký bằng khoá debug để cài được ngay; muốn phát hành chính thức hãy thay bằng keystore riêng.
