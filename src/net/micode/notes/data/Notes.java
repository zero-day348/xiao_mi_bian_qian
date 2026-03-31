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

package net.micode.notes.data;

import android.net.Uri;
public class Notes {
    // 统一数据契约：集中维护 URI、表列名、MIME 类型与业务常量，避免硬编码散落在各层。
    public static final String AUTHORITY = "micode_notes";
    // ContentProvider authority，需与 Manifest 中 provider 配置保持一致。
    public static final String TAG = "Notes";
    public static final int TYPE_NOTE     = 0;
    // 普通笔记节点。
    public static final int TYPE_FOLDER   = 1;
    // 用户目录节点。
    public static final int TYPE_SYSTEM   = 2;
    // 系统目录节点（如回收站/通话目录）。

    /**
     * Following IDs are system folders' identifiers
     * {@link Notes#ID_ROOT_FOLDER } is default folder
     * {@link Notes#ID_TEMPARAY_FOLDER } is for notes belonging no folder
     * {@link Notes#ID_CALL_RECORD_FOLDER} is to store call records
     */
    public static final int ID_ROOT_FOLDER = 0;
    // 临时目录：用于移动过程中的中间态承载。
    public static final int ID_TEMPARAY_FOLDER = -1;
    // 通话目录：系统保留目录，承载来电自动生成便签。
    public static final int ID_CALL_RECORD_FOLDER = -2;
    // 回收站目录：同步模式下删除操作通常转为“移动到该目录”。
    public static final int ID_TRASH_FOLER = -3;

    public static final String INTENT_EXTRA_ALERT_DATE = "net.micode.notes.alert_date";
    // 提醒时间参数（毫秒时间戳）。
    public static final String INTENT_EXTRA_BACKGROUND_ID = "net.micode.notes.background_color_id";
    // 编辑页初始背景色 id。
    public static final String INTENT_EXTRA_WIDGET_ID = "net.micode.notes.widget_id";
    // 小组件实例 id。
    public static final String INTENT_EXTRA_WIDGET_TYPE = "net.micode.notes.widget_type";
    // 小组件规格类型（2x/4x）。
    public static final String INTENT_EXTRA_FOLDER_ID = "net.micode.notes.folder_id";
    // 新建笔记目标目录 id。
    public static final String INTENT_EXTRA_CALL_DATE = "net.micode.notes.call_date";
    // 通话记录时间（用于通话便签去重与回填）。

    public static final int TYPE_WIDGET_INVALIDE      = -1;
    // 无效/未绑定组件类型。
    public static final int TYPE_WIDGET_2X            = 0;
    // 2x 小组件。
    public static final int TYPE_WIDGET_4X            = 1;
    // 4x 小组件。

    public static class DataConstants {
        // 文本数据 MIME
        public static final String NOTE = TextNote.CONTENT_ITEM_TYPE;
        // 通话数据 MIME
        public static final String CALL_NOTE = CallNote.CONTENT_ITEM_TYPE;
        // 该常量组主要用于 data 表解析分支（按 MIME 决定字段解释方式）。
    }

    /**
     * Uri to query all notes and folders
     */
    public static final Uri CONTENT_NOTE_URI = Uri.parse("content://" + AUTHORITY + "/note");
    // 对应 Provider 路由：note / note/#。

    /**
     * Uri to query data
     */
    public static final Uri CONTENT_DATA_URI = Uri.parse("content://" + AUTHORITY + "/data");
    // 对应 Provider 路由：data / data/#。

    public interface NoteColumns {
        // note 主表字段：描述笔记/目录本身的元信息（层级、样式、同步状态、版本等）。
        /**
         * The unique ID for a row
         * <P> Type: INTEGER (long) </P>
         */
        public static final String ID = "_id";
        // 主键：Provider item 路由与列表适配器稳定 id 依赖该字段。

        /**
         * The parent's id for note or folder
         * <P> Type: INTEGER (long) </P>
         */
        public static final String PARENT_ID = "parent_id";
        // 父目录 id：构成目录树结构的核心引用字段。

        /**
         * Created data for note or folder
         * <P> Type: INTEGER (long) </P>
         */
        public static final String CREATED_DATE = "created_date";
        // 创建时间（毫秒）：通常用于历史展示或审计。

        /**
         * Latest modified date
         * <P> Type: INTEGER (long) </P>
         */
        public static final String MODIFIED_DATE = "modified_date";
        // 修改时间（毫秒）：列表排序默认优先依据该字段。


        /**
         * Alert date
         * <P> Type: INTEGER (long) </P>
         */
        public static final String ALERTED_DATE = "alert_date";
        // 提醒触发时间（毫秒），0 表示无提醒。

        /**
         * Folder's name or text content of note
         * <P> Type: TEXT </P>
         */
        public static final String SNIPPET = "snippet";
        // 列表摘要：通常由 data 触发器从正文同步写回。

        /**
         * Note's widget id
         * <P> Type: INTEGER (long) </P>
         */
        public static final String WIDGET_ID = "widget_id";
        // 绑定的小组件实例 id，未绑定时一般为无效值。

        /**
         * Note's widget type
         * <P> Type: INTEGER (long) </P>
         */
        public static final String WIDGET_TYPE = "widget_type";
        // 组件规格类型，决定刷新目标 Provider（2x/4x）。

        /**
         * Note's background color's id
         * <P> Type: INTEGER (long) </P>
         */
        public static final String BG_COLOR_ID = "bg_color_id";
        // 业务背景色 id，UI 层再映射为具体资源。

        /**
         * For text note, it doesn't has attachment, for multi-media
         * note, it has at least one attachment
         * <P> Type: INTEGER </P>
         */
        public static final String HAS_ATTACHMENT = "has_attachment";
        // 是否含附件的标记位（0/1）。

        /**
         * Folder's count of notes
         * <P> Type: INTEGER (long) </P>
         */
        public static final String NOTES_COUNT = "notes_count";
        // 目录直属子项计数，通常由触发器自动维护。

        /**
         * The file type: folder or note
         * <P> Type: INTEGER </P>
         */
        public static final String TYPE = "type";
        // 节点类型：note/folder/system。

        /**
         * The last sync id
         * <P> Type: INTEGER (long) </P>
         */
        public static final String SYNC_ID = "sync_id";
        // 同步序号/标识，供云同步流程定位增量状态。

        /**
         * Sign to indicate local modified or not
         * <P> Type: INTEGER </P>
         */
        public static final String LOCAL_MODIFIED = "local_modified";
        // 本地是否改动标记，驱动同步模块上传逻辑。

        /**
         * Original parent id before moving into temporary folder
         * <P> Type : INTEGER </P>
         */
        public static final String ORIGIN_PARENT_ID = "origin_parent_id";
        // 移动过程中的原父目录记录，用于回滚/恢复等场景。

        /**
         * The gtask id
         * <P> Type : TEXT </P>
         */
        public static final String GTASK_ID = "gtask_id";
        // 远端任务 id（如 Google Tasks 对应实体）。

        /**
         * The version code
         * <P> Type : INTEGER (long) </P>
         */
        public static final String VERSION = "version";
        // 版本号：Provider 更新 note 时递增，用于冲突检测。
    }

    public interface DataColumns {
        // data 子表字段：描述笔记内容实体，配合 MIME 类型承载不同结构的数据。
        /**
         * The unique ID for a row
         * <P> Type: INTEGER (long) </P>
         */
        public static final String ID = "_id";
        // data 行主键。

        /**
         * The MIME type of the item represented by this row.
         * <P> Type: Text </P>
         */
        public static final String MIME_TYPE = "mime_type";
        // 数据类型分发器：决定 DATA1~DATA5 的解释语义。

        /**
         * The reference id to note that this data belongs to
         * <P> Type: INTEGER (long) </P>
         */
        public static final String NOTE_ID = "note_id";
        // 所属 note 外键引用。

        /**
         * Created data for note or folder
         * <P> Type: INTEGER (long) </P>
         */
        public static final String CREATED_DATE = "created_date";
        // data 创建时间（毫秒）。

        /**
         * Latest modified date
         * <P> Type: INTEGER (long) </P>
         */
        public static final String MODIFIED_DATE = "modified_date";
        // data 修改时间（毫秒）。

        /**
         * Data's content
         * <P> Type: TEXT </P>
         */
        public static final String CONTENT = "content";
        // 主内容字段：文本便签正文通常落在此列。


        /**
         * Generic data column, the meaning is {@link #MIMETYPE} specific, used for
         * integer data type
         * <P> Type: INTEGER </P>
         */
        public static final String DATA1 = "data1";
        // 通用扩展列1：TextNote 用作 MODE，CallNote 用作 CALL_DATE。

        /**
         * Generic data column, the meaning is {@link #MIMETYPE} specific, used for
         * integer data type
         * <P> Type: INTEGER </P>
         */
        public static final String DATA2 = "data2";
        // 通用扩展列2：预留给整数扩展信息。

        /**
         * Generic data column, the meaning is {@link #MIMETYPE} specific, used for
         * TEXT data type
         * <P> Type: TEXT </P>
         */
        public static final String DATA3 = "data3";
        // 通用扩展列3：CallNote 用作 PHONE_NUMBER。

        /**
         * Generic data column, the meaning is {@link #MIMETYPE} specific, used for
         * TEXT data type
         * <P> Type: TEXT </P>
         */
        public static final String DATA4 = "data4";
        // 通用扩展列4：文本扩展预留。

        /**
         * Generic data column, the meaning is {@link #MIMETYPE} specific, used for
         * TEXT data type
         * <P> Type: TEXT </P>
         */
        public static final String DATA5 = "data5";
        // 通用扩展列5：文本扩展预留。
    }

    public static final class TextNote implements DataColumns {
        // 文本便签类型定义：包括清单模式标记与对应 MIME、访问 URI。
        /**
         * Mode to indicate the text in check list mode or not
         * <P> Type: Integer 1:check list mode 0: normal mode </P>
         */
        public static final String MODE = DATA1;
        // 清单模式标记存储在 DATA1（1=清单，0=普通）。

        public static final int MODE_CHECK_LIST = 1;

        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/text_note";
        // 文本便签目录 MIME。

        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/text_note";
        // 文本便签单项 MIME。

        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/text_note");
        // 文本便签专用访问 URI（部分模块按类型直接查询）。
    }

    public static final class CallNote implements DataColumns {
        // 通话便签类型定义：复用 DATA1/DATA3 存放通话时间与号码。
        /**
         * Call date for this record
         * <P> Type: INTEGER (long) </P>
         */
        public static final String CALL_DATE = DATA1;
        // 通话发生时间（毫秒）。

        /**
         * Phone number for this record
         * <P> Type: TEXT </P>
         */
        public static final String PHONE_NUMBER = DATA3;
        // 来电/去电号码字符串。

        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/call_note";
        // 通话便签目录 MIME。

        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/call_note";
        // 通话便签单项 MIME。

        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/call_note");
        // 通话便签专用访问 URI。
    }
}
