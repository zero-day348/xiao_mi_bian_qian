/*
 * Copyright (c) 2010-2011, The MiCode Open Source Community (www.micode.net)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.micode.notes.model;

import android.appwidget.AppWidgetManager;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.text.TextUtils;
import android.util.Log;

import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.CallNote;
import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.DataConstants;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.data.Notes.TextNote;
import net.micode.notes.tool.ResourceParser.NoteBgResources;


public class WorkingNote {
    // WorkingNote 是编辑会话模型：承接 UI 输入、维护编辑状态，并驱动 Note 落库与组件联动。
    // Note for the working note
    // 实际的“差量写入器”，负责把当前会话改动同步到 ContentProvider。
    private Note mNote;
    // Note Id
    // >0 表示数据库中已存在；=0 表示仅在内存中（未首次保存）。
    private long mNoteId;
    // Note content
    // 当前编辑态正文缓存，UI 修改先写这里，再按需落库。
    private String mContent;
    // Note mode
    // 0:普通文本；1:清单模式（对应 TextNote.MODE_CHECK_LIST）。
    private int mMode;

    // 提醒时间戳（毫秒）；0 表示未设置提醒。
    private long mAlertDate;

    // 最近修改时间（用于列表显示与排序）。
    private long mModifiedDate;

    // 背景色业务 id，最终映射到具体资源由 NoteBgResources 处理。
    private int mBgColorId;

    // 绑定的小组件 id，未绑定时通常为 INVALID_APPWIDGET_ID。
    private int mWidgetId;

    // 小组件规格（2x/4x），用于决定刷新目标 Provider。
    private int mWidgetType;

    // 所属目录 id：根目录/系统目录/用户目录。
    private long mFolderId;

    private Context mContext;

    private static final String TAG = "WorkingNote";

    private boolean mIsDeleted;

    private NoteSettingChangedListener mNoteSettingStatusListener;

    public static final String[] DATA_PROJECTION = new String[] {
            // data 行主键（用于后续 update 定位）
            DataColumns.ID,
            // 正文文本
            DataColumns.CONTENT,
            // MIME 类型（区分文本便签/通话便签）
            DataColumns.MIME_TYPE,
            DataColumns.DATA1,
            DataColumns.DATA2,
            DataColumns.DATA3,
            DataColumns.DATA4,
    };

    public static final String[] NOTE_PROJECTION = new String[] {
            // 所属目录
            NoteColumns.PARENT_ID,
            // 提醒时间
            NoteColumns.ALERTED_DATE,
            // 背景色
            NoteColumns.BG_COLOR_ID,
            NoteColumns.WIDGET_ID,
            NoteColumns.WIDGET_TYPE,
            NoteColumns.MODIFIED_DATE
    };

    private static final int DATA_ID_COLUMN = 0;

    private static final int DATA_CONTENT_COLUMN = 1;

    private static final int DATA_MIME_TYPE_COLUMN = 2;

    private static final int DATA_MODE_COLUMN = 3;
    // 说明：DATA1 在 TextNote 场景下被复用为“清单模式标记”列。

    private static final int NOTE_PARENT_ID_COLUMN = 0;

    private static final int NOTE_ALERTED_DATE_COLUMN = 1;

    private static final int NOTE_BG_COLOR_ID_COLUMN = 2;

    private static final int NOTE_WIDGET_ID_COLUMN = 3;

    private static final int NOTE_WIDGET_TYPE_COLUMN = 4;

    private static final int NOTE_MODIFIED_DATE_COLUMN = 5;

    // New note construct
    private WorkingNote(Context context, long folderId) {
        // 新建场景：没有 noteId，直到首次保存才在数据库中落地。
        mContext = context;
        mAlertDate = 0;
        mModifiedDate = System.currentTimeMillis();
        mFolderId = folderId;
        mNote = new Note();
        mNoteId = 0;
        mIsDeleted = false;
        mMode = 0;
        mWidgetType = Notes.TYPE_WIDGET_INVALIDE;
    }

    // Existing note construct
    private WorkingNote(Context context, long noteId, long folderId) {
        // 加载场景：构造后立即从数据库回填当前会话状态。
        // folderId 参数在该路径下会被数据库真实值覆盖，调用方无需先验保证。
        mContext = context;
        mNoteId = noteId;
        mFolderId = folderId;
        mIsDeleted = false;
        mNote = new Note();
        loadNote();
    }

    private void loadNote() {
        // 先读取 note 元数据（文件夹/提醒/背景/组件），再读取 data 正文，保持对象状态完整。
        Cursor cursor = mContext.getContentResolver().query(
                ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, mNoteId), NOTE_PROJECTION, null,
                null, null);

        if (cursor != null) {
            if (cursor.moveToFirst()) {
                // 这里读取的是会影响编辑页 UI 的核心元字段。
                mFolderId = cursor.getLong(NOTE_PARENT_ID_COLUMN);
                mBgColorId = cursor.getInt(NOTE_BG_COLOR_ID_COLUMN);
                mWidgetId = cursor.getInt(NOTE_WIDGET_ID_COLUMN);
                mWidgetType = cursor.getInt(NOTE_WIDGET_TYPE_COLUMN);
                mAlertDate = cursor.getLong(NOTE_ALERTED_DATE_COLUMN);
                mModifiedDate = cursor.getLong(NOTE_MODIFIED_DATE_COLUMN);
            }
            cursor.close();
        } else {
            Log.e(TAG, "No note with id:" + mNoteId);
            throw new IllegalArgumentException("Unable to find note with id " + mNoteId);
        }
        loadNoteData();
        // 注意：即使 note 主记录存在，正文 data 仍可能为空（如异常中断创建流程）。
    }

    private void loadNoteData() {
        // 一个 note 可挂多条 data：常见是正文，也可能附带通话记录。
        Cursor cursor = mContext.getContentResolver().query(Notes.CONTENT_DATA_URI, DATA_PROJECTION,
                DataColumns.NOTE_ID + "=?", new String[] {
                    String.valueOf(mNoteId)
                }, null);

        if (cursor != null) {
            if (cursor.moveToFirst()) {
                do {
                    String type = cursor.getString(DATA_MIME_TYPE_COLUMN);
                    // 按 MIME 类型分派解析逻辑，保证未来扩展新数据类型时可平滑兼容。
                    if (DataConstants.NOTE.equals(type)) {
                        // 文本数据：正文 + 清单模式标记。
                        mContent = cursor.getString(DATA_CONTENT_COLUMN);
                        mMode = cursor.getInt(DATA_MODE_COLUMN);
                        mNote.setTextDataId(cursor.getLong(DATA_ID_COLUMN));
                    } else if (DataConstants.CALL_NOTE.equals(type)) {
                        // 通话数据：这里只记录 dataId，实际字段由 NoteData 管理。
                        mNote.setCallDataId(cursor.getLong(DATA_ID_COLUMN));
                    } else {
                        // 保守处理未知类型：记录日志但不中断整条笔记加载。
                        Log.d(TAG, "Wrong note type with type:" + type);
                    }
                } while (cursor.moveToNext());
            }
            cursor.close();
        } else {
            Log.e(TAG, "No data with id:" + mNoteId);
            throw new IllegalArgumentException("Unable to find note's data with id " + mNoteId);
        }
    }

    public static WorkingNote createEmptyNote(Context context, long folderId, int widgetId,
            int widgetType, int defaultBgColorId) {
        // 新建便签工厂：把 UI 入口参数（目录/组件/默认背景）一次性注入。
        WorkingNote note = new WorkingNote(context, folderId);
        note.setBgColorId(defaultBgColorId);
        note.setWidgetId(widgetId);
        note.setWidgetType(widgetType);
        return note;
    }

    public static WorkingNote load(Context context, long id) {
        // 外部统一入口：根据 noteId 构造已存在笔记的工作副本。
        return new WorkingNote(context, id, 0);
    }

    public synchronized boolean saveNote() {
        // 保存前先判定“是否值得保存”，避免空白草稿与无改动笔记造成无意义写库。
        if (isWorthSaving()) {
            if (!existInDatabase()) {
                // 首次保存先创建 note 主记录，再同步正文与附加数据。
                if ((mNoteId = Note.getNewNoteId(mContext, mFolderId)) == 0) {
                    Log.e(TAG, "Create new note fail with id:" + mNoteId);
                    return false;
                }
            }

            mNote.syncNote(mContext, mNoteId);
            // 注意：syncNote 内部会处理 note/data 两层差量提交。
            // 该方法默认信任 syncNote 的内部幂等性，不再重复校验字段级变更。

            /**
             * Update widget content if there exist any widget of this note
             */
            if (mWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID
                    && mWidgetType != Notes.TYPE_WIDGET_INVALIDE
                    && mNoteSettingStatusListener != null) {
                // 仅在已绑定组件且监听器可用时触发刷新，避免无意义回调。
                mNoteSettingStatusListener.onWidgetChanged();
            }
            return true;
        } else {
            return false;
        }
    }

    public boolean existInDatabase() {
        // 统一存在性判断，便于 UI 与业务层共享同一语义。
        return mNoteId > 0;
    }

    private boolean isWorthSaving() {
        // 不保存场景：
        // - 已标记删除；
        // - 新建且正文为空；
        // - 已存在但本地无改动。
        if (mIsDeleted || (!existInDatabase() && TextUtils.isEmpty(mContent))
                || (existInDatabase() && !mNote.isLocalModified())) {
            return false;
        } else {
            return true;
        }
    }

    public void setOnSettingStatusChangedListener(NoteSettingChangedListener l) {
        // UI 回调监听器：把模型状态变化反推给界面（提醒、背景、组件等）。
        mNoteSettingStatusListener = l;
    }

    public void setAlertDate(long date, boolean set) {
        // set 参数用于区分“设置提醒”和“取消提醒”，供 UI 层同步更新闹钟状态。
        if (date != mAlertDate) {
            mAlertDate = date;
            mNote.setNoteValue(NoteColumns.ALERTED_DATE, String.valueOf(mAlertDate));
        }
        if (mNoteSettingStatusListener != null) {
            mNoteSettingStatusListener.onClockAlertChanged(date, set);
        }
    }

    public void markDeleted(boolean mark) {
        // 标记删除不等于立刻删库，是否真正删除由上层业务流决定。
        mIsDeleted = mark;
        if (mWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID
                && mWidgetType != Notes.TYPE_WIDGET_INVALIDE && mNoteSettingStatusListener != null) {
                mNoteSettingStatusListener.onWidgetChanged();
        }
    }

    public void setBgColorId(int id) {
        // 背景色变化属于 note 元数据变更，不需要改动 data 表。
        if (id != mBgColorId) {
            mBgColorId = id;
            if (mNoteSettingStatusListener != null) {
                mNoteSettingStatusListener.onBackgroundColorChanged();
            }
            mNote.setNoteValue(NoteColumns.BG_COLOR_ID, String.valueOf(id));
        }
    }

    public void setCheckListMode(int mode) {
        // 清单模式变更同时通知 UI 与持久层字段（TextNote.MODE）。
        if (mMode != mode) {
            if (mNoteSettingStatusListener != null) {
                mNoteSettingStatusListener.onCheckListModeChanged(mMode, mode);
            }
            mMode = mode;
            mNote.setTextData(TextNote.MODE, String.valueOf(mMode));
        }
    }

    public void setWidgetType(int type) {
        // 仅记录组件类型，不直接触发刷新，刷新由保存/删除流程统一处理。
        if (type != mWidgetType) {
            mWidgetType = type;
            mNote.setNoteValue(NoteColumns.WIDGET_TYPE, String.valueOf(mWidgetType));
        }
    }

    public void setWidgetId(int id) {
        // widgetId 常在“从组件创建便签”或“组件绑定笔记”时被设置。
        if (id != mWidgetId) {
            mWidgetId = id;
            mNote.setNoteValue(NoteColumns.WIDGET_ID, String.valueOf(mWidgetId));
        }
    }

    public void setWorkingText(String text) {
        // 文本相同不重复写差量，减少不必要的 update。
        if (!TextUtils.equals(mContent, text)) {
            mContent = text;
            mNote.setTextData(DataColumns.CONTENT, mContent);
        }
    }

    public void convertToCallNote(String phoneNumber, long callDate) {
        // 通话便签会写入 call 数据列，并强制归档到系统“通话记录目录”。
        mNote.setCallData(CallNote.CALL_DATE, String.valueOf(callDate));
        mNote.setCallData(CallNote.PHONE_NUMBER, phoneNumber);
        mNote.setNoteValue(NoteColumns.PARENT_ID, String.valueOf(Notes.ID_CALL_RECORD_FOLDER));
    }

    public boolean hasClockAlert() {
        // 约定：alertDate>0 表示已设置提醒。
        return (mAlertDate > 0 ? true : false);
    }

    public String getContent() {
        // 返回当前会话正文（不保证已持久化）。
        return mContent;
    }

    public long getAlertDate() {
        return mAlertDate;
    }

    public long getModifiedDate() {
        return mModifiedDate;
    }

    public int getBgColorResId() {
        // 将业务色值转换为编辑页正文背景资源 id。
        return NoteBgResources.getNoteBgResource(mBgColorId);
    }

    public int getBgColorId() {
        return mBgColorId;
    }

    public int getTitleBgResId() {
        // 将业务色值转换为标题栏背景资源 id。
        return NoteBgResources.getNoteTitleBgResource(mBgColorId);
    }

    public int getCheckListMode() {
        return mMode;
    }

    public long getNoteId() {
        return mNoteId;
    }

    public long getFolderId() {
        return mFolderId;
    }

    public int getWidgetId() {
        return mWidgetId;
    }

    public int getWidgetType() {
        return mWidgetType;
    }

    public interface NoteSettingChangedListener {
        /**
         * Called when the background color of current note has just changed
         */
        void onBackgroundColorChanged();

        /**
         * Called when user set clock
         */
        void onClockAlertChanged(long date, boolean set);

        /**
         * Call when user create note from widget
         */
        void onWidgetChanged();

        /**
         * Call when switch between check list mode and normal mode
         * @param oldMode is previous mode before change
         * @param newMode is new mode
         */
        void onCheckListModeChanged(int oldMode, int newMode);
    }
}
