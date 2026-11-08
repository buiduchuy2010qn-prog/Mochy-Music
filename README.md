<div align="center">

<img src="https://raw.githubusercontent.com/buiduchuy2010qn-prog/Mochy-Music/main/assets/github banner.png" alt="Mochy Music" width="100%"/>

<br/>

### 🇻🇳 Mochy Music — Bản Việt Hóa 100% (Dành cho Điện thoại Android · Hỗ trợ OTA & Cài đè)

<sub>Ứng dụng nghe nhạc mã nguồn mở hiện đại, mượt mà dành cho Android — đã được Việt hóa toàn diện 100% (bao gồm cả màn hình thiết lập, thẻ hồ sơ và trình phát), mặc định theo ngôn ngữ thiết bị, tích hợp cập nhật tự động (OTA) và không thu thập dữ liệu người dùng.</sub>

<br/>

<p align="center">
  <a href="https://github.com/buiduchuy2010qn-prog/Mochy-Music/releases/latest"><img src="https://img.shields.io/github/downloads/buiduchuy2010qn-prog/Mochy-Music/total?style=for-the-badge&logo=github&logoColor=white&label=l%C6%B0%E1%BB%A3t%20t%E1%BA%A3i&color=111111&labelColor=1a1a1a" alt="Downloads"/></a>
  <a href="https://github.com/buiduchuy2010qn-prog/Mochy-Music/stargazers"><img src="https://img.shields.io/github/stars/buiduchuy2010qn-prog/Mochy-Music?style=for-the-badge&logo=starship&logoColor=white&label=sao&color=111111&labelColor=1a1a1a" alt="Stars"/></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-111111?style=for-the-badge&logo=android&logoColor=3DDC84&labelColor=1a1a1a" alt="Android 8.0+"/>
  <a href="https://github.com/buiduchuy2010qn-prog/Mochy-Music/releases/latest"><img src="https://img.shields.io/github/v/release/buiduchuy2010qn-prog/Mochy-Music?style=for-the-badge&logo=tag&logoColor=white&label=phi%C3%AAn%20b%E1%BA%A3n&color=111111&labelColor=1a1a1a" alt="Release"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/Gi%E1%BA%A5y%20ph%C3%A9p-GPL--3.0-111111?style=for-the-badge&logo=gnu&logoColor=white&labelColor=1a1a1a" alt="GPL-3.0"/></a>
</p>

</div>

---

## 📥 Hướng dẫn cài đặt & Cập nhật OTA (Điện thoại Android)

1. Trên điện thoại Android, mở trang **[Bản phát hành mới nhất (Releases)](https://github.com/buiduchuy2010qn-prog/Mochy-Music/releases/latest)**.
2. Tải tệp **`Mochy-Music-vi-arm64-release.apk`** về máy.
3. Mở tệp `.apk` vừa tải để cài đặt (cho phép cài đặt ứng dụng từ trình duyệt hoặc trình quản lý tệp nếu được hỏi).
4. Mở **Mochy Music** — ứng dụng tự động hiển thị theo **ngôn ngữ thiết bị của bạn (Tiếng Việt 100%)** và không bắt buộc đăng nhập tài khoản.

> [!TIP]
> **Cập nhật tự động (OTA) & Cài đè giữ nguyên dữ liệu:**
> - Tất cả các bản `.apk` trên kho lưu trữ này đều được ký bằng cùng một chứng chỉ số cố định (`mochy_vi`) và tự động tăng **`+0.1`** số phiên bản ở mỗi lần phát hành (`8.3.2` → `8.4.0` → `8.5.0`...).
> - Khi có bản mới trên GitHub Releases của repo này, ứng dụng sẽ **tự động thông báo và tải bản cập nhật (OTA)** ngay trong ứng dụng, hoặc bạn có thể tải `.apk` mới và **cài đè trực tiếp** lên bản cũ mà không mất danh sách phát, lịch sử nghe hay cài đặt.

---

## ✨ Điểm nổi bật của bản này

- **🇻🇳 Việt hóa 100% toàn diện:** Đã dịch đầy đủ tất cả hơn **2.200 chuỗi giao diện XML** (`values-vi` & `values-vi-rVN`) và toàn bộ các màn hình Kotlin Compose của ứng dụng (Màn hình chào mừng, Thiết lập ban đầu, Nhập nhạc, Hồ sơ & Thẻ ID Mochy, Trang chủ, Trình phát nhạc, Lời bài hát, Audio Lab, Bộ chỉnh âm Equalizer, Thư viện và Cài đặt).
- **📱 Mặc định theo ngôn ngữ thiết bị:** Tự động sử dụng ngôn ngữ máy điện thoại ngay từ lần mở đầu tiên (`SYSTEM_DEFAULT`).
- **🔄 Tích hợp OTA trỏ về Repo cá nhân:** Trình kiểm tra cập nhật (`Updater.kt`), tải xuống (`UpdateSheet.kt`) và thông báo đẩy (`UpdateNotifications.kt`) kết nối trực tiếp tới `buiduchuy2010qn-prog/Mochy-Music`.
- **🎧 Chất lượng âm thanh & Hiệu ứng cao cấp:** Hỗ trợ Equalizer đa dải tần, AutoEQ cho tai nghe, Crossfade mượt mà, Dolby Atmos / Spatial Audio, lời bài hát đồng bộ từng từ (Word-by-word) và dịch lời bài hát.

---

## ⚖️ Giấy phép & Ghi nhận tác giả (License & Credits)

Dự án này là phần mềm mã nguồn mở được phân phối dưới giấy phép **[GNU General Public License v3.0 (GPL-3.0)](LICENSE)**.

- **Tác giả dự án gốc (Mochy Music):**
  - **[realidkroo](https://github.com/realidkroo)** (*mochy / DH P ASHOKAN*) — Nhà phát triển chính & Thiết kế UI/UX ([Mochy-Music](https://github.com/realidkroo/Mochy-Music))
  - **[realkeichi](https://github.com/realkeichi)** (*Keycii*) & **T-Boyke** — Đồng phát triển & Cộng tác viên
- **Dự án nền tảng kế thừa (Upstream):**
  - **[ViviMusic](https://github.com/vivizzz007/vivi-music)** bởi **[vivizzz007](https://github.com/vivizzz007)**
  - **[Metrolist](https://github.com/mostafaalagamy/Metrolist)** bởi **[Mostafa Alagamy](https://github.com/mostafaalagamy)**
- **Bản tùy biến, Việt hóa & Đóng gói OTA:**
  - Thực hiện và duy trì tại kho lưu trữ này bởi **[buiduchuy2010qn-prog](https://github.com/buiduchuy2010qn-prog/Mochy-Music)** tuân thủ đầy đủ các điều khoản của giấy phép **GPL-3.0**.