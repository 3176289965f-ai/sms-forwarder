package com.example.smsforward

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * App 配置。Token / Key 只存放在本机私有目录，不会上传到任何地方。
 *
 * v2 新增：转发目标（Telegram / 微信 ClawBot / 两者），以及 OpenClaw 网关连接参数。
 * v3 新增：设备角色（发送端/接收端）、直连中转（EC2 relay）配置、接收端游标。
 *   微信通道在 v3 被折叠隐藏（灰度保留）：配置项原样保留，UI 默认不展示、
 *   转发目标里不可选；iLink 限制解除后可重新启用。
 */
object Settings {
    private const val FILE = "smsforward_prefs"

    /** 转发目标：0=Telegram，1=微信 ClawBot（v3 已折叠隐藏），2=两者都发（v3 已折叠隐藏），3=直连中转（EC2 relay，v3 默认） */
    const val TARGET_TELEGRAM = 0
    const val TARGET_WECHAT = 1
    const val TARGET_BOTH = 2
    const val TARGET_RELAY = 3

    /** 设备角色：0=未选择，1=发送端（收短信并上传），2=接收端（轮询并通知） */
    const val ROLE_UNSET = 0
    const val ROLE_SENDER = 1
    const val ROLE_RECEIVER = 2

    /** v3 默认中转地址（用户 EC2，经 Caddy 反代） */
    const val DEFAULT_RELAY_URL = "https://xsmlink.duckdns.org/sms-relay"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getTelegramToken(c: Context): String =
        prefs(c).getString("tg_token", "") ?: ""

    fun setTelegramToken(c: Context, v: String) {
        prefs(c).edit().putString("tg_token", v).apply()
    }

    fun getTelegramChatId(c: Context): String =
        prefs(c).getString("tg_chat_id", "") ?: ""

    fun setTelegramChatId(c: Context, v: String) {
        prefs(c).edit().putString("tg_chat_id", v).apply()
    }

    fun getForwardTarget(c: Context): Int =
        prefs(c).getInt("forward_target", TARGET_TELEGRAM)

    fun setForwardTarget(c: Context, v: Int) {
        prefs(c).edit().putInt("forward_target", v).apply()
    }

    // ---- 微信 ClawBot（经由用户自己的 OpenClaw 网关）----

    /** OpenClaw 网关地址，例如 http://192.168.1.10:18789 */
    fun getOcBaseUrl(c: Context): String =
        prefs(c).getString("oc_base_url", "") ?: ""

    fun setOcBaseUrl(c: Context, v: String) {
        prefs(c).edit().putString("oc_base_url", v).apply()
    }

    /** 网关 API Token（OpenClaw 配置里的 gateway token） */
    fun getOcToken(c: Context): String =
        prefs(c).getString("oc_token", "") ?: ""

    fun setOcToken(c: Context, v: String) {
        prefs(c).edit().putString("oc_token", v).apply()
    }

    /** 消息发送路径，默认 /rpc（JSON-RPC message.send）；网关版本不同可自行修改 */
    fun getOcSendPath(c: Context): String =
        prefs(c).getString("oc_send_path", "/rpc") ?: "/rpc"

    fun setOcSendPath(c: Context, v: String) {
        prefs(c).edit().putString("oc_send_path", v).apply()
    }

    /** 微信目标 ID（用 openclaw message channel list / channels resolve 查得） */
    fun getWechatTarget(c: Context): String =
        prefs(c).getString("wechat_target", "") ?: ""

    fun setWechatTarget(c: Context, v: String) {
        prefs(c).edit().putString("wechat_target", v).apply()
    }

    // ---- v3：设备角色 ----

    fun getRole(c: Context): Int =
        prefs(c).getInt("role", ROLE_UNSET)

    fun setRole(c: Context, v: Int) {
        prefs(c).edit().putInt("role", v).apply()
    }

    // ---- v3：直连中转（EC2 relay）----

    fun getRelayUrl(c: Context): String =
        prefs(c).getString("relay_url", DEFAULT_RELAY_URL) ?: DEFAULT_RELAY_URL

    fun setRelayUrl(c: Context, v: String) {
        prefs(c).edit().putString("relay_url", v).apply()
    }

    fun getRelayToken(c: Context): String =
        prefs(c).getString("relay_token", "") ?: ""

    fun setRelayToken(c: Context, v: String) {
        prefs(c).edit().putString("relay_token", v).apply()
    }

    /** 发送端设备名，方便在接收端区分多台发送机 */
    fun getDeviceName(c: Context): String =
        prefs(c).getString("device_name", android.os.Build.MODEL ?: "sender")
            ?: "sender"

    fun setDeviceName(c: Context, v: String) {
        prefs(c).edit().putString("device_name", v).apply()
    }

    /** 接收端轮询游标：已处理的最大消息 id */
    fun getLastRelayId(c: Context): Long =
        prefs(c).getLong("last_relay_id", 0L)

    fun setLastRelayId(c: Context, v: Long) {
        prefs(c).edit().putLong("last_relay_id", v).apply()
    }

    /** 接收服务是否处于运行状态（用于开机自启恢复） */
    fun isReceiverRunning(c: Context): Boolean =
        prefs(c).getBoolean("receiver_running", false)

    fun setReceiverRunning(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean("receiver_running", v).apply()
    }

    // ---- v3：后台隐藏（GKD 式开关：在「最近任务」隐藏卡片，防手滑划掉）----

    /** 默认开启：跟 GKD 的「后台隐藏」开关同款行为 */
    fun isHideFromRecents(c: Context): Boolean =
        prefs(c).getBoolean("hide_recents", true)

    fun setHideFromRecents(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean("hide_recents", v).apply()
    }

    /**
     * 运行时动态控制当前任务是否出现在「最近任务」里
     *（manifest 写死就做不成开关了，所以用 AppTask.setExcludeFromRecents）。
     * 每个 Activity 的 onCreate 里调一次即可。
     */
    fun applyHideFromRecents(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        try {
            val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val hide = isHideFromRecents(activity)
            am.appTasks.forEach { it.setExcludeFromRecents(hide) }
        } catch (_: Exception) {
        }
    }

    /**
     * v3 升级迁移：微信通道已折叠停用，老用户若之前选的是微信/两者，
     * 自动切到直连中转（网关配置原样保留，以后可恢复）。
     */
    fun migrateWechatTargetIfNeeded(c: Context): Boolean {
        val t = getForwardTarget(c)
        return if (t == TARGET_WECHAT || t == TARGET_BOTH) {
            setForwardTarget(c, TARGET_RELAY)
            true
        } else {
            false
        }
    }

    // ---- Hachimi AI ----

    fun getHachimiKey(c: Context): String =
        prefs(c).getString("hachimi_key", "") ?: ""

    fun setHachimiKey(c: Context, v: String) {
        prefs(c).edit().putString("hachimi_key", v).apply()
    }

    fun getHachimiModel(c: Context): String =
        prefs(c).getString("hachimi_model", "gpt-6-astra") ?: "gpt-6-astra"

    fun setHachimiModel(c: Context, v: String) {
        prefs(c).edit().putString("hachimi_model", v).apply()
    }

    fun isAiEnabled(c: Context): Boolean =
        prefs(c).getBoolean("ai_enabled", false)

    fun setAiEnabled(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean("ai_enabled", v).apply()
    }

    fun getKeywords(c: Context): String =
        prefs(c).getString("keywords", "") ?: ""

    fun setKeywords(c: Context, v: String) {
        prefs(c).edit().putString("keywords", v).apply()
    }

    /** 逗号/顿号分隔的关键词列表，留空表示不过滤 */
    fun getKeywordList(c: Context): List<String> =
        getKeywords(c).split("[,，、]".toRegex())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
