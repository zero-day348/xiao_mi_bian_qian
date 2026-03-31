# 小米便签 Bug 报告

## Bug 1：提醒时间标题始终按 24 小时格式显示

- **Bug 位置**
  - `src/net/micode/notes/ui/DateTimePickerDialog.java`
  - 方法：`updateTitle(long date)`
- **Bug 是什么**
  - 即使系统处于 12 小时制，标题格式仍然强制使用 24 小时制。
- **出现原因**
  - 代码中三元表达式两边都写成了 `DateUtils.FORMAT_24HOUR`，导致条件分支失效。
- **修复方法**
  - 12 小时制分支应使用 `DateUtils.FORMAT_12HOUR`。
- **修复副本文件**
  - `src/net/micode/notes/ui/DateTimePickerDialogfixed.java`

---

## Bug 2：重启后多个提醒可能互相覆盖，仅最后一个生效

- **Bug 位置**
  - `src/net/micode/notes/ui/AlarmInitReceiver.java`
  - 方法：`onReceive(...)`
- **Bug 是什么**
  - 扫描并重建闹钟时，所有 `PendingIntent.getBroadcast(...)` 都使用同一个 requestCode（`0`），导致系统把它们识别为同一条 PendingIntent，前面的提醒被后面的覆盖。
- **出现原因**
  - PendingIntent 唯一性由 `requestCode + Intent 特征` 共同决定，统一使用 `0` 会冲突。
- **修复方法**
  - 使用 `noteId` 作为 requestCode，确保每条便签提醒唯一。
- **修复副本文件**
  - `src/net/micode/notes/ui/AlarmInitReceiverfixed.java`

---

## Bug 3：提醒铃声音频流类型设置错误，可能导致异常或音量行为异常

- **Bug 位置**
  - `src/net/micode/notes/ui/AlarmAlertActivity.java`
  - 方法：`playAlarmSound()`
- **Bug 是什么**
  - 代码将 `MODE_RINGER_STREAMS_AFFECTED` 的位掩码值直接传给 `MediaPlayer.setAudioStreamType(...)`，而该 API 需要的是具体流类型常量（如 `STREAM_ALARM`）。
- **出现原因**
  - 把“受静音影响的流集合位图”误当作“流类型枚举值”使用。
- **修复方法**
  - 固定使用 `AudioManager.STREAM_ALARM` 作为播放流。
- **修复副本文件**
  - `src/net/micode/notes/ui/AlarmAlertActivityfixed.java`

