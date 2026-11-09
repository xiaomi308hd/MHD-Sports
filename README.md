# MHD Sports (APK)

Tên gói: `com.mhd.sports`

Ứng dụng Android phát trực tiếp thể thao từ link **m3u8 / M3U / JSON** (link rút gọn của bên thứ 3 vẫn dùng được).

## Tính năng
- Nhập link cá nhân (nút **Nhập link**). Link có sẵn của ứng dụng **không hiện** trong ô nhập; để trống rồi Lưu là dùng lại nguồn có sẵn. Thông báo lỗi cũng không in link.
- Gắn sẵn link: sửa `DEFAULT_SOURCE_URL` trong `app/src/main/java/com/mhd/sports/Models.kt`
- Luồng bổ sung có sẵn (iptv-org: thể thao quốc tế, truyền hình Việt Nam): khai báo ở `Config.BUILTIN_STATIONS` (Models.kt) và `github-data/index.json`
- Trang chủ có logo + tên ứng dụng (`res/drawable-nodpi/app_logo.png`, `Config.APP_NAME`)
- **Gộp FLV + HLS**: cùng bình luận viên và cùng chất lượng chỉ còn 1 mục. HLS phát trước; nếu lỗi, tự chuyển sang FLV (và ngược lại)
- **Lọc bộ môn**: hàng nút Tất cả / Bóng đá / Bóng rổ / Bóng chuyền / Tennis / Cầu lông dưới thanh trên cùng, được nhớ cho lần sau. Môn được nhận từ trường `sport` của nguồn (nếu có) hoặc từ tên giải/trận/kênh; trận của API Chuối Chiên không rõ môn thì tính là bóng đá. Kênh không nhận ra môn chỉ hiện ở "Tất cả" (xem `Sports.kt`)
- **Luồng HTTP không bị chặn**: cho phép HTTP không mã hoá (`network_security_config.xml`), tự đi theo chuyển hướng http↔https, nhận link dạng `https://...m3u8|User-Agent=abc&Referer=https://site`, và khi lỗi tự thử lần lượt: đổi header → đổi http↔https → bỏ kiểm tra chứng chỉ TLS (chỉ cho video) → ép đọc HLS → ép đọc FLV. Đang xem mà rớt mạng thì tự kết nối lại tối đa 3 lần
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

## Màn hình phát
Thanh thông tin kính ở phía trên (hiện cùng bộ nút điều khiển, bấm OK/chạm màn hình để hiện): tên trận bên trái; bên phải là độ phân giải thực tế đang giải mã, số khung hình/giây thực tế được vẽ, và tốc độ tải (trung bình 5 giây).

## Nâng cấp nhiều nhà đài và tự làm mới dữ liệu GitHub

Bản nâng cấp hiện đọc mặc định `index.json` tại:
`https://raw.githubusercontent.com/guiterhd-bit/Api-JK/main/index.json`

`index.json` quản lý danh sách nhà đài (`id`, `name`, `logo`, `url`, `enabled`). Mỗi nhà đài có thể trỏ tới một file JSON riêng. Ứng dụng tự tải lại dữ liệu khi mở lại và mỗi 5 phút khi đang chạy; sửa JSON trên GitHub không cần phát hành APK mới.

Với API Chuối Chiên, ứng dụng đọc `matches[]`, tên hai đội, giải đấu, giờ thi đấu, tỷ số, trạng thái và các luồng trong `blvs[]`/`blvs_*[] .streams[]`. Mỗi trận hiển thị thành một thẻ; bấm thẻ để chọn bình luận viên và chất lượng phát. Xem `README_UPGRADE_VI.md` và `github-data/index.json` để cấu hình thêm nhà đài.

## Cập nhật giao diện & dữ liệu (bản mới nhất)

- **Gộp trận trùng tên**: các mục cùng tên (sau khi bỏ nhãn `[hls]`, `[flv]`, `[hls2]`...) trong cùng một nhà đài và cùng giờ chỉ còn 1 thẻ. Bấm vào thẻ rồi đổi luồng **HLS / HLS2 / FLV** ngay trên thanh trạng thái phía trên của trình phát (thanh hiện khi chạm màn hình / bấm OK).
- **Thanh trên cùng mới**: logo + tên ứng dụng bên trái, đồng hồ/ngày bên phải, kế bên là hai nút biểu tượng **Làm mới** và **Cài đặt**. Hàng bộ môn được căn giữa.
- **Cài đặt** gom: Lọc theo giờ, Sắp xếp/ẩn-hiện nhóm, Nhập link nguồn, Kiểm tra bản cập nhật.
- **Tự cập nhật**: dữ liệu tải lại mỗi `Config.DATA_REFRESH_MIN` phút (2 phút) và khi mở lại app; bản APK mới được kiểm tra khi mở app, khi quay lại app và mỗi 10 phút, hiện hộp thoại một lần cho mỗi bản mới. `Config.GITHUB_REPO` phải trỏ đúng repo chứa Releases (hiện đặt `guiterhd-bit/MHD-Sports`; sửa lại nếu repo của bạn tên khác).

### Gộp luồng nâng cao & nhận diện bộ môn
- Nhãn trong ngoặc được bỏ khi so tên: `[hls]`, `[flv]`, `[hls2]`, chất lượng `HD`/`FHD`/`SD`/`4K`/`1080p`, và `geo`. Ví dụ `(KOBE)` và `(HD KOBE)` gộp thành một thẻ; trong trình phát có các nút `HLS`, `HD HLS`, `GEO HLS`, `FLV`...
- Với API Chuối Chiên, luồng có chữ `geo` (ở nhãn, loại hoặc tên khoá `blvs_*`) được đưa vào danh sách luồng của cùng bình luận viên, xếp cuối.
- Bóng rổ: nhận từ tên giải (NBA, WNBA, CBA, KBL, Euroleague...) hoặc tên đội NBA/WNBA (xem `BASKETBALL_RE` trong `Sports.kt`). Muốn thêm đội/giải, sửa danh sách đó.


## Cập nhật ứng dụng

MHD Sports kiểm tra bản phát hành mới nhất tại GitHub Releases của repository
`xiaomi308hd/MHD-Sports`. Mỗi bản phát hành cần có tag dạng `build-N` và đính kèm
file APK trong mục Assets. Ứng dụng tự kiểm tra khi khởi động/quay lại ứng dụng
và định kỳ; người dùng cũng có thể kiểm tra thủ công trong Cài đặt.

Khi cập nhật, ứng dụng tải APK, thử cài đè qua ADB nội bộ `127.0.0.1:5555`;
nếu ADB không khả dụng, ứng dụng chuyển sang trình cài đặt Android. Các bản APK
phải được ký bằng cùng keystore `app/sportsplayer.jks` để cài đè lên bản cũ.
