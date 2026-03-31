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


import android.app.SearchManager;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import net.micode.notes.R;
import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.data.NotesDatabaseHelper.TABLE;


public class NotesProvider extends ContentProvider {
    // 统一的数据访问入口：把外部 URI 请求分发到 note/data 两张表，并负责查询建议与变更通知。
    private static final UriMatcher mMatcher;

    private NotesDatabaseHelper mHelper;
    // 读写数据库统一通过 Helper 获取，避免直接管理连接生命周期。

    private static final String TAG = "NotesProvider";

    private static final int URI_NOTE            = 1;
    private static final int URI_NOTE_ITEM       = 2;
    private static final int URI_DATA            = 3;
    private static final int URI_DATA_ITEM       = 4;

    private static final int URI_SEARCH          = 5;
    private static final int URI_SEARCH_SUGGEST  = 6;
    // 搜索相关 URI 与普通 CRUD 分离，便于独立维护返回字段格式。

    static {
        // URI 路由表：将 content://micode_notes/... 映射为内部整型码，后续 CRUD 统一按码分支处理。
        mMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        mMatcher.addURI(Notes.AUTHORITY, "note", URI_NOTE);
        mMatcher.addURI(Notes.AUTHORITY, "note/#", URI_NOTE_ITEM);
        mMatcher.addURI(Notes.AUTHORITY, "data", URI_DATA);
        mMatcher.addURI(Notes.AUTHORITY, "data/#", URI_DATA_ITEM);
        mMatcher.addURI(Notes.AUTHORITY, "search", URI_SEARCH);
        mMatcher.addURI(Notes.AUTHORITY, SearchManager.SUGGEST_URI_PATH_QUERY, URI_SEARCH_SUGGEST);
        mMatcher.addURI(Notes.AUTHORITY, SearchManager.SUGGEST_URI_PATH_QUERY + "/*", URI_SEARCH_SUGGEST);
    }

    /**
     * x'0A' represents the '\n' character in sqlite. For title and content in the search result,
     * we will trim '\n' and white space in order to show more information.
     */
    private static final String NOTES_SEARCH_PROJECTION = NoteColumns.ID + ","
        // _id 同时作为建议列表主键与跳转所需 extra data。
        + NoteColumns.ID + " AS " + SearchManager.SUGGEST_COLUMN_INTENT_EXTRA_DATA + ","
        + "TRIM(REPLACE(" + NoteColumns.SNIPPET + ", x'0A','')) AS " + SearchManager.SUGGEST_COLUMN_TEXT_1 + ","
        + "TRIM(REPLACE(" + NoteColumns.SNIPPET + ", x'0A','')) AS " + SearchManager.SUGGEST_COLUMN_TEXT_2 + ","
        + R.drawable.search_result + " AS " + SearchManager.SUGGEST_COLUMN_ICON_1 + ","
        // 建议项点击动作统一走 ACTION_VIEW。
        + "'" + Intent.ACTION_VIEW + "' AS " + SearchManager.SUGGEST_COLUMN_INTENT_ACTION + ","
        + "'" + Notes.TextNote.CONTENT_TYPE + "' AS " + SearchManager.SUGGEST_COLUMN_INTENT_DATA;
        // intent data type 指向文本便签类型。

    private static String NOTES_SNIPPET_SEARCH_QUERY = "SELECT " + NOTES_SEARCH_PROJECTION
        // 只搜索可见笔记：排除回收站、排除非 note 类型，减少无效结果干扰。
        + " FROM " + TABLE.NOTE
        + " WHERE " + NoteColumns.SNIPPET + " LIKE ?"
        + " AND " + NoteColumns.PARENT_ID + "<>" + Notes.ID_TRASH_FOLER
        + " AND " + NoteColumns.TYPE + "=" + Notes.TYPE_NOTE;
        // 仅对 TYPE_NOTE 做全文片段搜索，目录名称不参与该查询。

    @Override
    public boolean onCreate() {
        // Provider 生命周期入口：初始化数据库帮助类，后续所有 CRUD 共用同一实例。
        mHelper = NotesDatabaseHelper.getInstance(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        // 查询流程：
        // 1) 解析 URI -> 目标表/目标行；
        // 2) 普通查询直接走 db.query；
        // 3) 搜索建议走自定义 SQL，返回 SearchManager 约定列。
        Cursor c = null;
        SQLiteDatabase db = mHelper.getReadableDatabase();
        String id = null;
        switch (mMatcher.match(uri)) {
            case URI_NOTE:
                // 查询 note 全集或按 selection 过滤。
                c = db.query(TABLE.NOTE, projection, selection, selectionArgs, null, null,
                        sortOrder);
                break;
            case URI_NOTE_ITEM:
                id = uri.getPathSegments().get(1);
                // item 级查询：先按主键命中，再追加调用方传入筛选条件。
                // path 约定：note/<id>，id 位于第 2 段。
                c = db.query(TABLE.NOTE, projection, NoteColumns.ID + "=" + id
                        + parseSelection(selection), selectionArgs, null, null, sortOrder);
                break;
            case URI_DATA:
                // 查询 data 全集或按 selection 过滤。
                c = db.query(TABLE.DATA, projection, selection, selectionArgs, null, null,
                        sortOrder);
                break;
            case URI_DATA_ITEM:
                id = uri.getPathSegments().get(1);
                // data item 查询与 note item 同理，避免重复实现两套 selection 逻辑。
                c = db.query(TABLE.DATA, projection, DataColumns.ID + "=" + id
                        + parseSelection(selection), selectionArgs, null, null, sortOrder);
                break;
            case URI_SEARCH:
            case URI_SEARCH_SUGGEST:
                // 搜索建议接口由系统调用，参数结构固定，不允许调用方自定义 projection/sort。
                if (sortOrder != null || projection != null) {
                    throw new IllegalArgumentException(
                            "do not specify sortOrder, selection, selectionArgs, or projection" + "with this query");
                }

                String searchString = null;
                if (mMatcher.match(uri) == URI_SEARCH_SUGGEST) {
                    if (uri.getPathSegments().size() > 1) {
                        // SUGGEST 路由下关键字来自 path 段。
                        searchString = uri.getPathSegments().get(1);
                    }
                } else {
                    // 普通 search 路由下关键字来自 query 参数 pattern。
                    searchString = uri.getQueryParameter("pattern");
                }

                if (TextUtils.isEmpty(searchString)) {
                    // 空关键字不执行检索，直接返回 null 避免全表扫描。
                    return null;
                }

                try {
                    // LIKE 模糊检索统一包裹 %keyword%，与搜索建议交互保持一致体验。
                    searchString = String.format("%%%s%%", searchString);
                    c = db.rawQuery(NOTES_SNIPPET_SEARCH_QUERY,
                            new String[] { searchString });
                } catch (IllegalStateException ex) {
                    // 数据库状态异常时记录日志并返回空结果，避免崩溃外抛。
                    Log.e(TAG, "got exception: " + ex.toString());
                }
                break;
            default:
                // 未注册 URI 一律拒绝，防止调用方误用接口。
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
        if (c != null) {
            // 注册通知 URI：数据变化时 Cursor 可感知刷新（配合 Loader/Adapter 使用）。
            c.setNotificationUri(getContext().getContentResolver(), uri);
        }
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        // 插入后会按影响范围发送 notifyChange：
        // - 新建 note 通知 note/<id>
        // - 新建 data 通知 data/<id>，并联动通知所属 note
        SQLiteDatabase db = mHelper.getWritableDatabase();
        long dataId = 0, noteId = 0, insertedId = 0;
        switch (mMatcher.match(uri)) {
            case URI_NOTE:
                // note 插入后 insertedId 同时可作为 noteId 使用。
                insertedId = noteId = db.insert(TABLE.NOTE, null, values);
                break;
            case URI_DATA:
                if (values.containsKey(DataColumns.NOTE_ID)) {
                    // data 记录必须有 note_id 才能回溯归属笔记。
                    noteId = values.getAsLong(DataColumns.NOTE_ID);
                } else {
                    // 非法入参仅记录日志，不中断插入流程（兼容历史调用）。
                    Log.d(TAG, "Wrong data format without note id:" + values.toString());
                }
                insertedId = dataId = db.insert(TABLE.DATA, null, values);
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
        // Notify the note uri
        if (noteId > 0) {
            // 精确通知 note/<id>，有利于局部刷新。
            getContext().getContentResolver().notifyChange(
                    ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, noteId), null);
        }

        // Notify the data uri
        if (dataId > 0) {
            // 精确通知 data/<id>，便于数据订阅方感知新增。
            getContext().getContentResolver().notifyChange(
                    ContentUris.withAppendedId(Notes.CONTENT_DATA_URI, dataId), null);
        }

        return ContentUris.withAppendedId(uri, insertedId);
        // 返回资源 URI：content://.../<table>/<newId>
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        // 删除策略：
        // - note/# 保护系统目录（id<=0）不被直接删除；
        // - data 删除后通知 note，确保列表摘要等派生信息及时刷新。
        int count = 0;
        String id = null;
        SQLiteDatabase db = mHelper.getWritableDatabase();
        boolean deleteData = false;
        switch (mMatcher.match(uri)) {
            case URI_NOTE:
                // 批量删除 note 时补充 id>0 保护，避免误删系统目录。
                selection = "(" + selection + ") AND " + NoteColumns.ID + ">0 ";
                count = db.delete(TABLE.NOTE, selection, selectionArgs);
                break;
            case URI_NOTE_ITEM:
                id = uri.getPathSegments().get(1);
                /**
                 * ID that smaller than 0 is system folder which is not allowed to
                 * trash
                 */
                long noteId = Long.valueOf(id);
                if (noteId <= 0) {
                    // 系统目录禁止从 item URI 直接删除，直接返回 0。
                    break;
                }
                count = db.delete(TABLE.NOTE,
                        NoteColumns.ID + "=" + id + parseSelection(selection), selectionArgs);
                break;
            case URI_DATA:
                // 删除 data 可能改变 note 摘要/展示，需要联动通知。
                count = db.delete(TABLE.DATA, selection, selectionArgs);
                deleteData = true;
                break;
            case URI_DATA_ITEM:
                id = uri.getPathSegments().get(1);
                // 单条 data 删除：主键 + 可选附加 selection 共同约束。
                count = db.delete(TABLE.DATA,
                        DataColumns.ID + "=" + id + parseSelection(selection), selectionArgs);
                deleteData = true;
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
        if (count > 0) {
            // 删除/更新成功后只在有影响时通知，避免无效刷新。
            if (deleteData) {
                // data 删除可能触发摘要变化，广播 note 全量 URI。
                getContext().getContentResolver().notifyChange(Notes.CONTENT_NOTE_URI, null);
            }
            getContext().getContentResolver().notifyChange(uri, null);
        }
        return count;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        // 更新 note 时先递增版本号（用于同步冲突判定）；更新 data 后联动通知 note 刷新。
        int count = 0;
        String id = null;
        SQLiteDatabase db = mHelper.getWritableDatabase();
        boolean updateData = false;
        switch (mMatcher.match(uri)) {
            case URI_NOTE:
                // 批量更新 note 前先统一 +1 version，供同步模块识别版本推进。
                increaseNoteVersion(-1, selection, selectionArgs);
                count = db.update(TABLE.NOTE, values, selection, selectionArgs);
                break;
            case URI_NOTE_ITEM:
                id = uri.getPathSegments().get(1);
                // 单条更新只递增该条 version。
                increaseNoteVersion(Long.valueOf(id), selection, selectionArgs);
                count = db.update(TABLE.NOTE, values, NoteColumns.ID + "=" + id
                        + parseSelection(selection), selectionArgs);
                break;
            case URI_DATA:
                // data 更新不走 version++，版本推进只针对 note 主表。
                count = db.update(TABLE.DATA, values, selection, selectionArgs);
                updateData = true;
                break;
            case URI_DATA_ITEM:
                id = uri.getPathSegments().get(1);
                count = db.update(TABLE.DATA, values, DataColumns.ID + "=" + id
                        + parseSelection(selection), selectionArgs);
                updateData = true;
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }

        if (count > 0) {
            // 更新 data 可能影响摘要、列表展示，因此额外通知 note URI。
            if (updateData) {
                getContext().getContentResolver().notifyChange(Notes.CONTENT_NOTE_URI, null);
            }
            getContext().getContentResolver().notifyChange(uri, null);
        }
        return count;
    }

    private String parseSelection(String selection) {
        // 把可选筛选条件拼成 "AND (...)" 片段，减少 URI_ITEM 分支里的重复字符串拼接。
        // 传入为空时返回空串，调用处可直接做字符串拼接。
        return (!TextUtils.isEmpty(selection) ? " AND (" + selection + ')' : "");
    }

    private void increaseNoteVersion(long id, String selection, String[] selectionArgs) {
        // 统一版本号递增入口：
        // - id>0 时更新单条；
        // - 否则按 selection 批量更新。
        StringBuilder sql = new StringBuilder(120);
        sql.append("UPDATE ");
        sql.append(TABLE.NOTE);
        sql.append(" SET ");
        sql.append(NoteColumns.VERSION);
        sql.append("=" + NoteColumns.VERSION + "+1 ");

        if (id > 0 || !TextUtils.isEmpty(selection)) {
            sql.append(" WHERE ");
        }
        if (id > 0) {
            sql.append(NoteColumns.ID + "=" + String.valueOf(id));
            // 单条更新场景先拼主键条件，再拼附加筛选。
        }
        if (!TextUtils.isEmpty(selection)) {
            // 这里直接用参数替换 ?，用于构造 execSQL 的完整 where 语句。
            // 该逻辑依赖调用方传入可信参数（内部调用场景）。
            String selectString = id > 0 ? parseSelection(selection) : selection;
            for (String args : selectionArgs) {
                // 依次替换第一个 ?，得到最终 SQL 字符串。
                selectString = selectString.replaceFirst("\\?", args);
            }
            sql.append(selectString);
        }

        mHelper.getWritableDatabase().execSQL(sql.toString());
        // 这里使用 execSQL 直接执行更新语句，不返回受影响行数。
    }

    @Override
    public String getType(Uri uri) {
        // TODO Auto-generated method stub
        // 当前未实现 MIME 返回；调用方若依赖 getType 需自行规避。
        return null;
    }

}
