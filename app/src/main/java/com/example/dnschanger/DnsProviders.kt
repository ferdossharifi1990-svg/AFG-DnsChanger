package com.example.dnschanger

data class DnsProvider(
    val name: String,
    val primary: String,
    val secondary: String,
    val description: String
)

object DnsProviders {
    val ALL = listOf(
        DnsProvider("Shecan", "178.22.122.100", "185.51.200.2", "دور زدن فیلترینگ - محبوب‌ترین گزینه ایرانی"),
        DnsProvider("Electro", "78.157.42.100", "78.157.42.101", "جایگزین خوب برای شبکه‌های اجتماعی"),
        DnsProvider("403.online", "10.202.10.10", "10.202.10.11", "دور زدن تحریم و فیلترینگ"),
        DnsProvider("Cloudflare", "1.1.1.1", "1.0.0.1", "سریع‌ترین DNS عمومی جهان"),
        DnsProvider("Google", "8.8.8.8", "8.8.4.4", "پایدار، مناسب دانلود"),
        DnsProvider("Quad9", "9.9.9.9", "149.112.112.112", "تمرکز بر امنیت و فیلتر بدافزار")
    )
}
