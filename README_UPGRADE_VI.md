# MHD Sports – bản nâng cấp nhiều nhà đài

## Nội dung nâng cấp

- Đọc `index.json` để quản lý nhiều nhà đài.
- Mỗi nhà đài trỏ tới một file JSON riêng; mỗi file có thể chứa danh sách `matches`.
- Đọc đúng API Chuối Chiên: `matches[]`, `teams`, `league`, `matchTime`, `status`, `score`, `blvs[]/blvs_*[]`, `streams[]`.
- Mỗi trận chỉ tạo một thẻ; bấm trận để chọn bình luận viên và chất lượng HD1/HD2/FHD.
- Tự tải lại JSON khi mở lại ứng dụng và định kỳ 5 phút khi ứng dụng đang chạy.
- Bỏ cache URL Raw GitHub khi tải dữ liệu.
- Giữ lại trình phát Media3, giao diện nền sân vận động, điều khiển remote, sắp xếp/ghim/ẩn nhóm và cơ chế cài đè APK bằng keystore hiện tại.

## 1. Cập nhật repository dữ liệu Api-JK

Giữ nguyên `sport.json` đang được GitHub Actions cập nhật. Chép file:

`github-data/index.json`

lên thư mục gốc repository `guiterhd-bit/Api-JK` và đặt tên chính xác là `index.json`.

Raw URL mà ứng dụng dùng mặc định:

`https://raw.githubusercontent.com/guiterhd-bit/Api-JK/main/index.json`

Mẫu index ban đầu đã trỏ tới `sport.json` của Chuối Chiến TV.

## 2. Thêm nhà đài mới

Thêm một mục vào mảng `stations` trong `index.json`:

```json
{
  "id": "ten-nha-dai",
  "name": "TÊN NHÀ ĐÀI",
  "logo": "https://example.com/logo.png",
  "url": "https://raw.githubusercontent.com/USER/REPO/main/stations/ten-nha-dai.json",
  "enabled": true
}
```

Tạo file JSON ở URL đó. Với dữ liệu Chuối Chiến, ứng dụng đọc trực tiếp `matches[]` và các luồng trong `blvs[].streams[]`. Với JSON/M3U thông thường, vẫn hỗ trợ các trường `name`, `url`/`link`/`stream`/`m3u8`, `group`, `start`, `end` và cấu trúc `groups[].channels[]`.

Để tạm ẩn nhà đài, đặt `enabled` thành `false`.

## 3. Ghi đè mã nguồn ứng dụng

Giải nén gói nâng cấp và chép đè thư mục `app/src/main/java/com/mhd/sports/` lên dự án gốc, đồng thời chép đè các file `README_UPGRADE_VI.md`. Có thể thay toàn bộ source project bằng thư mục trong ZIP nếu bạn muốn giữ đồng nhất.

Không xóa hoặc thay `app/sportsplayer.jks`. Đây là keystore hiện có của dự án; giữ nguyên để APK mới có cùng chữ ký với bản đã cài. Không chia sẻ file keystore/mật khẩu công khai.

## 4. Build APK để cài đè

Push source lên repository ứng dụng có workflow `.github/workflows/build.yml`. GitHub Actions sẽ chạy `Build APK`, tạo APK Release và GitHub Release như workflow hiện tại.

Ứng dụng giữ nguyên `applicationId = com.mhd.sports`. Để cài đè thành công, APK cũ phải được ký bằng cùng keystore và `versionCode` của APK mới phải lớn hơn hoặc bằng bản đang cài. Nếu Android báo xung đột chữ ký, không gỡ ứng dụng cũ trước khi xác minh keystore, vì gỡ ứng dụng có thể làm mất dữ liệu cục bộ.

## 5. Tự cập nhật dữ liệu khác với tự cập nhật APK

- **Dữ liệu nhà đài/trận đấu:** ứng dụng tự đọc lại `index.json` và các file JSON mỗi 5 phút khi đang mở, và khi quay lại ứng dụng. Sửa JSON trên GitHub không cần phát hành APK mới.
- **Mã nguồn/tính năng ứng dụng:** vẫn cần build APK mới. Nút cập nhật APK trong ứng dụng còn cần đặt `GITHUB_REPO` trong `Models.kt` thành repository phát hành APK thực tế; không dùng repository `Api-JK` nếu repository đó chỉ chứa dữ liệu.

## Lưu ý dữ liệu

Endpoint đã xác nhận cho Chuối Chiến là `https://api-v2.chuoichientv.net/v2/matches?type=live&page=1&limit=500`; `sport.json` là đầu ra do updater của repository dữ liệu tạo. Tham số `type=live` chỉ được giữ nguyên, không tự giả định API hỗ trợ `upcoming` hoặc `finished`.
