# MHD Sports

## Phát hành APK production và cập nhật trong ứng dụng

Mỗi lần push lên `main` hoặc chạy thủ công workflow `Build APK`, GitHub Actions sẽ build APK Release, ký bằng keystore production gốc, xác minh chữ ký/package/version, phát hành GitHub Release rồi cập nhật `version.json`. Ứng dụng chỉ mời cập nhật khi `versionCode` từ GitHub lớn hơn phiên bản đang cài.

### GitHub Actions Secrets bắt buộc

Vào **Repository → Settings → Secrets and variables → Actions** và tạo 4 secrets:

- `KEYSTORE_BASE64`: nội dung file `sportsplayer.jks` đã mã hóa Base64.
- `KEYSTORE_PASSWORD`: mật khẩu keystore.
- `KEY_ALIAS`: nhập chính xác `sportsplayer`.
- `KEY_PASSWORD`: mật khẩu của alias `sportsplayer`.

Không commit file JKS hoặc mật khẩu vào repository. Workflow chỉ giải mã JKS tạm thời, kiểm tra alias, ký APK Release, rồi xóa JKS dù build thành công hay thất bại.

### Tạo KEYSTORE_BASE64 bằng Windows PowerShell

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\duong-dan\sportsplayer.jks")) | Set-Clipboard
```

Dán nội dung clipboard vào GitHub Secret `KEYSTORE_BASE64`.

### Đánh số phiên bản

Workflow đặt `versionCode = max(version.json versionCode, GitHub Actions run number, 141) + 1`. Vì vậy run number của repo mới thấp cũng không làm APK tụt về phiên bản cũ. File `version.json` được cập nhật sau khi APK đã được build, kiểm tra và phát hành thành công.
