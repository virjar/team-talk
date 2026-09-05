package com.virjar.tk.protocol.model

/**
 * 认证参数校验规则（客户端与服务端共用，避免规则不一致导致请求到服务端才失败）。
 *
 * 规则属于客户端与服务端共同执行的契约，不属于任一端实现。
 */
object AuthRules {

    /** 用户名最小长度 */
    const val USERNAME_MIN_LENGTH = 3

    /** 用户名最大长度 */
    const val USERNAME_MAX_LENGTH = 50

    /** 密码最小长度 */
    const val PASSWORD_MIN_LENGTH = 6

    /** BCrypt 只区分前 72 个 UTF-8 字节；更长输入会产生不可区分的凭据。 */
    const val PASSWORD_MAX_UTF8_BYTES = 72

    /** 人类账号显示名最大长度。 */
    const val DISPLAY_NAME_MAX_LENGTH = 100

    /** 安装级设备标识最大长度。 */
    const val DEVICE_ID_MAX_LENGTH = 100

    /** 设备名和型号的最大长度。 */
    const val DEVICE_METADATA_MAX_LENGTH = 200

    const val DEVICE_FLAG_UNKNOWN = 0
    const val DEVICE_FLAG_ANDROID = 1
    const val DEVICE_FLAG_DESKTOP = 2

    /**
     * 校验用户名。返回 null 表示合法，否则返回错误原因（中文，可直接展示给用户）。
     */
    fun validateUsername(username: String): String? = when {
        username.isBlank() -> "用户名不能为空"
        username.length < USERNAME_MIN_LENGTH -> "用户名长度不能少于${USERNAME_MIN_LENGTH}位"
        username.length > USERNAME_MAX_LENGTH -> "用户名长度不能超过${USERNAME_MAX_LENGTH}位"
        username.contains('\u0000') -> "用户名包含不支持的字符"
        else -> null
    }

    /**
     * 校验密码。返回 null 表示合法，否则返回错误原因。
     */
    fun validatePassword(password: String): String? = when {
        password.isBlank() -> "密码不能为空"
        password.length < PASSWORD_MIN_LENGTH -> "密码长度不能少于${PASSWORD_MIN_LENGTH}位"
        password.encodeToByteArray().size > PASSWORD_MAX_UTF8_BYTES ->
            "密码 UTF-8 编码后不能超过${PASSWORD_MAX_UTF8_BYTES}字节"
        else -> null
    }

    /** 显示名是注册身份的必填字段。 */
    fun validateDisplayName(displayName: String): String? = when {
        displayName.isBlank() -> "显示名不能为空"
        displayName.length > DISPLAY_NAME_MAX_LENGTH ->
            "显示名不能超过${DISPLAY_NAME_MAX_LENGTH}个字符"
        displayName.contains('\u0000') -> "显示名包含不支持的字符"
        else -> null
    }

    /**
     * 设备标识同时用于凭据绑定和受控的日志路径分段，因此不接受空白、
     * 点路径或其他路径字符。
     */
    fun validateDeviceId(deviceId: String): String? = when {
        deviceId.isBlank() -> "设备标识不能为空"
        deviceId.length > DEVICE_ID_MAX_LENGTH ->
            "设备标识不能超过${DEVICE_ID_MAX_LENGTH}个字符"
        deviceId == "." || deviceId == ".." -> "设备标识不合法"
        deviceId.any {
            it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' &&
                it != '-' && it != '_' && it != '.'
        } ->
            "设备标识不合法"
        else -> null
    }

    /**
     * 设备名和型号在 wire/持久层均可缺省；一旦提供就必须符合持久层上限，
     * 且不能包含 PostgreSQL text/varchar 无法表示的 NUL。
     */
    fun validateDeviceName(deviceName: String?): String? = when {
        deviceName != null && deviceName.length > DEVICE_METADATA_MAX_LENGTH ->
            "设备名不能超过${DEVICE_METADATA_MAX_LENGTH}个字符"
        deviceName?.contains('\u0000') == true -> "设备名包含不支持的字符"
        else -> null
    }

    fun validateDeviceModel(deviceModel: String?): String? = when {
        deviceModel != null && deviceModel.length > DEVICE_METADATA_MAX_LENGTH ->
            "设备型号不能超过${DEVICE_METADATA_MAX_LENGTH}个字符"
        deviceModel?.contains('\u0000') == true -> "设备型号包含不支持的字符"
        else -> null
    }

    /** 0=未知/SDK，1=Android，2=Desktop。 */
    fun validateDeviceFlag(deviceFlag: Int): String? = when (deviceFlag) {
        DEVICE_FLAG_UNKNOWN, DEVICE_FLAG_ANDROID, DEVICE_FLAG_DESKTOP -> null
        else -> "设备类型不合法"
    }

    /**
     * 校验完整的人类账号注册字段。任一不合法即抛 [IllegalArgumentException]。
     */
    fun validateRegister(username: String, password: String, displayName: String) {
        validateUsername(username)?.let { throw IllegalArgumentException(it) }
        validatePassword(password)?.let { throw IllegalArgumentException(it) }
        validateDisplayName(displayName)?.let { throw IllegalArgumentException(it) }
    }

    /**
     * 校验登录参数（用户名 + 密码）。任一不合法即抛 [IllegalArgumentException]。
     */
    fun validateLogin(username: String, password: String) {
        validateUsername(username)?.let { throw IllegalArgumentException(it) }
        validatePassword(password)?.let { throw IllegalArgumentException(it) }
    }

    /** 校验每种认证方式共用的设备声明。 */
    fun validateDevice(
        deviceId: String,
        deviceName: String?,
        deviceModel: String?,
        deviceFlag: Int,
    ) {
        validateDeviceId(deviceId)?.let { throw IllegalArgumentException(it) }
        validateDeviceName(deviceName)?.let { throw IllegalArgumentException(it) }
        validateDeviceModel(deviceModel)?.let { throw IllegalArgumentException(it) }
        validateDeviceFlag(deviceFlag)?.let { throw IllegalArgumentException(it) }
    }
}
