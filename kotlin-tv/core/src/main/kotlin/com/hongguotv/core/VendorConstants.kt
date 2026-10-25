// Derived from drpys 22261ad; GPL-3.0. See kotlin-tv/NOTICE.md.
package com.hongguotv.core

/** 字节系设备档案与签名常量；现役档案（device/deviceTtreq）由应用壳在启动时
 *  adopt/注册覆盖（见 DeviceRegister），passport/reading 的 Cookie 从这取值。 */
object VendorConstants {
    // 73932 档案（2026-10-04 抓包刷新）：71332 旧档案的 install_id 已被服务端
    // 风控清理——reading 系整段 0 字节拒、passport 报 error_code=22「非法应用」。
    // 与 ANON_TTREQ 同源，失效时需整组同换。
    const val VIDEO_UA = "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; Xiaomi 14; Build/UKQ1.230804.001; Cronet/TTNetVersion:8d40f833 QuicVersion:462f352c 2026-08-31)"
    const val APP_UA = "com.phoenix.read/73932 (Linux; U; Android 14; zh_CN; Xiaomi 14; Build/UKQ1.230804.001)"
    /** 无签名 video_model 请求专用 UA：与 73932 客户端对齐（与签名路径的 VIDEO_UA 相同） */
    const val VIDEO_MODEL_UA = VIDEO_UA
    /** 静态兜底档案配套的 ttreq 票（服务端按 install_id 发放，与 device 同一次抓包） */
    const val ANON_TTREQ = "1$5c6c7c7cd605c0a9f176a0533d9c64755b5df874"
    /** 阵亡名单：71332 旧档案身份，持久化档案命中即重铺静态兜底 */
    val DEAD_IIDS = setOf("1905892595382586", "1905892595378490")

    /** 静态兜底档案（字段顺序即签名 query 顺序，不可重排；与 UA 的系统版本必须成套） */
    fun defaultDevice(): LinkedHashMap<String, String> = linkedMapOf(
        "iid" to "2715158266282762",
        "device_id" to "2715158266032906",
        "ac" to "wifi",
        "channel" to "xiaomi_8662_64",
        "aid" to "8662",
        "app_name" to "novelread",
        "cdid" to "babf8a0e-8586-40ca-bb75-20a1a698b43f",
        "version_code" to "73932",
        "version_name" to "7.3.9.32",
        "device_platform" to "android",
        "os" to "android",
        "ssmix" to "a",
        "device_type" to "23127PN0CC",
        "device_brand" to "xiaomi",
        "language" to "zh",
        "os_api" to "34",
        "os_version" to "14",
        "manifest_version_code" to "73932",
        "resolution" to "1200*2670",
        "dpi" to "460",
        "update_version_code" to "73932",
        "host_abi" to "arm64-v8a",
        "dragon_device_type" to "phone",
        "pv_player" to "73932",
        "compliance_status" to "0",
        "need_personal_recommend" to "1",
        "player_so_load" to "1",
        "is_android_pad_screen" to "0"
    )

    /** 现役设备档案（签名 query 与 medusa 都从这里取值）；整档替换、发布后不可变 */
    @Volatile var device: LinkedHashMap<String, String> = defaultDevice()
    /** 现役 ttreq 票（注册响应 Set-Cookie 下发；空 = 用静态兜底 ANON_TTREQ）。不进 device——那要参与签名 query */
    @Volatile var deviceTtreq: String = ""

    /** 匿名 Cookie：reading 系按 install_id 风控，缺它静默拒（HTTP 200 + 0 字节）；
     *  passport_csrf_token 空值必须在场（缺失时 send_code 走换绑分支报 1001） */
    fun anonymousCookie(): String {
        val ttreq = deviceTtreq.ifEmpty { ANON_TTREQ }
        return "passport_csrf_token=; passport_csrf_token_default=; store-region=cn-gd; store-region-src=did; install_id=${device["iid"]}; ttreq=$ttreq"
    }

    val sbox = decode64("+n0Ia5xZs0sEXznQOEqRmQBnpiCf9U2Ccybu3xhmgzOAAxn72f6uqqmwUsYL83klTni0NqxdGieeiNu9PGPsSRXBMB/cuFbUbM3KCUPINaPvHvSW0vwOcnuUhNHqRVpiAj/TEoE0K91+5ijypUYTATsh9mE3KSoN7Yyvv51cuyR2D3XkU4nhmI2xmmVwT1RMWKtub4sjxAcRDLrPoKSO2AU9FLLadMPX577Wf95IFj6FkKFVt3dCIsmGUC4X+WQxLJvxbRxEaOPpqJOXyzJX6+Vxaq3AzMfF/WAdoi1Hp+JRaV56zgpBtpWP97mH4DoGEIq1+FvV8LyS/3wvwugbQOwb2r26mJEMsiuDQTRn+wrYdrVGBVlhI3WQhyrjUBVMrLF5667llUcEaPCGPVGLD8qO5LlO8hKCvA7V9+8oJc9bXelqVQLhM76T5/WtnT45JKji+hdX0HoNCDDWuKON/QeaxB5uImSX0h2wv0VmP2zd2yeApxHcpsVS+MC2yFwAc2B7oBkTqsk1SEvTpM2fmfMQREBUfin0Bh+iq6EvPPavhWI2IX9e3yAas7Tm/3KEj2UmlFp36kN4x0rMLBRrxuh0U/zUHM4xcAMYjJY4MonxOl/X+alpt2M3WMI7w3HLnpIBigtNiJu7T21v4P6lSd5WFgntnMEt7oF92XzRLkJbTcGmXepE/UVOG6E/0YnhfS+q26utWcuxzpooyeD2cDlK1/8w9d28VzsRjbLuALbmGlp8+d7EzS6Au7lMpZ+ECMZvQmzwJ+eLOpxR+2chdUExp8ogQyq3v9l68rX4jCwjg0+PYKAEEzcU4wHFY2ZcdIHfWL1okD3SszT0GZMyKdZJrg1L2AeerB4tC0C4crp2EHGo5FYdSP7lwkeR2ocmnR+Ia8CYviUJlzOjhRZef9xuVOn3qcjow3fQgivsAmKKkg4+sA8F8/GWeDiGNhg8JM8KtFPMYWWkx5TVFX5t73kiNRJqjlIGVXtGZFCV4gzt0xcDopuZ6xz8r9Rzafpf9ywev8jh8592gHFIqpStZPuJxmDDMrNN0uBE3V+oscdoIzTJbRJ/t+sVvqnReJOgDJKk10fjisJwqyZBmnmn2BSFj8BvVtCMEbkuPOKdzw7eA11GPs04Qw8zWtkaZWwiO/wwpojqN6K0jY5RnNZA7vn4hPSul+nKCkVnVwQvg1zVxcSCtqORmB9KrJaBbssbCQivGJVJfVTt+hYxOtq4ZvWl8f4QAQZ0zGPffCgl9s6yT4vlvIdpu4YhBwA25wtQWZsc6GJYGWHyvSdeuh3mmUI9DSq13Fsp8C1MU3tqc04/df9LoTUXVXI5INOw/e8C7Hd+5CvbkMEFnnrUUmskEw==")
    val hashTable = intArrayOf(0xebb64faf.toInt(),0x7aadcc2.toInt(),0xcf3187bf.toInt(),0xe01138ff.toInt(),0x6d0bfcff.toInt(),0x5a30a3be.toInt(),0xb41ad638.toInt(),0x34180eb8.toInt(),0xf233eb6f.toInt(),0xb1a584cc.toInt(),0xccc30dc7.toInt(),0x47d1db51.toInt(),0xd55653de.toInt(),0x70a84fa1.toInt(),0x57473c12.toInt(),0xf76f0288.toInt(),0x2c077f0a.toInt(),0xda0dcad0.toInt(),0xfbb86f6c.toInt(),0xfdc4cf00.toInt(),0x688a020d.toInt(),0xe676c6a6.toInt(),0x8cd6338b.toInt(),0x1a3c8d0e.toInt(),0xcce8b06b.toInt(),0x6ad0ed0b.toInt(),0xa0522717.toInt(),0xdc71ac83.toInt(),0x2285db71.toInt(),0xd5b4dda6.toInt(),0x736f8650.toInt(),0x6560306c.toInt(),0x617ce2a6.toInt(),0xe423417e.toInt(),0xa40e143.toInt(),0x544e4032.toInt(),0x88dffb2a.toInt(),0x716c1ae0.toInt(),0x4c467a88.toInt(),0x5b23bb3.toInt(),0xe1d0b866.toInt(),0xbaa3dcb8.toInt(),0xae3374d3.toInt(),0xc3381a50.toInt(),0x1702f75b.toInt(),0xfe6da368.toInt(),0xf0b4cf48.toInt(),0x4e0ffbb8.toInt(),0x72aad10d.toInt(),0x26c53a3d.toInt(),0xf2bce0f6.toInt(),0xb4557581.toInt(),0x4a257fdd.toInt(),0x8c3182a2.toInt(),0xab0b3b86.toInt(),0x3d5dfb14.toInt(),0x4f103634.toInt(),0xd37b52d7.toInt(),0x444eff16.toInt(),0xeb0a33d1.toInt(),0x6ca86f6e.toInt(),0x284ba7.toInt(),0x8387cfa.toInt(),0x5fb37586.toInt())
}
